// 文件路径：bluetooth/BluetoothRouter.java
package com.web2blue.shell.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.web2blue.shell.bridge.CallbackDispatcher;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;

import java.nio.charset.StandardCharsets;

/**
 * 蓝牙协议路由器 — BlueAppBridge 和具体蓝牙实现之间的调度中心。
 *
 * <p>职责：
 * 1. 根据 type 参数（"SPP" / "BLE"），将操作路由到对应的 Connector 实例
 * 2. 统一处理 String/HEX 字符串 → byte[] 的转换（send 数据预处理）
 * 3. 管理扫描生命周期（经典蓝牙 BroadcastReceiver + BLE LeScanner）
 * 4. 管理 Connector 实例的创建、复用和释放
 *
 * <p>设计原则：
 * - 对 BlueAppBridge 屏蔽所有蓝牙协议差异，Bridge 只需调用 connect/send/disconnect
 * - 对 Connector 提供统一的上下文（BluetoothAdapter、CallbackDispatcher、LogRepository）
 */
public class BluetoothRouter {

    private static final String TAG = "BluetoothRouter";

    /** BLE 扫描默认持续时间（毫秒），超时后自动停止 */
    private static final long BLE_SCAN_DURATION_MS = 12_000L;

    /** 经典蓝牙扫描默认持续时间（毫秒） */
    private static final long SPP_SCAN_DURATION_MS = 12_000L;

    // ────────────────────────────────────────────────────────
    //  依赖
    // ────────────────────────────────────────────────────────
    private final Context context;
    private final CallbackDispatcher callbackDispatcher;
    private final LogRepository logRepository;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ────────────────────────────────────────────────────────
    //  当前活跃的连接器（同一时刻只有一个）
    // ────────────────────────────────────────────────────────
    private volatile IBluetoothConnector activeConnector;

    // ────────────────────────────────────────────────────────
    //  扫描相关状态
    // ────────────────────────────────────────────────────────
    /** BLE 扫描回调（持有引用用于停止扫描）*/
    private ScanCallback bleScanCallback;

    /** 经典蓝牙扫描广播接收器 */
    private BroadcastReceiver sppScanReceiver;

    /** 扫描超时停止 Runnable */
    private Runnable scanStopRunnable;

    // ════════════════════════════════════════════════════════════════
    //  构造函数
    // ════════════════════════════════════════════════════════════════

    /**
     * @param context            Activity 上下文
     * @param callbackDispatcher JS 回调分发器
     * @param logRepository      全局日志仓库
     */
    public BluetoothRouter(Context context,
                           CallbackDispatcher callbackDispatcher,
                           LogRepository logRepository) {
        this.context             = context;
        this.callbackDispatcher  = callbackDispatcher;
        this.logRepository       = logRepository;
        this.bluetoothAdapter    = BluetoothAdapter.getDefaultAdapter();
    }

    // ════════════════════════════════════════════════════════════════
    //  核心路由方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 路由连接请求到对应协议的 Connector。
     *
     * <p>若当前有活跃连接器且处于连接状态，先断开再新建。
     * 这样 JS 层可以直接调用 connect() 实现"重连"，无需先 disconnect()。
     *
     * @param macAddress 目标设备 MAC 地址
     * @param type       "SPP" 或 "BLE"（已由 BlueAppBridge 转大写）
     */
    public void connect(String macAddress, String type) {
        // 若有旧连接器仍活跃，先释放
        if (activeConnector != null &&
            activeConnector.getState() != IBluetoothConnector.State.DISCONNECTED) {
            logRepository.addLog(LogEntry.system(
                    "[Router] 检测到旧连接，先断开再重连..."
            ));
            activeConnector.release();
            activeConnector = null;
        }

        // 根据 type 创建对应的 Connector 实例
        if ("SPP".equals(type)) {
            logRepository.addLog(LogEntry.system("[Router] 路由至 SPP 经典蓝牙连接器"));
            activeConnector = new SppConnector(
                    context, callbackDispatcher, logRepository, bluetoothAdapter
            );
        } else if ("BLE".equals(type)) {
            logRepository.addLog(LogEntry.system("[Router] 路由至 BLE 低功耗蓝牙连接器"));
            activeConnector = new BleConnector(
                    context, callbackDispatcher, logRepository, bluetoothAdapter
            );
        } else {
            logRepository.addLog(LogEntry.system("[Router] 未知协议类型：" + type));
            callbackDispatcher.dispatchError("unknown_type", "未知协议类型：" + type);
            return;
        }

        activeConnector.connect(macAddress);
    }

    /**
     * 断开当前活跃连接。
     */
    public void disconnect() {
        if (activeConnector == null) {
            logRepository.addLog(LogEntry.system("[Router] disconnect() 调用：当前无活跃连接器"));
            return;
        }
        activeConnector.disconnect();
    }

    /**
     * 发送数据（自动处理 HEX 前缀和字符串编码）。
     *
     * <p>数据格式支持：
     * - 普通字符串："AT+LED=1\r\n" → 直接 UTF-8 编码
     * - HEX 字符串："HEX:FF0102"  → 解析为 byte[] {0xFF, 0x01, 0x02}
     *   HEX 字符串中的空格会被忽略，大小写均可："HEX:FF 01 02" 合法
     *
     * @param data JS 传入的原始字符串
     */
    public void send(String data) {
        if (activeConnector == null ||
            activeConnector.getState() != IBluetoothConnector.State.CONNECTED) {
            logRepository.addLog(LogEntry.system("[Router] send() 失败：当前没有已连接的设备"));
            callbackDispatcher.dispatchError("not_connected", "请先连接蓝牙设备再发送数据");
            return;
        }

        byte[] bytes = parseDataToBytes(data);
        if (bytes == null || bytes.length == 0) {
            logRepository.addLog(LogEntry.system("[Router] send() 失败：数据解析结果为空"));
            return;
        }

        activeConnector.send(bytes);
    }

    /**
     * 查询当前连接状态。
     *
     * @return 连接状态（若无活跃连接器，返回 DISCONNECTED）
     */
    public IBluetoothConnector.State getState() {
        if (activeConnector == null) return IBluetoothConnector.State.DISCONNECTED;
        return activeConnector.getState();
    }

    /**
     * 启动蓝牙设备扫描。
     *
     * @param type "SPP"：仅扫描经典蓝牙；"BLE"：仅扫描低功耗；"ALL"：全部扫描
     */
    public void scan(String type) {
        stopAllScans();  // 先停止可能正在进行的旧扫描

        boolean doSpp = "SPP".equals(type) || "ALL".equals(type);
        boolean doBle = "BLE".equals(type) || "ALL".equals(type);

        if (doSpp) startSppScan();
        if (doBle) startBleScan();

        // 超时后自动停止扫描并通知 JS
        scanStopRunnable = () -> {
            stopAllScans();
            logRepository.addLog(LogEntry.system("[Router] 扫描完成（超时自动停止）"));
            callbackDispatcher.dispatchScanComplete();
        };
        mainHandler.postDelayed(scanStopRunnable,
                Math.max(BLE_SCAN_DURATION_MS, SPP_SCAN_DURATION_MS));
    }

    /**
     * 释放路由器持有的所有资源（在 Activity.onDestroy 中调用）。
     */
    public void release() {
        stopAllScans();
        if (activeConnector != null) {
            activeConnector.release();
            activeConnector = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：数据解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 JS 传入的字符串解析为 byte[]。
     * - "HEX:" 前缀 → HEX 解码
     * - 其他 → UTF-8 编码
     *
     * @param data 原始字符串
     * @return 解析后的字节数组，解析失败返回 null
     */
    private byte[] parseDataToBytes(String data) {
        if (data == null) return null;

        // HEX 模式：前缀 "HEX:"，大小写均支持
        if (data.toUpperCase().startsWith("HEX:")) {
            String hexStr = data.substring(4).replaceAll("\\s+", ""); // 去除所有空格
            if (hexStr.isEmpty()) return null;
            if (hexStr.length() % 2 != 0) {
                logRepository.addLog(LogEntry.system(
                        "[Router] HEX 解析失败：字符数必须为偶数，实际：" + hexStr.length()
                ));
                return null;
            }
            try {
                byte[] result = new byte[hexStr.length() / 2];
                for (int i = 0; i < result.length; i++) {
                    result[i] = (byte) Integer.parseInt(hexStr.substring(i * 2, i * 2 + 2), 16);
                }
                return result;
            } catch (NumberFormatException e) {
                logRepository.addLog(LogEntry.system(
                        "[Router] HEX 解析失败：包含非法字符，原始：" + hexStr
                ));
                return null;
            }
        }

        // 普通字符串模式：直接 UTF-8 编码
        return data.getBytes(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：扫描管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动经典蓝牙（SPP）设备扫描。
     * 使用 BroadcastReceiver 接收扫描结果，这是标准的 Android 经典蓝牙扫描方式。
     */
    @SuppressLint("MissingPermission")
    private void startSppScan() {
        // Android 12+ 需要 BLUETOOTH_SCAN 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                logRepository.addLog(LogEntry.system(
                        "[Router] SPP 扫描失败：缺少 BLUETOOTH_SCAN 权限（Android 12+）"
                ));
                return;
            }
        }

        // 注册广播接收器监听扫描结果
        sppScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    if (device != null) {
                        String name = getDeviceName(device);
                        String mac  = device.getAddress();
                        logRepository.addLog(LogEntry.system(
                                "[SPP 扫描] 发现：" + name + " (" + mac + ") RSSI=" + rssi
                        ));
                        callbackDispatcher.dispatchScanResult(name, mac, "SPP", rssi);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(sppScanReceiver, filter);

        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            bluetoothAdapter.startDiscovery();
            logRepository.addLog(LogEntry.system("[Router] 经典蓝牙扫描已启动"));
        } catch (SecurityException e) {
            logRepository.addLog(LogEntry.system("[Router] SPP 扫描 SecurityException：" + e.getMessage()));
        }
    }

    /**
     * 启动 BLE 低功耗蓝牙扫描。
     * 使用 BluetoothLeScanner（API 21+），支持过滤和后台扫描。
     */
    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                logRepository.addLog(LogEntry.system(
                        "[Router] BLE 扫描失败：缺少 BLUETOOTH_SCAN 权限（Android 12+）"
                ));
                return;
            }
        }

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            logRepository.addLog(LogEntry.system("[Router] BLE 扫描失败：设备不支持 BLE"));
            return;
        }

        bleScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                int rssi = result.getRssi();
                String name = getDeviceName(device);
                String mac  = device.getAddress();
                logRepository.addLog(LogEntry.system(
                        "[BLE 扫描] 发现：" + name + " (" + mac + ") RSSI=" + rssi
                ));
                callbackDispatcher.dispatchScanResult(name, mac, "BLE", rssi);
            }

            @Override
            public void onScanFailed(int errorCode) {
                logRepository.addLog(LogEntry.system(
                        "[Router] BLE 扫描失败，errorCode=" + errorCode
                ));
            }
        };

        try {
            scanner.startScan(bleScanCallback);
            logRepository.addLog(LogEntry.system("[Router] BLE 扫描已启动"));
        } catch (SecurityException e) {
            logRepository.addLog(LogEntry.system("[Router] BLE 扫描 SecurityException：" + e.getMessage()));
        }
    }

    /**
     * 停止所有正在进行的扫描并注销资源。
     */
    @SuppressLint("MissingPermission")
    private void stopAllScans() {
        // 取消超时定时器
        if (scanStopRunnable != null) {
            mainHandler.removeCallbacks(scanStopRunnable);
            scanStopRunnable = null;
        }

        // 停止经典蓝牙扫描
        if (sppScanReceiver != null) {
            try { context.unregisterReceiver(sppScanReceiver); } catch (Exception ignored) {}
            sppScanReceiver = null;
        }
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {}

        // 停止 BLE 扫描
        if (bleScanCallback != null) {
            try {
                BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
                if (scanner != null) scanner.stopScan(bleScanCallback);
            } catch (SecurityException ignored) {}
            bleScanCallback = null;
        }
    }

    /**
     * 安全获取设备名称（Android 12+ 需要 BLUETOOTH_CONNECT 权限）。
     *
     * @param device 蓝牙设备
     * @return 设备名称，无法获取时返回 "未知设备"
     */
    @SuppressLint("MissingPermission")
    private String getDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : "未知设备";
        } catch (SecurityException e) {
            return "未知设备";
        }
    }
}
