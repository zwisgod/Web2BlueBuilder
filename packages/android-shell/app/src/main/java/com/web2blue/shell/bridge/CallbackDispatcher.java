// 文件路径：bridge/CallbackDispatcher.java
package com.web2blue.shell.bridge;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android → JS 回调分发器
 *
 * <p>核心职责：把 Android 原生层产生的事件（蓝牙数据到达、连接成功、错误等），
 * 安全地"注射"回 HTML 里的 JavaScript 世界。
 *
 * <p>实现原理：
 * 使用 WebView.evaluateJavascript() 在 WebView 中执行一段 JS 代码，
 * 调用 HTML 开发者预先注册的全局回调函数 window.BlueApp._onEvent(event, data)。
 *
 * <p>线程安全说明：
 * evaluateJavascript() 必须在主线程（UI 线程）调用，否则会抛出异常。
 * 蓝牙数据接收发生在子线程，所以本类内部使用 mainHandler 强制切回主线程。
 */
public class CallbackDispatcher {

    /** 主线程 Handler，用于将任何线程的回调切换到 UI 线程执行 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** WebView 引用，通过它向 JS 注入执行代码 */
    private final WebView webView;

    /** 串口文本缓冲：将分片拼成完整一行后再回给前端 */
    private final StringBuilder serialLineBuffer = new StringBuilder();

    /**
     * 构造函数
     *
     * @param webView 当前 Activity 的 WebView 实例
     */
    public CallbackDispatcher(WebView webView) {
        this.webView = webView;
    }

    // =========================================================
    //  对外暴露的事件分发方法（供蓝牙层和桥接层调用）
    // =========================================================

    /**
     * 连接成功事件
     *
     * <p>触发 JS：BlueApp._onEvent('connected', { mac: "...", type: "SPP" })
     *
     * @param macAddress 已连接的设备 MAC 地址
     * @param type       连接协议类型 "SPP" 或 "BLE"
     */
    public void dispatchConnected(String macAddress, String type) {
        try {
            JSONObject data = new JSONObject();
            data.put("mac", macAddress);
            data.put("type", type);
            dispatch("connected", data.toString());
        } catch (JSONException e) {
            dispatch("connected", "{}");
        }
    }

    /**
     * 连接断开事件
     *
     * <p>触发 JS：BlueApp._onEvent('disconnected', { reason: "用户主动断开" })
     *
     * @param reason 断开原因的可读描述（中文），方便 HTML 开发者展示
     */
    public void dispatchDisconnected(String reason) {
        synchronized (serialLineBuffer) {
            serialLineBuffer.setLength(0);
        }
        try {
            JSONObject data = new JSONObject();
            data.put("reason", reason == null ? "未知原因" : reason);
            dispatch("disconnected", data.toString());
        } catch (JSONException e) {
            dispatch("disconnected", "{}");
        }
    }

    /**
     * 接收到蓝牙数据事件（这是最高频触发的事件！）
     *
     * <p>同时触发两条 JS 回调通道，兼容不同的前端写法：
     *
     * <p>通道 A（标准 BlueApp 事件总线）：
     * <pre>
     *   window.BlueApp._onEvent('data', { str: "OK\r\n", hex: "4F4B0D0A", length: 4 })
     * </pre>
     *
     * <p>通道 B（简单回调，适合轻量前端）：
     * <pre>
     *   window.onBluetoothDataReceived("OK\r\n")
     * </pre>
     * 通道 B 中的字符串已做 JS 安全转义（单引号、反斜杠、换行符），
     * 传入的是原始串口数据的字符串形式。
     *
     * @param rawBytes 从蓝牙设备接收到的原始字节数组
     */
    public void dispatchDataReceived(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) return;

        try {
            String rawStr = new String(rawBytes, "UTF-8");
            dispatchBufferedLines(rawStr, rawBytes.length);

        } catch (Exception e) {
            dispatch("data", "{}");
        }
    }

    private void dispatchBufferedLines(String rawStr, int rawLength) {
        synchronized (serialLineBuffer) {
            serialLineBuffer.append(rawStr);

            int newlineIndex = indexOfNewline(serialLineBuffer);
            while (newlineIndex >= 0) {
                String frame = serialLineBuffer.substring(0, newlineIndex);
                serialLineBuffer.delete(0, newlineIndex + 1);

                if (!frame.isEmpty() && frame.charAt(frame.length() - 1) == '\r') {
                    frame = frame.substring(0, frame.length() - 1);
                }

                if (!frame.trim().isEmpty()) {
                    dispatchFrame(frame, rawLength);
                }

                newlineIndex = indexOfNewline(serialLineBuffer);
            }
        }
    }

    private void dispatchFrame(String frame, int rawLength) {
        try {
            JSONObject data = new JSONObject();
            data.put("str", frame);
            data.put("length", rawLength);
            dispatch("data", data.toString());

            String safeData = frame
                    .replace("\\", "\\\\")
                    .replace("'",  "\\'")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");

            final String jsBridgeB =
                    "if(typeof window.onBluetoothDataReceived === 'function'){" +
                    "  window.onBluetoothDataReceived('" + safeData + "');" +
                    "}";

            mainHandler.post(() -> webView.evaluateJavascript(jsBridgeB, null));
        } catch (Exception e) {
            dispatch("data", "{}");
        }
    }

    private static int indexOfNewline(StringBuilder buffer) {
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 扫描到新设备事件
     *
     * <p>触发 JS：BlueApp._onEvent('scan_result', { name: "HC-05", mac: "...", type: "SPP", rssi: -65 })
     *
     * @param name 设备名称
     * @param mac  设备 MAC 地址
     * @param type 设备类型 "SPP" 或 "BLE"
     * @param rssi 信号强度（负数，越接近 0 越强）
     */
    public void dispatchScanResult(String name, String mac, String type, int rssi) {
        try {
            JSONObject data = new JSONObject();
            data.put("name", name == null ? "未知设备" : name);
            data.put("mac", mac);
            data.put("type", type);
            data.put("rssi", rssi);
            dispatch("scan_result", data.toString());
        } catch (JSONException e) {
            dispatch("scan_result", "{}");
        }
    }

    /**
     * 扫描完成事件
     *
     * <p>触发 JS：BlueApp._onEvent('scan_complete', {})
     */
    public void dispatchScanComplete() {
        dispatch("scan_complete", "{}");
    }

    /**
     * 错误事件（统一的错误上报出口）
     *
     * <p>触发 JS：BlueApp._onEvent('error', { code: "connect_failed", message: "..." })
     *
     * @param code    错误代码，供程序判断（英文，固定值）
     * @param message 可读的错误描述（中文），供展示给用户
     */
    public void dispatchError(String code, String message) {
        try {
            JSONObject data = new JSONObject();
            data.put("code", code);
            data.put("message", message);
            dispatch("error", data.toString());
        } catch (JSONException e) {
            dispatch("error", "{}");
        }
    }

    // =========================================================
    //  私有核心方法
    // =========================================================

    /**
     * 核心分发方法：将事件和数据拼装成 JS 调用语句，在主线程注入 WebView 执行。
     *
     * <p>最终执行的 JS 代码形如：
     * <pre>
     *   (function(){
     *     if(window.BlueApp && typeof window.BlueApp._onEvent === 'function'){
     *       window.BlueApp._onEvent('connected', {"mac":"AA:BB","type":"SPP"});
     *     }
     *   })();
     * </pre>
     *
     * @param event    事件名，对应 JS 端 on() 注册时使用的名称
     * @param jsonData 已序列化好的 JSON 字符串作为事件数据
     */
    private void dispatch(String event, String jsonData) {
        final String jsCode = String.format(
                "(function(){" +
                "  if(window.BlueApp && typeof window.BlueApp._onEvent === 'function'){" +
                "    window.BlueApp._onEvent('%s', %s);" +
                "  }" +
                "})();",
                event,
                jsonData
        );

        // 强制切回主线程执行，这是硬性要求
        mainHandler.post(() -> webView.evaluateJavascript(jsCode, null));
    }

    /**
     * 工具方法：byte[] → 大写十六进制字符串（无分隔符）
     *
     * <p>例如 {0x4F, 0x4B} → "4F4B"
     *
     * @param bytes 原始字节数组
     * @return 大写十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
