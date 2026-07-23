// 文件路径：bluetooth/SppConnector.java
package com.web2blue.shell.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import android.content.Context;

import com.web2blue.shell.bridge.CallbackDispatcher;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 经典蓝牙（SPP / RFCOMM）连接器 — IBluetoothConnector 的 SPP 协议实现。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【核心线程模型】
 * ═══════════════════════════════════════════════════════════════════
 *
 * SPP 连接涉及三个线程：
 *
 *   1. 【调用方线程】→ 调用 connect() 后立即返回（非阻塞）
 *      │
 *      ▼
 *   2. 【connectThread（后台）】→ 执行阻塞的 socket.connect()
 *      │  成功后启动 readerThread
 *      ▼
 *   3. 【readerThread（后台）】→ 死循环 inputStream.read(buf)
 *         每读到数据 → LogRepository.addLog(rx) + callbackDispatcher.dispatchDataReceived()
 *         IOException（对端关闭 / 超出范围）→ 触发断开流程
 *
 * InputStream.read() 是天然阻塞的，所以必须独占一个后台线程，
 * 绝对不能在主线程或 WebView 线程中调用！
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【回退连接策略（Fallback Socket）】
 * ═══════════════════════════════════════════════════════════════════
 *
 * HC-05 / JDY-31 等老式模块有时会出现配对后无法通过标准 createRfcommSocketToServiceRecord
 * 连接的情况（返回 IOException），原因是设备的 SDP 记录不完整。
 * 解决方案：使用反射调用 createRfcommSocket(1)，强制指定 RFCOMM 通道 1。
 * 本类在标准连接失败后自动尝试 Fallback 方式，无需上层干预。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【Android 12+ 权限】
 * ═══════════════════════════════════════════════════════════════════
 *
 * - BLUETOOTH_CONNECT：所有 BluetoothSocket 操作均需此权限
 * - 本类在每个权限敏感操作前做检查，不满足时记录日志 + 触发 JS error
 */
public class SppConnector implements IBluetoothConnector {

    private static final String TAG = "SppConnector";

    /**
     * SPP（Serial Port Profile）标准 UUID，所有经典蓝牙串口设备通用。
     * HC-05、JDY-31、HC-06 等都使用这个 UUID 注册 RFCOMM 服务。
     */
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** 连接超时：主动断开连接的等待时间（毫秒）*/
    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    /** InputStream 读缓冲区大小（字节）*/
    private static final int READ_BUFFER_SIZE = 1024;

    // ────────────────────────────────────────────────────────
    //  依赖注入
    // ────────────────────────────────────────────────────────
    private final Context context;
    private final CallbackDispatcher callbackDispatcher;
    private final LogRepository logRepository;
    private final BluetoothAdapter bluetoothAdapter;

    // ────────────────────────────────────────────────────────
    //  运行时状态（volatile 保证可见性，AtomicReference 保证原子更新）
    // ────────────────────────────────────────────────────────
    private final AtomicReference<State> currentState = new AtomicReference<>(State.DISCONNECTED);

    /** RFCOMM 套接字，连接建立后持有，断开时关闭 */
    private volatile BluetoothSocket socket;

    /** 数据输出流（App → 单片机），send() 使用 */
    private volatile OutputStream outputStream;

    /** 数据输入流（单片机 → App），readerThread 独占 */
    private volatile InputStream inputStream;

    /** 后台连接线程 */
    private volatile Thread connectThread;

    /** 后台数据读取线程 */
    private volatile Thread readerThread;

    /**
     * 退出标志：设为 true 后，readerThread 的读循环会在下次 IO 操作时退出。
     * volatile 保证其他线程的写操作对 readerThread 立即可见。
     */
    private volatile boolean shouldExit = false;

    /** 记录当前连接的目标 MAC（用于日志和重连） */
    private volatile String targetMac;

    // ════════════════════════════════════════════════════════════════
    //  构造函数
    // ════════════════════════════════════════════════════════════════

    /**
     * @param context            Activity 上下文，用于权限检查
     * @param callbackDispatcher JS 回调分发器
     * @param logRepository      全局日志仓库
     * @param bluetoothAdapter   蓝牙适配器（由 BluetoothRouter 传入）
     */
    public SppConnector(Context context,
                        CallbackDispatcher callbackDispatcher,
                        LogRepository logRepository,
                        BluetoothAdapter bluetoothAdapter) {
        this.context             = context;
        this.callbackDispatcher  = callbackDispatcher;
        this.logRepository       = logRepository;
        this.bluetoothAdapter    = bluetoothAdapter;
    }

    // ════════════════════════════════════════════════════════════════
    //  IBluetoothConnector 接口实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 发起 SPP 连接（非阻塞，内部开启后台线程）。
     *
     * @param macAddress 目标设备 MAC 地址
     */
    @Override
    public void connect(String macAddress) {
        // ── 前置检查 1：防重复连接 ────────────────────────────────
        if (currentState.get() != State.DISCONNECTED) {
            logRepository.addLog(LogEntry.system(
                    "[SPP] 已处于 " + currentState.get() + " 状态，请先断开再重连"
            ));
            return;
        }

        // ── 前置检查 2：Android 12+ 蓝牙权限 ─────────────────────
        if (!checkBluetoothConnectPermission()) return;

        // ── 前置检查 3：蓝牙适配器 ────────────────────────────────
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            String msg = "[SPP] 蓝牙适配器未开启，请先打开手机蓝牙";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("bt_disabled", msg);
            return;
        }

        this.targetMac  = macAddress;
        this.shouldExit = false;
        currentState.set(State.CONNECTING);

        logRepository.addLog(LogEntry.system("[SPP] 开始连接 → " + macAddress));

        // ── 在后台线程执行阻塞连接（主线程安全）────────────────────
        connectThread = new Thread(() -> doConnect(macAddress), "spp-connect-thread");
        connectThread.start();
    }

    /**
     * 断开 SPP 连接并终止所有后台线程。
     * 幂等：多次调用不报错。
     */
    @Override
    public void disconnect() {
        if (currentState.get() == State.DISCONNECTED) return;

        logRepository.addLog(LogEntry.system("[SPP] 主动断开连接"));

        // 设置退出标志，通知 readerThread 的读循环退出
        shouldExit = true;

        // 关闭 Socket 会导致 InputStream.read() 立即抛出 IOException，
        // 这是让 readerThread 干净退出的标准方式
        closeSocketSilently();

        currentState.set(State.DISCONNECTED);
        callbackDispatcher.dispatchDisconnected("用户主动断开");
    }

    /**
     * 发送数据到单片机。
     *
     * <p>OutputStream.write() 是阻塞调用，但对于小包（< 1KB）通常在微秒级完成，
     * 考虑到 SPP 的串行特性，这里直接同步写，无需额外队列。
     * 如果需要发送大量连续数据（例如固件升级），可参考 BleConnector 的写队列机制。
     *
     * @param data 原始字节数组
     */
    @Override
    public void send(byte[] data) {
        if (currentState.get() != State.CONNECTED) {
            logRepository.addLog(LogEntry.system("[SPP] send() 失败：当前未连接"));
            return;
        }
        if (outputStream == null) {
            logRepository.addLog(LogEntry.system("[SPP] send() 失败：OutputStream 为 null"));
            return;
        }
        if (data == null || data.length == 0) {
            logRepository.addLog(LogEntry.system("[SPP] send() 失败：数据为空"));
            return;
        }

        // 记录 TX 日志（在调用 write 之前记录，确保顺序正确）
        logRepository.addLog(LogEntry.tx(data));

        try {
            outputStream.write(data);
            // flush() 确保数据立即从缓冲区发出（SPP 的 OutputStream 一般不缓冲，但显式 flush 更安全）
            outputStream.flush();
        } catch (IOException e) {
            String msg = "[SPP] send() 写入失败：" + e.getMessage() + "，连接可能已断开";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("send_failed", msg);
            // 发送失败通常意味着连接已断开，触发断开流程
            handleUnexpectedDisconnect("发送数据时连接断开");
        }
    }

    /**
     * 查询连接状态（线程安全）。
     */
    @Override
    public State getState() {
        return currentState.get();
    }

    /**
     * 释放所有资源（Activity.onDestroy 调用）。
     */
    @Override
    public void release() {
        shouldExit = true;
        closeSocketSilently();
        interruptThread(connectThread);
        interruptThread(readerThread);
        connectThread = null;
        readerThread  = null;
        currentState.set(State.DISCONNECTED);
        logRepository.addLog(LogEntry.system("[SPP] 资源已完全释放"));
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：连接逻辑（在 connectThread 后台线程中执行）
    // ════════════════════════════════════════════════════════════════

    /**
     * 真正执行阻塞连接操作（运行在 connectThread 后台线程中）。
     *
     * <p>连接策略（双重保险）：
     * 1. 先尝试标准方式：createRfcommSocketToServiceRecord(SPP_UUID)
     * 2. 若失败，自动 Fallback：反射调用 createRfcommSocket(1)（强制通道 1）
     *
     * @param macAddress 目标设备 MAC 地址
     */
    @SuppressLint("MissingPermission")
    private void doConnect(String macAddress) {
        BluetoothDevice device;
        try {
            device = bluetoothAdapter.getRemoteDevice(macAddress);
        } catch (IllegalArgumentException e) {
            String msg = "[SPP] MAC 地址格式非法：" + macAddress;
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("invalid_mac", msg);
            currentState.set(State.DISCONNECTED);
            return;
        }

        // ── 经典蓝牙连接前必须取消搜索，否则连接速度极慢甚至失败 ──
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                logRepository.addLog(LogEntry.system("[SPP] 已取消蓝牙搜索（连接前必要操作）"));
            }
        } catch (SecurityException ignored) {}

        // ── 策略 1：标准 RFCOMM Socket ────────────────────────────
        BluetoothSocket candidateSocket = null;
        try {
            candidateSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            logRepository.addLog(LogEntry.system("[SPP] 使用标准 RFCOMM Socket，正在连接..."));
            candidateSocket.connect();  // ← 阻塞，直到连接成功或抛出 IOException
            logRepository.addLog(LogEntry.system("[SPP] 标准连接成功！"));
        } catch (SecurityException e) {
            handleSecurityException("createRfcommSocket", e);
            closeSocket(candidateSocket);
            return;
        } catch (IOException e) {
            // 标准方式失败，尝试 Fallback
            logRepository.addLog(LogEntry.system(
                    "[SPP] 标准连接失败：" + e.getMessage() + "，尝试 Fallback 方式（反射通道1）..."
            ));
            closeSocket(candidateSocket);
            candidateSocket = connectViaFallback(device);
        }

        // ── 检查最终连接结果 ──────────────────────────────────────
        if (candidateSocket == null || !candidateSocket.isConnected()) {
            String msg = "[SPP] 连接失败：两种方式均未成功，请检查设备是否配对并在范围内";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("connect_failed", msg);
            currentState.set(State.DISCONNECTED);
            closeSocket(candidateSocket);
            return;
        }

        // ── 连接成功：保存 Socket，获取 IO 流 ────────────────────
        socket = candidateSocket;
        try {
            outputStream = socket.getOutputStream();
            inputStream  = socket.getInputStream();
        } catch (IOException e) {
            String msg = "[SPP] 获取 IO 流失败：" + e.getMessage();
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("io_stream_failed", msg);
            currentState.set(State.DISCONNECTED);
            closeSocketSilently();
            return;
        }

        // ── 正式宣告连接成功 ─────────────────────────────────────
        currentState.set(State.CONNECTED);
        logRepository.addLog(LogEntry.system("[SPP] 连接完成，开始监听数据..."));
        callbackDispatcher.dispatchConnected(macAddress, "SPP");

        // ── 启动 InputStream 阻塞读取线程 ────────────────────────
        startReaderThread();
    }

    /**
     * Fallback 连接策略：通过反射强制使用 RFCOMM 通道 1。
     *
     * <p>部分老式模块（尤其是 JDY-31、HC-06 早期固件）的 SDP 记录不标准，
     * 标准 UUID 查询失败，但直接指定通道 1 可以成功连接。
     * 这是社区广泛验证的兼容性解决方案。
     *
     * @param device 蓝牙设备对象
     * @return 已连接的 BluetoothSocket，失败返回 null
     */
    @SuppressLint("MissingPermission")
    private BluetoothSocket connectViaFallback(BluetoothDevice device) {
        try {
            // 通过反射调用隐藏的 createRfcommSocket(int channel) 方法
            java.lang.reflect.Method method = device.getClass()
                    .getMethod("createRfcommSocket", int.class);
            BluetoothSocket fallbackSocket = (BluetoothSocket) method.invoke(device, 1);

            if (fallbackSocket == null) {
                logRepository.addLog(LogEntry.system("[SPP] Fallback：反射创建 Socket 返回 null"));
                return null;
            }

            logRepository.addLog(LogEntry.system("[SPP] Fallback Socket 已创建（通道 1），正在连接..."));
            fallbackSocket.connect();  // 同样是阻塞调用
            logRepository.addLog(LogEntry.system("[SPP] Fallback 连接成功！"));
            return fallbackSocket;

        } catch (SecurityException e) {
            handleSecurityException("fallback connect", e);
            return null;
        } catch (Exception e) {
            // 反射调用异常（方法不存在 / 调用失败 / 连接超时等）
            logRepository.addLog(LogEntry.system(
                    "[SPP] Fallback 连接也失败：" + e.getMessage()
            ));
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：InputStream 阻塞读取线程
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动 InputStream 数据读取后台线程。
     *
     * <p>【关键设计】：
     * inputStream.read(buffer) 是阻塞调用，会一直等待直到：
     * a. 收到数据 → 返回实际读取的字节数
     * b. 对端关闭连接 → 返回 -1
     * c. 连接断开 / Socket 被关闭 → 抛出 IOException
     *
     * 通过 shouldExit 标志 + closeSocket() 双重机制确保线程干净退出：
     * - 主动断开：disconnect() 设置 shouldExit=true，关闭 Socket → read() 抛 IOException → 线程退出
     * - 意外断开：对端断电/超出范围 → read() 返回 -1 或抛 IOException → 触发断开流程
     */
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            logRepository.addLog(LogEntry.system("[SPP] 数据读取线程已启动"));

            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int bytesRead;

            // ── 核心：阻塞读循环 ──────────────────────────────────
            while (!shouldExit) {
                try {
                    // InputStream.read() 阻塞在这里，直到有数据或发生异常
                    bytesRead = inputStream.read(buffer);

                    if (bytesRead == -1) {
                        // 返回 -1 表示流已到末尾（对端正常关闭了连接）
                        logRepository.addLog(LogEntry.system("[SPP] InputStream 返回 -1，对端已关闭连接"));
                        break;  // 跳出循环，进入断开处理
                    }

                    if (bytesRead > 0) {
                        // 复制有效字节（buffer 是复用的，不能直接传引用）
                        byte[] received = new byte[bytesRead];
                        System.arraycopy(buffer, 0, received, 0, bytesRead);

                        // 记录 RX 日志（同步，在 readerThread 线程上执行，不阻塞）
                        logRepository.addLog(LogEntry.rx(received));

                        // 异步通知 JS（CallbackDispatcher 内部切主线程）
                        callbackDispatcher.dispatchDataReceived(received);
                    }

                } catch (IOException e) {
                    if (shouldExit) {
                        // shouldExit=true 说明是我们主动关闭的，属于正常退出，不报错
                        logRepository.addLog(LogEntry.system("[SPP] 读取线程正常退出（主动断开）"));
                    } else {
                        // shouldExit=false 说明是意外断开（超出范围、对端断电等）
                        logRepository.addLog(LogEntry.system(
                                "[SPP] 读取线程意外 IOException：" + e.getMessage() + "，连接已断开"
                        ));
                        handleUnexpectedDisconnect("连接意外断开：" + e.getMessage());
                    }
                    break;  // 无论什么原因，IOException 后必须退出循环
                }
            }

            logRepository.addLog(LogEntry.system("[SPP] 数据读取线程已退出"));

        }, "spp-reader-thread");

        // 设为守护线程：当 App 进程退出时，守护线程自动终止，不阻止 JVM 退出
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：断开与资源清理
    // ════════════════════════════════════════════════════════════════

    /**
     * 处理意外断开（对端断电、超出范围等由 readerThread 检测到的情况）。
     * 此方法在 readerThread 中被调用，需注意不能直接操作 UI。
     *
     * @param reason 断开原因描述
     */
    private void handleUnexpectedDisconnect(String reason) {
        // 防止重复触发（readerThread 和 connectThread 可能同时检测到断开）
        if (!currentState.compareAndSet(State.CONNECTED, State.DISCONNECTED) &&
            !currentState.compareAndSet(State.CONNECTING, State.DISCONNECTED)) {
            return;  // 已经是 DISCONNECTED 状态，跳过
        }

        shouldExit = true;
        closeSocketSilently();
        callbackDispatcher.dispatchDisconnected(reason);
    }

    /**
     * 静默关闭 Socket 及 IO 流，忽略所有异常。
     * "静默"意味着：关闭失败时只记录日志，不抛出异常，保证后续代码能继续执行。
     */
    private void closeSocketSilently() {
        // 按顺序关闭：先关流，再关 Socket
        // 注意：关闭 Socket 会自动关闭其 InputStream/OutputStream，
        // 但显式关闭流再关 Socket 可以避免某些设备上的资源泄漏

        if (outputStream != null) {
            try { outputStream.close(); } catch (IOException ignored) {}
            outputStream = null;
        }
        if (inputStream != null) {
            try { inputStream.close(); } catch (IOException ignored) {}
            inputStream = null;
        }
        closeSocket(socket);
        socket = null;
    }

    /**
     * 关闭指定 Socket（静默，用于 Fallback 失败时清理临时 Socket）。
     *
     * @param s 要关闭的 BluetoothSocket，null 安全
     */
    private static void closeSocket(BluetoothSocket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 中断指定线程（用于 release() 时强制停止残留线程）。
     *
     * @param t 要中断的线程，null 安全
     */
    private static void interruptThread(Thread t) {
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：权限检查工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查 Android 12+ 的 BLUETOOTH_CONNECT 权限。
     * API 30 及以下不需要此权限，直接返回 true。
     *
     * @return true = 有权限或无需权限，false = 缺少权限（已记录日志并触发 JS error）
     */
    private boolean checkBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "[SPP] 缺少 BLUETOOTH_CONNECT 权限（Android 12+ 必须）！" +
                        "请在 AndroidManifest.xml 中声明并在运行时申请此权限。";
                logRepository.addLog(LogEntry.system(msg));
                callbackDispatcher.dispatchError("permission_denied", msg);
                currentState.set(State.DISCONNECTED);
                return false;
            }
        }
        return true;
    }

    /**
     * 统一处理 SecurityException。
     *
     * @param operation 发生异常的操作名（用于日志定位）
     * @param e         捕获到的 SecurityException
     */
    private void handleSecurityException(String operation, SecurityException e) {
        String msg = "[SPP] SecurityException @ " + operation +
                "：缺少蓝牙权限（Android 12+ 需要 BLUETOOTH_CONNECT）。详情：" + e.getMessage();
        logRepository.addLog(LogEntry.system(msg));
        callbackDispatcher.dispatchError("security_exception", msg);
        currentState.set(State.DISCONNECTED);
        Log.e(TAG, msg, e);
    }
}
