// 文件路径：bridge/BlueAppBridge.java
package com.web2blue.shell.bridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.web2blue.shell.bluetooth.BluetoothRouter;
import com.web2blue.shell.debug.DebugConsoleView;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JS 桥接入口 — HTML 世界与 Android 原生世界之间的"边境海关"。
 *
 * <p>权限策略：
 * 采用"按需触发"策略。当用户点击 HTML 中的连接相关按钮时，
 * JS 调用 selectDevice() 或 connect()，此时若检测到权限缺失，
 * 立即弹出系统权限请求对话框。权限结果由 WebViewActivity 接收后
 * 回调 notifyPermissionGranted() / notifyPermissionDenied()，
 * 再通过 JS 事件通知前端。
 *
 * <p>线程模型：
 * @JavascriptInterface 方法在 WebView 后台线程执行，禁止直接操作 UI。
 * AlertDialog 和 requestPermissions 均通过 webView.post() 切换到主线程。
 */
public class BlueAppBridge {

    /**
     * 蓝牙权限请求码，必须与 WebViewActivity.REQUEST_CODE_BT_PERMISSIONS 保持一致。
     * WebViewActivity 声明为 package-private static final，此处通过引用保持同步。
     */
    private static final int REQUEST_CODE_BT_PERMISSIONS = 2001;

    /** Activity 引用，用于 requestPermissions 和 AlertDialog */
    private final Activity activity;

    /** Context 引用（与 activity 同一对象，保留用于兼容接受 Context 的 API） */
    private final Context context;

    /** WebView 引用，用于 post 切主线程、回调 JS */
    private final WebView webView;

    /** 蓝牙路由器 */
    private final BluetoothRouter bluetoothRouter;

    /** 日志仓库 */
    private final LogRepository logRepository;

    /** 调试悬浮窗 */
    private final DebugConsoleView debugConsoleView;

    /**
     * 构造函数，由 WebViewActivity 在初始化 WebView 时调用一次。
     *
     * @param context          Activity 上下文（运行时必须是 Activity 子类）
     * @param webView          当前 WebView 实例
     * @param debugConsoleView 已创建好的悬浮窗实例
     */
    public BlueAppBridge(Context context,
                         WebView webView,
                         DebugConsoleView debugConsoleView) {
        this.context          = context;
        // context 由 WebViewActivity 传入，运行时始终是 Activity 实例
        this.activity         = (Activity) context;
        this.webView          = webView;
        this.debugConsoleView = debugConsoleView;
        this.logRepository    = LogRepository.getInstance();

        CallbackDispatcher callbackDispatcher = new CallbackDispatcher(webView);
        this.bluetoothRouter = new BluetoothRouter(context, callbackDispatcher, logRepository);
    }

    // ════════════════════════════════════════════════════════════════
    //  JS API — 以下所有 public 方法必须加 @JavascriptInterface 注解
    // ════════════════════════════════════════════════════════════════

    /**
     * 【JS API】发起蓝牙连接。
     *
     * <p>JS 调用示例：
     * <pre>
     * BlueApp.connect("AA:BB:CC:DD:EE:FF", "SPP");
     * BlueApp.connect("AA:BB:CC:DD:EE:FF", "BLE");
     * </pre>
     *
     * @param macAddress 目标设备 MAC 地址，格式 "XX:XX:XX:XX:XX:XX"
     * @param type       协议类型："SPP" 或 "BLE"
     */
    @JavascriptInterface
    public void connect(String macAddress, String type) {
        if (macAddress == null || macAddress.trim().isEmpty()) {
            logRepository.addLog(LogEntry.system("[Bridge] connect() 失败：MAC 地址不能为空"));
            return;
        }
        if (type == null || (!type.equalsIgnoreCase("SPP") && !type.equalsIgnoreCase("BLE"))) {
            logRepository.addLog(LogEntry.system(
                    "[Bridge] connect() 失败：type 必须为 SPP 或 BLE，收到：" + type));
            return;
        }

        // 连接前检查权限，缺少时自动申请
        if (!hasBluetoothPermissions()) {
            logRepository.addLog(LogEntry.system("[Bridge] connect() 权限不足，正在申请..."));
            webView.post(this::requestBluetoothPermissionsNow);
            dispatchPermissionRequired("connect()");
            return;
        }

        logRepository.addLog(LogEntry.system(
                String.format("[Bridge] 收到连接指令 → %s [%s]", macAddress, type.toUpperCase())));
        bluetoothRouter.connect(macAddress, type.toUpperCase());
    }

    /**
     * 【JS API】弹出原生蓝牙设备选择器。
     *
     * <p>权限处理：若缺少蓝牙权限，立即触发系统权限弹窗，
     * 并通过 JS 事件通知前端权限请求已发起（用户授权后需再次调用）。
     *
     * <p>设备列表回调：通过 window.onDeviceListReceived(devices, type) 返回，
     * 其中 devices 为 JSON 数组，每项含 name、mac 字段。
     *
     * <p>JS 调用示例：
     * <pre>
     * BlueApp.selectDevice("SPP");
     * BlueApp.selectDevice("BLE");
     * BlueApp.selectDevice("ALL");
     * </pre>
     *
     * @param type 期望的协议类型
     */
    @JavascriptInterface
    public void selectDevice(String type) {
        final String preferredType =
                (type == null || type.trim().isEmpty()) ? "SPP" : type.trim().toUpperCase();

        // ── 第一步：权限检查（在后台线程完成，无 UI 操作）──────────
        if (!hasBluetoothPermissions()) {
            logRepository.addLog(LogEntry.system(
                    "[Bridge] selectDevice() 权限不足，弹出系统权限申请对话框"));

            // 切主线程申请权限（requestPermissions 必须在主线程调用）
            webView.post(this::requestBluetoothPermissionsNow);

            // 通知 JS：权限申请已发起，用户授权后请重新调用 selectDevice()
            dispatchPermissionRequired("selectDevice()");
            return;
        }

        logRepository.addLog(LogEntry.system("[Bridge] 打开设备选择器，协议类型：" + preferredType));

        // ── 第二步：权限就绪，切主线程弹设备列表 ──────────────────
        webView.post(() -> showDevicePickerDialog(preferredType));
    }

    /**
     * 【JS API】断开当前蓝牙连接。
     */
    @JavascriptInterface
    public void disconnect() {
        logRepository.addLog(LogEntry.system("[Bridge] 收到断开连接指令"));
        bluetoothRouter.disconnect();
    }

    /**
     * 【JS API】向已连接的蓝牙设备发送数据。
     *
     * <p>数据格式：
     * - 普通字符串："AT+LED=1\r\n"
     * - HEX 字符串："HEX:FF0102"（BluetoothRouter 内部解析）
     *
     * @param data 要发送的数据
     */
    @JavascriptInterface
    public void send(String data) {
        if (data == null) {
            logRepository.addLog(LogEntry.system("[Bridge] send() 失败：数据不能为 null"));
            return;
        }
        bluetoothRouter.send(data);
    }

    /**
     * 【JS API】扫描附近的蓝牙设备。
     *
     * @param type "SPP"、"BLE" 或 "ALL"
     */
    @JavascriptInterface
    public void scan(String type) {
        String scanType = (type == null) ? "ALL" : type.toUpperCase();
        logRepository.addLog(LogEntry.system("[Bridge] 开始扫描蓝牙设备，类型：" + scanType));
        bluetoothRouter.scan(scanType);
    }

    /**
     * 【JS API】主动唤起硬件调试悬浮控制台。
     */
    @JavascriptInterface
    public void showDebugConsole() {
        logRepository.addLog(LogEntry.system("[Bridge] 通过 JS API 唤起调试控制台"));
        webView.post(() -> debugConsoleView.show());
    }

    /**
     * 【JS API】隐藏调试控制台。
     */
    @JavascriptInterface
    public void hideDebugConsole() {
        webView.post(() -> debugConsoleView.hide());
    }

    /**
     * 【JS API】查询当前蓝牙连接状态（同步返回）。
     *
     * @return "CONNECTED" / "CONNECTING" / "DISCONNECTED"
     */
    @JavascriptInterface
    public String getConnectionState() {
        return bluetoothRouter.getState().name();
    }

    // ════════════════════════════════════════════════════════════════
    //  权限结果通知（由 WebViewActivity.onRequestPermissionsResult 调用）
    // ════════════════════════════════════════════════════════════════

    /**
     * 权限被授予后由 WebViewActivity 调用，向 JS 派发"权限已就绪"事件。
     * 前端收到此事件后可自动重试上一次被阻断的操作。
     */
    public void notifyPermissionGranted() {
        logRepository.addLog(LogEntry.system("[Bridge] 权限已授予，通知 JS"));
        final String js =
                "(function(){" +
                "  if(window.BlueApp && typeof window.BlueApp._onEvent === 'function'){" +
                "    window.BlueApp._onEvent('permission_granted',{});" +
                "  }" +
                "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /**
     * 权限被拒绝后由 WebViewActivity 调用，向 JS 派发"权限被拒绝"错误事件。
     */
    public void notifyPermissionDenied() {
        logRepository.addLog(LogEntry.system("[Bridge] 权限被拒绝，通知 JS"));
        final String js =
                "(function(){" +
                "  if(window.BlueApp && typeof window.BlueApp._onEvent === 'function'){" +
                "    window.BlueApp._onEvent('error',{" +
                "      code:'permission_denied'," +
                "      message:'蓝牙权限被拒绝，无法使用蓝牙功能'});" +
                "  }" +
                "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：权限管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查当前是否已拥有所有必要的蓝牙权限。
     *
     * <p>Android 12+：需要 BLUETOOTH_CONNECT + BLUETOOTH_SCAN
     * <p>Android 6~11：需要 ACCESS_FINE_LOCATION
     *
     * @return true = 权限就绪，false = 至少有一个权限缺失
     */
    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 发起系统蓝牙权限申请对话框（必须在主线程调用）。
     * 申请结果通过 WebViewActivity.onRequestPermissionsResult() 回调。
     */
    private void requestBluetoothPermissionsNow() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (needed.isEmpty()) return;

        // 通过 Activity 发起系统权限弹窗，结果回调到 WebViewActivity.onRequestPermissionsResult
        ActivityCompat.requestPermissions(
                activity,
                needed.toArray(new String[0]),
                REQUEST_CODE_BT_PERMISSIONS
        );
    }

    /**
     * 向 JS 发送"权限申请已发起"事件，告知前端等待授权后重试。
     *
     * @param callerApi 触发权限申请的 API 名称，用于日志
     */
    private void dispatchPermissionRequired(String callerApi) {
        logRepository.addLog(LogEntry.system(
                "[Bridge] 权限申请已发起（来自 " + callerApi + "），等待用户授权..."));
        final String js =
                "(function(){" +
                "  if(window.BlueApp && typeof window.BlueApp._onEvent === 'function'){" +
                "    window.BlueApp._onEvent('error',{" +
                "      code:'permission_required'," +
                "      message:'蓝牙权限申请中，授权后请重新操作'});" +
                "  }" +
                "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：原生设备选择器
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建已配对设备列表并通过 JS 回调通知前端（必须在主线程调用）。
     *
     * <p>回调格式：
     * <pre>
     * window.onDeviceListReceived(
     * [{ name: "HC-05", mac: "AA:BB:CC:DD:EE:FF" }, ...],
     * "SPP"
     * )
     * </pre>
     *
     * <p>若权限在此时仍未就绪（极少情况），向 JS 派发 error 事件。
     *
     * @param preferredType 协议类型，原样传给 JS 回调
     */
    private void showDevicePickerDialog(String preferredType) {
        // 1. 权限最后防线检查
        if (!hasBluetoothPermissions()) {
            logRepository.addLog(LogEntry.system("[Bridge] 权限未就绪，取消列表获取"));
            return;
        }

        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        StringBuilder json = new StringBuilder("[");

        if (btAdapter != null) {
            Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
            if (paired != null) {
                int count = 0;
                for (BluetoothDevice device : paired) {
                    String rawName = null;
                    try { rawName = device.getName(); } catch (SecurityException ignored) {}
                    if (rawName == null || rawName.isEmpty()) rawName = "未知设备";

                    String mac = device.getAddress();

                    // 构建 JSON 并处理转义
                    String jsonName = rawName.replace("\\", "\\\\").replace("\"", "\\\"");
                    if (count > 0) json.append(",");
                    json.append("{\"name\":\"").append(jsonName)
                        .append("\",\"mac\":\"").append(mac).append("\"}");
                    count++;
                }
            }
        }
        json.append("]");

        // 🚀 核心：唯一通道 —— 将数据传给你的 Mac 风格 HTML 列表
        String safeType = preferredType.replace("'", "\\'");
        String jsCallback = "if(typeof window.onDeviceListReceived === 'function'){" +
                "  window.onDeviceListReceived(" + json + ",'" + safeType + "');" +
                "}";
        
        // 确保在主线程执行 JS 注入
        webView.post(() -> webView.evaluateJavascript(jsCallback, null));
        
        logRepository.addLog(LogEntry.system("[Bridge] 已将 " + (btAdapter != null ? "配对列表" : "空列表") + " 发送至 HTML"));
    }
} 