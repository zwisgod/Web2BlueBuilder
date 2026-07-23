// 文件路径：android-shell/app/src/main/java/com/web2blue/shell/bluetooth/BleConnector.java
package com.web2blue.shell.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.web2blue.shell.bridge.CallbackDispatcher;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BLE（低功耗蓝牙 / GATT）连接器 — IBluetoothConnector 的 BLE 协议实现。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【核心设计：混合自适应 UUID 策略】
 * ═══════════════════════════════════════════════════════════════════
 * 市面上的 BLE 模块五花八门（HM-10、AT-09、MLT-BT05 等），
 * 它们的 Service UUID 和 Characteristic UUID 各不相同。
 * 本策略不硬编码任何 UUID，而是在 onServicesDiscovered 回调中：
 *   1. 遍历所有 Service 下的所有 Characteristic
 *   2. 找到拥有 WRITE / WRITE_NO_RESPONSE 属性的 → 用作 TX（发送通道）
 *   3. 找到拥有 NOTIFY / INDICATE 属性的 → 用作 RX（接收通道）
 *   4. 将最终匹配的 UUID 打印到调试悬浮窗，开发者一目了然
 * 这样，同一套代码可以无缝适配几乎所有主流 BLE 串口透传模块。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【Android 12+ 权限注意事项】
 * ═══════════════════════════════════════════════════════════════════
 * Android 12（API 31）起，以下操作需要新权限：
 *   - BLUETOOTH_CONNECT：连接、断开、读写 Gatt
 *   - BLUETOOTH_SCAN：扫描设备
 * 本类在每个需要权限的方法入口做 checkPermission()，
 * 不满足时记录日志 + 触发 JS error 回调，绝不直接 crash。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 【线程模型】
 * ═══════════════════════════════════════════════════════════════════
 * - BluetoothGattCallback 的所有回调：在 Android 内部的 Binder 线程执行
 * - send() 方法：由调用方线程触发，内部切到 writeQueue 串行化处理
 * - UI 操作 / JS 回调：一律通过 CallbackDispatcher 切回主线程
 * - 状态变量 currentState：AtomicReference 保证可见性
 */
public class BleConnector implements IBluetoothConnector {

    private static final String TAG = "BleConnector";

    // ─── GATT 连接超时时间 ────────────────────────────────────────
    /** 连接超时：15 秒。超时后主动断开，避免永久 CONNECTING 状态 */
    private static final long CONNECT_TIMEOUT_MS = 15_000L;

    // ─── CCCD（Client Characteristic Configuration Descriptor）────
    /**
     * 开启 NOTIFY / INDICATE 必须写这个描述符（固定 UUID，蓝牙规范定义）。
     * 写入 ENABLE_NOTIFICATION_VALUE 开启 Notify，
     * 写入 ENABLE_INDICATION_VALUE 开启 Indicate。
     */
    private static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ─── 依赖注入 ─────────────────────────────────────────────────
    private final Context context;
    private final CallbackDispatcher callbackDispatcher;
    private final LogRepository logRepository;
    private final BluetoothAdapter bluetoothAdapter;

    // ─── 运行时状态 ───────────────────────────────────────────────
    /** 当前连接状态，AtomicReference 保证多线程可见性，无需加锁 */
    private final AtomicReference<State> currentState = new AtomicReference<>(State.DISCONNECTED);

    /** GATT 连接句柄，所有后续操作（读写、订阅）都通过它 */
    private volatile BluetoothGatt bluetoothGatt;

    // ─── UUID 自适应结果 ──────────────────────────────────────────
    /** 自适应策略匹配到的 TX 特征值（WRITE / WRITE_NO_RESPONSE） */
    private volatile BluetoothGattCharacteristic txCharacteristic;

    /** 自适应策略匹配到的 RX 特征值（NOTIFY / INDICATE） */
    private volatile BluetoothGattCharacteristic rxCharacteristic;

    /**
     * RX 特征值是 INDICATE 类型（而非 NOTIFY）？
     * 两者的区别：INDICATE 需要客户端回 ACK，更可靠但稍慢；
     * NOTIFY 不需要 ACK，速度更快。
     * 写 CCCD 时需要根据此标志选择不同的值。
     */
    private volatile boolean rxIsIndicate = false;

    // ─── 写操作串行队列 ───────────────────────────────────────────
    /**
     * BLE 的 GATT 写操作必须串行执行：必须等上一条 write 的 onCharacteristicWrite
     * 回调返回后，才能发下一条。否则会丢包！
     * 这个队列 + writeInProgress 标志共同实现写操作的串行化。
     */
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private volatile boolean writeInProgress = false;

    // ─── 超时 Handler ─────────────────────────────────────────────
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable connectTimeoutRunnable;

    // ─── 记录当前连接的目标设备信息（用于日志）─────────────────────
    private volatile String targetMac;

    // ════════════════════════════════════════════════════════════════
    //  构造函数
    // ════════════════════════════════════════════════════════════════

    /**
     * @param context            Activity 上下文，用于权限检查和 connectGatt
     * @param callbackDispatcher 事件分发器，用于向 JS 层回调
     * @param logRepository      日志仓库，所有事件写入此处
     * @param bluetoothAdapter   蓝牙适配器，由 BluetoothRouter 传入（保证单一来源）
     */
    public BleConnector(Context context,
                        CallbackDispatcher callbackDispatcher,
                        LogRepository logRepository,
                        BluetoothAdapter bluetoothAdapter) {
        this.context = context;
        this.callbackDispatcher = callbackDispatcher;
        this.logRepository = logRepository;
        this.bluetoothAdapter = bluetoothAdapter;
    }

    // ════════════════════════════════════════════════════════════════
    //  IBluetoothConnector 接口实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 发起 BLE 连接。
     *
     * <p>连接是异步的：调用 connectGatt() 后立即返回，
     * 实际连接结果通过 GattCallback.onConnectionStateChange 回调通知。
     *
     * @param macAddress 目标 BLE 设备的 MAC 地址
     */
    @Override
    public void connect(String macAddress) {
        // ── 前置检查 1：防重复连接 ────────────────────────────────
        if (currentState.get() != State.DISCONNECTED) {
            logRepository.addLog(LogEntry.system(
                    "[BLE] 已处于 " + currentState.get() + " 状态，请先断开再重连"
            ));
            return;
        }

        // ── 前置检查 2：Android 12+ 蓝牙权限 ─────────────────────
        if (!checkBluetoothConnectPermission()) {
            // checkBluetoothConnectPermission 内部已记录日志和触发 JS error
            return;
        }

        // ── 前置检查 3：蓝牙适配器是否开启 ───────────────────────
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            String msg = "[BLE] 蓝牙适配器未开启，请先打开手机蓝牙";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("bt_disabled", msg);
            return;
        }

        this.targetMac = macAddress;
        currentState.set(State.CONNECTING);

        // ── 记录连接意图到调试窗 ──────────────────────────────────
        logRepository.addLog(LogEntry.system("[BLE] 开始连接 → " + macAddress));

        // ── 启动连接超时计时器 ────────────────────────────────────
        startConnectTimeout(macAddress);

        // ── 发起 GATT 连接（此处需要 BLUETOOTH_CONNECT 权限） ─────
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

            /*
             * connectGatt 参数说明：
             * - context:      上下文
             * - autoConnect:  false = 直接连接（推荐，速度快）；
             *                 true  = 后台自动重连（省电但延迟高）
             * - gattCallback: 所有 GATT 事件的回调处理器
             * - transport:    TRANSPORT_LE = 强制使用 BLE 传输，
             *                 避免双模设备走经典蓝牙通道
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(
                        context,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE  // API 23+ 才有，强制 BLE 通道
                );
            } else {
                bluetoothGatt = device.connectGatt(context, false, gattCallback);
            }

        } catch (SecurityException e) {
            // Android 12+ 未授权时抛出，做最后一道安全防护
            handleSecurityException("connect", e);
        } catch (IllegalArgumentException e) {
            // MAC 地址格式非法时抛出
            String msg = "[BLE] MAC 地址格式非法：" + macAddress;
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("invalid_mac", msg);
            currentState.set(State.DISCONNECTED);
        }
    }

    /**
     * 断开 BLE 连接并清理资源。
     * 此方法可在任意线程安全调用，内部保证幂等性。
     */
    @Override
    public void disconnect() {
        cancelConnectTimeout();

        if (bluetoothGatt == null) {
            currentState.set(State.DISCONNECTED);
            return;
        }

        logRepository.addLog(LogEntry.system("[BLE] 主动断开连接"));

        try {
            bluetoothGatt.disconnect();
            // disconnect() 是异步的，真正的资源释放在 onConnectionStateChange 的
            // STATE_DISCONNECTED 回调里执行 gatt.close()
        } catch (SecurityException e) {
            handleSecurityException("disconnect", e);
            // 即使 disconnect 失败，也要强制 close 释放资源，防止 GATT 资源泄漏
            forceCloseGatt();
        }
    }

    /**
     * 向 BLE 设备发送数据。
     *
     * <p>数据会先加入写队列，再由串行机制逐条发送，防止 BLE 丢包。
     *
     * @param data 待发送的原始字节数组（由 BluetoothRouter 完成 String/HEX → byte[] 转换）
     */
    @Override
    public void send(byte[] data) {
        // ── 前置检查 ──────────────────────────────────────────────
        if (currentState.get() != State.CONNECTED) {
            logRepository.addLog(LogEntry.system("[BLE] send() 失败：当前未处于连接状态"));
            return;
        }
        if (txCharacteristic == null) {
            logRepository.addLog(LogEntry.system("[BLE] send() 失败：未找到 TX 特征值，请检查设备服务"));
            return;
        }
        if (data == null || data.length == 0) {
            logRepository.addLog(LogEntry.system("[BLE] send() 失败：数据为空"));
            return;
        }

        // ── 记录 TX 日志到调试控制台 ─────────────────────────────
        logRepository.addLog(LogEntry.tx(data));

        // ── 加入串行写队列 ────────────────────────────────────────
        writeQueue.offer(data);
        processWriteQueue();
    }

    /**
     * 查询当前连接状态（线程安全）。
     */
    @Override
    public State getState() {
        return currentState.get();
    }

    /**
     * 释放所有资源（在 Activity.onDestroy 中调用）。
     */
    @Override
    public void release() {
        cancelConnectTimeout();
        writeQueue.clear();
        forceCloseGatt();
        currentState.set(State.DISCONNECTED);
        logRepository.addLog(LogEntry.system("[BLE] 资源已完全释放"));
    }

    // ════════════════════════════════════════════════════════════════
    //  GATT 核心回调（BluetoothGattCallback）
    // ════════════════════════════════════════════════════════════════

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        /**
         * 连接状态变化回调
         * 触发场景：connectGatt() 成功/失败、disconnect() 完成、连接意外断开
         *
         * @param gatt     GATT 客户端
         * @param status   操作结果状态码（GATT_SUCCESS = 0 表示成功，其他为错误码）
         * @param newState 新的连接状态（STATE_CONNECTED / STATE_DISCONNECTED）
         */
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // ── 连接建立成功 ──────────────────────────────────
                cancelConnectTimeout();  // 取消超时定时器
                logRepository.addLog(LogEntry.system(
                        "[BLE] GATT 连接成功（status=" + status + "），正在发现服务..."
                ));

                // MTU 协商：请求更大的 MTU（默认 23 字节太小）
                // 255 字节是常用上限，实际 MTU 由双方协商决定
                // 协商完成后会触发 onMtuChanged，再在那里发现服务
                try {
                    boolean mtuResult = gatt.requestMtu(255);
                    if (!mtuResult) {
                        // MTU 请求失败，直接进行服务发现（使用默认 MTU）
                        logRepository.addLog(LogEntry.system("[BLE] MTU 协商请求失败，将使用默认 MTU(23)"));
                        discoverServices(gatt);
                    }
                } catch (SecurityException e) {
                    handleSecurityException("requestMtu", e);
                    discoverServices(gatt);  // 权限失败也继续服务发现
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // ── 连接断开（主动断开 或 意外断开）────────────────
                cancelConnectTimeout();

                String reason = decodeGattStatus(status);
                logRepository.addLog(LogEntry.system(
                        "[BLE] 连接已断开，原因：" + reason + "（status=" + status + "）"
                ));

                // 清理 TX/RX 特征值引用（下次连接需要重新发现）
                txCharacteristic = null;
                rxCharacteristic = null;
                writeQueue.clear();
                writeInProgress = false;

                currentState.set(State.DISCONNECTED);
                callbackDispatcher.dispatchDisconnected(reason);

                // 在回调里安全关闭 GATT（必须在 onConnectionStateChange 里关闭，
                // 不能在 disconnect() 里立即关闭，否则会导致系统崩溃）
                forceCloseGatt();
            }
        }

        /**
         * MTU 协商完成回调
         * 协商完成后再发现服务，顺序是：connect → requestMtu → discoverServices
         *
         * @param gatt  GATT 客户端
         * @param mtu   最终协商到的 MTU 值
         * @param status 协商结果
         */
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logRepository.addLog(LogEntry.system(
                        "[BLE] MTU 协商成功：" + mtu + " 字节（有效负载 " + (mtu - 3) + " 字节）"
                ));
            } else {
                logRepository.addLog(LogEntry.system(
                        "[BLE] MTU 协商失败（status=" + status + "），将使用默认 MTU"
                ));
            }
            // 无论协商成功失败，都继续发现服务
            discoverServices(gatt);
        }

        /**
         * ══════════════════════════════════════════════════════════
         * 【核心】服务发现完成回调 — 混合自适应 UUID 策略在此执行！
         * ══════════════════════════════════════════════════════════
         *
         * @param gatt   GATT 客户端
         * @param status 服务发现结果（GATT_SUCCESS = 0 表示成功）
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                String msg = "[BLE] 服务发现失败（status=" + status + "），连接终止";
                logRepository.addLog(LogEntry.system(msg));
                callbackDispatcher.dispatchError("service_discovery_failed", msg);
                disconnect();
                return;
            }

            logRepository.addLog(LogEntry.system("[BLE] 服务发现完成，开始执行自适应 UUID 匹配策略..."));

            // ── 执行混合自适应 UUID 策略 ──────────────────────────
            adaptiveUuidMatch(gatt);
        }

        /**
         * 接收到 BLE 设备主动推送的数据（NOTIFY / INDICATE）
         * 这是 RX 的数据入口，单片机发回的数据都从这里进来。
         *
         * @param gatt           GATT 客户端
         * @param characteristic 触发通知的特征值（即 rxCharacteristic）
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                             BluetoothGattCharacteristic characteristic) {
            byte[] rawData = characteristic.getValue();
            if (rawData == null || rawData.length == 0) return;

            // 记录 RX 日志到调试控制台（两种格式都记录）
            logRepository.addLog(LogEntry.rx(rawData));

            // 分发给 JS 层
            callbackDispatcher.dispatchDataReceived(rawData);
        }

        /**
         * 写操作完成回调（WRITE 类型特征值写入后触发）
         * 必须在此回调中发送下一条队列数据，这是 BLE 串行写的关键！
         *
         * @param gatt           GATT 客户端
         * @param characteristic 完成写操作的特征值
         * @param status         写入结果（GATT_SUCCESS = 0 表示成功）
         */
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logRepository.addLog(LogEntry.system(
                        "[BLE] TX 写入失败（status=" + status + "），数据可能未发出"
                ));
            }
            // 标记当前写操作完成，处理队列中的下一条
            writeInProgress = false;
            processWriteQueue();
        }

        /**
         * 描述符写入完成回调（写 CCCD 开启 NOTIFY 后触发）
         *
         * @param gatt       GATT 客户端
         * @param descriptor 完成写操作的描述符
         * @param status     写入结果
         */
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                       BluetoothGattDescriptor descriptor,
                                       int status) {
            if (UUID_CCCD.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logRepository.addLog(LogEntry.system(
                            "[BLE] CCCD 写入成功，RX 通知已开启 ✓ — 设备现在可以主动推送数据了"
                    ));
                    // ── 所有准备工作完成，正式宣告连接就绪 ─────────
                    currentState.set(State.CONNECTED);
                    callbackDispatcher.dispatchConnected(targetMac, "BLE");
                } else {
                    String msg = "[BLE] CCCD 写入失败（status=" + status + "），无法接收设备数据";
                    logRepository.addLog(LogEntry.system(msg));
                    callbackDispatcher.dispatchError("cccd_write_failed", msg);
                }
            }
        }
    };

    // ════════════════════════════════════════════════════════════════
    //  【核心私有方法】混合自适应 UUID 匹配策略
    // ════════════════════════════════════════════════════════════════

    /**
     * 混合自适应 UUID 匹配策略的完整实现。
     *
     * <p>算法步骤：
     * <pre>
     * for (每个 Service) {
     *   打印 Service UUID 到日志
     *   for (每个 Characteristic) {
     *     如果有 WRITE 或 WRITE_NO_RESPONSE 属性 → 候选 TX
     *     如果有 NOTIFY 或 INDICATE 属性      → 候选 RX
     *   }
     * }
     * 从候选列表中选最优：
     *   TX 优先选同一 Service 内同时有 NOTIFY 的 Service 下的 WRITE（增加配对准确性）
     *   RX 优先选 NOTIFY（INDICATE 作为备选）
     * </pre>
     *
     * @param gatt 已完成服务发现的 GATT 客户端
     */
    private void adaptiveUuidMatch(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null || services.isEmpty()) {
            String msg = "[BLE] 未发现任何 GATT 服务，设备可能不兼容";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("no_services", msg);
            return;
        }

        logRepository.addLog(LogEntry.system(
                "[BLE] 发现 " + services.size() + " 个服务，开始遍历匹配..."
        ));

        // ── 候选变量（本轮遍历的最优结果） ───────────────────────
        BluetoothGattCharacteristic candidateTx = null;
        BluetoothGattCharacteristic candidateRx = null;
        boolean candidateRxIsIndicate = false;

        // ── 记录最优配对所在的 Service UUID（用于日志） ──────────
        UUID bestServiceUuid = null;

        // ── 遍历所有服务 ──────────────────────────────────────────
        for (BluetoothGattService service : services) {
            UUID serviceUuid = service.getUuid();

            // 跳过标准 GAP / GATT 服务（这两个是系统基础服务，不含业务数据）
            // 0x1800 = Generic Access，0x1801 = Generic Attribute
            String uuidStr = serviceUuid.toString().toLowerCase();
            if (uuidStr.startsWith("00001800") || uuidStr.startsWith("00001801")) {
                continue;
            }

            logRepository.addLog(LogEntry.system("[BLE] 检查服务：" + serviceUuid));

            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            if (characteristics == null || characteristics.isEmpty()) continue;

            // 本 Service 内的临时候选（优先同一 Service 下的 TX+RX 配对）
            BluetoothGattCharacteristic localTx = null;
            BluetoothGattCharacteristic localRx = null;
            boolean localRxIsIndicate = false;

            for (BluetoothGattCharacteristic ch : characteristics) {
                int props = ch.getProperties();
                String propsDesc = describeProperties(props);

                logRepository.addLog(LogEntry.system(
                        "  └─ Char: " + ch.getUuid() + "  属性：[" + propsDesc + "]"
                ));

                // ── 判断是否可作为 TX（发送通道）─────────────────
                // WRITE_NO_RESPONSE（0x04）：写无应答，速度快，推荐优先选择
                // WRITE（0x08）：写有应答，可靠但稍慢，作为备选
                boolean hasWriteNoResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                boolean hasWrite           = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;

                if (hasWriteNoResponse || hasWrite) {
                    if (localTx == null) {
                        localTx = ch;
                        // WRITE_NO_RESPONSE 优先级更高，如果当前候选不是，
                        // 但新的是，则替换
                    } else if (hasWriteNoResponse &&
                               (localTx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                        localTx = ch;  // 用更优的 WRITE_NO_RESPONSE 替换
                    }
                }

                // ── 判断是否可作为 RX（接收通道）─────────────────
                // NOTIFY（0x10）：不需要 ACK，速度快，优先选择
                // INDICATE（0x20）：需要客户端 ACK，更可靠，作为备选
                boolean hasNotify   = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean hasIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (hasNotify || hasIndicate) {
                    if (localRx == null) {
                        localRx = ch;
                        localRxIsIndicate = !hasNotify && hasIndicate;
                    } else if (hasNotify && localRxIsIndicate) {
                        // NOTIFY 优先级比 INDICATE 更高，替换
                        localRx = ch;
                        localRxIsIndicate = false;
                    }
                }
            }

            // ── 优先选取同一 Service 内同时拥有 TX 和 RX 的配对 ──
            // 这是最理想的情况（绝大多数 BLE 串口模块都是这种结构）
            if (localTx != null && localRx != null) {
                candidateTx       = localTx;
                candidateRx       = localRx;
                candidateRxIsIndicate = localRxIsIndicate;
                bestServiceUuid   = serviceUuid;
                // 找到完整配对立即停止遍历，减少不必要的日志噪音
                logRepository.addLog(LogEntry.system(
                        "[BLE] 在服务 " + serviceUuid + " 内找到完整 TX+RX 配对，停止遍历"
                ));
                break;
            }

            // ── 没有完整配对时，保存单独的候选作为兜底 ──────────
            if (localTx != null && candidateTx == null) {
                candidateTx     = localTx;
                bestServiceUuid = serviceUuid;
            }
            if (localRx != null && candidateRx == null) {
                candidateRx           = localRx;
                candidateRxIsIndicate = localRxIsIndicate;
                if (bestServiceUuid == null) bestServiceUuid = serviceUuid;
            }
        }

        // ════════════════════════════════════════════════════════
        // 打印最终匹配结果（PRD 强制要求！）
        // ════════════════════════════════════════════════════════
        logRepository.addLog(LogEntry.system("══════════════════════════════════════"));
        logRepository.addLog(LogEntry.system("[BLE] 自适应 UUID 匹配结果汇总："));

        if (bestServiceUuid != null) {
            logRepository.addLog(LogEntry.system("  ▶ Service UUID : " + bestServiceUuid));
        }

        if (candidateTx != null) {
            int txProps = candidateTx.getProperties();
            String txType = ((txProps & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    ? "WRITE_NO_RESPONSE（无应答写，速度更快）"
                    : "WRITE（有应答写，更可靠）";
            logRepository.addLog(LogEntry.system(
                    "  ▶ TX Char UUID : " + candidateTx.getUuid() + "  [" + txType + "]"
            ));
        } else {
            logRepository.addLog(LogEntry.system("  ▶ TX Char UUID : ❌ 未找到可写特征值！"));
        }

        if (candidateRx != null) {
            String rxType = candidateRxIsIndicate ? "INDICATE（有应答推送）" : "NOTIFY（无应答推送）";
            logRepository.addLog(LogEntry.system(
                    "  ▶ RX Char UUID : " + candidateRx.getUuid() + "  [" + rxType + "]"
            ));
        } else {
            logRepository.addLog(LogEntry.system("  ▶ RX Char UUID : ❌ 未找到可订阅特征值！"));
        }
        logRepository.addLog(LogEntry.system("══════════════════════════════════════"));

        // ── 校验匹配结果 ──────────────────────────────────────────
        if (candidateTx == null && candidateRx == null) {
            String msg = "[BLE] 匹配失败：未找到任何可用的 TX/RX 特征值，设备不兼容";
            logRepository.addLog(LogEntry.system(msg));
            callbackDispatcher.dispatchError("uuid_match_failed", msg);
            disconnect();
            return;
        }

        // ── 保存匹配结果到实例变量 ────────────────────────────────
        txCharacteristic  = candidateTx;
        rxCharacteristic  = candidateRx;
        rxIsIndicate      = candidateRxIsIndicate;

        // ── 开启 RX 特征值的 NOTIFY / INDICATE 订阅 ──────────────
        if (rxCharacteristic != null) {
            enableNotification(gatt, rxCharacteristic, rxIsIndicate);
        } else {
            // 没有 RX 通道（只能发不能收），也算连接成功（某些单向控制场景）
            logRepository.addLog(LogEntry.system("[BLE] 警告：无 RX 通道，只能发送数据，无法接收"));
            currentState.set(State.CONNECTED);
            callbackDispatcher.dispatchConnected(targetMac, "BLE");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  私有工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 发起服务发现。封装成方法是为了在 onConnectionStateChange 和
     * onMtuChanged 两处都能调用，避免重复代码。
     *
     * @param gatt GATT 客户端
     */
    @SuppressLint("MissingPermission")
    private void discoverServices(BluetoothGatt gatt) {
        try {
            boolean result = gatt.discoverServices();
            if (!result) {
                logRepository.addLog(LogEntry.system("[BLE] discoverServices() 返回 false，稍后重试..."));
                // 部分设备需要延迟后再发起服务发现
                mainHandler.postDelayed(() -> {
                    try { gatt.discoverServices(); } catch (SecurityException e) { /* 忽略 */ }
                }, 600);
            }
        } catch (SecurityException e) {
            handleSecurityException("discoverServices", e);
        }
    }

    /**
     * 开启 RX 特征值的通知订阅（NOTIFY 或 INDICATE）。
     * 操作分两步：
     *   1. setCharacteristicNotification：通知本地 GATT 栈开始回调
     *   2. 写 CCCD 描述符：通知远端设备开始推送数据
     * 两步缺一不可！
     *
     * @param gatt       GATT 客户端
     * @param rx         RX 特征值
     * @param isIndicate true = 开启 INDICATE，false = 开启 NOTIFY
     */
    @SuppressLint("MissingPermission")
    private void enableNotification(BluetoothGatt gatt,
                                     BluetoothGattCharacteristic rx,
                                     boolean isIndicate) {
        try {
            // 步骤 1：本地注册（告诉 Android 系统：这个特征值变化时请调用我的回调）
            gatt.setCharacteristicNotification(rx, true);

            // 步骤 2：写 CCCD 描述符（告诉远端设备：请开始推送数据给我）
            BluetoothGattDescriptor cccd = rx.getDescriptor(UUID_CCCD);
            if (cccd == null) {
                // 某些非标准设备没有 CCCD 描述符，但 setCharacteristicNotification 可能已生效
                logRepository.addLog(LogEntry.system(
                        "[BLE] 未找到 CCCD 描述符，尝试仅依赖本地注册（非标准设备）"
                ));
                currentState.set(State.CONNECTED);
                callbackDispatcher.dispatchConnected(targetMac, "BLE");
                return;
            }

            // 根据特征值类型写入对应的开启值
            byte[] enableValue = isIndicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE   // 开启 INDICATE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; // 开启 NOTIFY

            cccd.setValue(enableValue);
            boolean writeResult = gatt.writeDescriptor(cccd);

            logRepository.addLog(LogEntry.system(
                    "[BLE] 写入 CCCD 描述符（" + (isIndicate ? "INDICATE" : "NOTIFY") + "），result=" + writeResult
            ));

            // 写入结果在 onDescriptorWrite 回调中处理

        } catch (SecurityException e) {
            handleSecurityException("enableNotification", e);
        }
    }

    /**
     * 串行写队列处理器。
     * 保证 BLE 写操作不并发，每次只有一个 write 在飞行中，
     * 收到 onCharacteristicWrite 回调后再发下一条。
     */
    @SuppressLint("MissingPermission")
    private synchronized void processWriteQueue() {
        if (writeInProgress || writeQueue.isEmpty()) return;
        if (currentState.get() != State.CONNECTED) {
            writeQueue.clear();
            return;
        }
        if (bluetoothGatt == null || txCharacteristic == null) return;

        byte[] nextPacket = writeQueue.poll();
        if (nextPacket == null) return;

        writeInProgress = true;

        // 根据特征值属性决定写类型
        // WRITE_NO_RESPONSE 更快，不需要等对方 ACK
        int writeType = ((txCharacteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        txCharacteristic.setWriteType(writeType);
        txCharacteristic.setValue(nextPacket);

        try {
            boolean result = bluetoothGatt.writeCharacteristic(txCharacteristic);
            if (!result) {
                logRepository.addLog(LogEntry.system(
                        "[BLE] writeCharacteristic() 返回 false，可能 GATT 繁忙，已丢弃该包"
                ));
                writeInProgress = false;
                // 此包丢弃，继续尝试下一包
                processWriteQueue();
            }
        } catch (SecurityException e) {
            writeInProgress = false;
            handleSecurityException("writeCharacteristic", e);
        }
    }

    /**
     * 强制关闭 GATT 并释放系统蓝牙资源。
     * close() 必须且只能调用一次，调用后 bluetoothGatt 置 null。
     */
    @SuppressLint("MissingPermission")
    private void forceCloseGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "close() SecurityException: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "close() exception: " + e.getMessage());
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    /**
     * 启动连接超时定时器。
     * 若 CONNECT_TIMEOUT_MS 内未收到 STATE_CONNECTED 回调，强制断开。
     *
     * @param macAddress 用于超时日志显示
     */
    private void startConnectTimeout(String macAddress) {
        cancelConnectTimeout();
        connectTimeoutRunnable = () -> {
            if (currentState.get() == State.CONNECTING) {
                logRepository.addLog(LogEntry.system(
                        "[BLE] 连接超时（" + (CONNECT_TIMEOUT_MS / 1000) + "s），目标：" + macAddress
                ));
                callbackDispatcher.dispatchError("connect_timeout",
                        "BLE 连接超时，请检查设备是否在范围内且未被占用");
                disconnect();
            }
        };
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    /** 取消连接超时定时器 */
    private void cancelConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    /**
     * 检查 Android 12+ 的 BLUETOOTH_CONNECT 权限。
     * API 30 及以下不需要此权限，直接返回 true。
     *
     * @return true = 有权限，false = 无权限（已记录日志并触发 JS error）
     */
    private boolean checkBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31 = Android 12
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "[BLE] 缺少 BLUETOOTH_CONNECT 权限（Android 12+ 必须）！" +
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
     * 统一处理 SecurityException（Android 12+ 权限被拒时抛出）。
     * 记录日志 + 触发 JS error + 重置状态。
     *
     * @param operation 发生异常的操作名（用于日志定位）
     * @param e         捕获到的 SecurityException
     */
    private void handleSecurityException(String operation, SecurityException e) {
        String msg = "[BLE] SecurityException @ " + operation +
                "：缺少蓝牙权限，Android 12+ 需要 BLUETOOTH_CONNECT 权限。详情：" + e.getMessage();
        logRepository.addLog(LogEntry.system(msg));
        callbackDispatcher.dispatchError("security_exception", msg);
        currentState.set(State.DISCONNECTED);
        Log.e(TAG, msg, e);
    }

    /**
     * 将特征值属性位掩码转为可读字符串（用于日志显示）。
     *
     * @param properties 特征值属性位掩码
     * @return 逗号分隔的属性名列表，如 "WRITE_NO_RESPONSE, NOTIFY"
     */
    private static String describeProperties(int properties) {
        StringBuilder sb = new StringBuilder();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
            sb.append("READ, ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            sb.append("WRITE_NO_RESPONSE, ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            sb.append("WRITE, ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            sb.append("NOTIFY, ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            sb.append("INDICATE, ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0)
            sb.append("BROADCAST, ");
        // 删除末尾多余的 ", "
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        return sb.length() > 0 ? sb.toString() : "NONE";
    }

    /**
     * 将 GATT 状态码翻译为可读中文（常见错误码映射）。
     *
     * <p>完整列表参考 AOSP 源码 BluetoothGatt.java，这里列出最常见的。
     *
     * @param status GATT 状态码
     * @return 可读的中文描述
     */
    private static String decodeGattStatus(int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:          return "正常断开";
            case 0x08:                                 return "连接超时（设备超出范围）";
            case 0x13:                                 return "远端设备主动断开（0x13）";
            case 0x16:                                 return "本地主动断开（0x16）";
            case 0x22:                                 return "LMP 响应超时（0x22）";
            case 0x85:  /* 133 */                      return "GATT 连接失败，常见原因：设备被占用或缓存异常（133）";
            case 0x101: /* 257 */                      return "GATT 连接中断（257）";
            default:                                   return "未知原因（status=" + status + "）";
        }
    }
}
