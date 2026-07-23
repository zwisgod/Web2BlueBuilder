// 文件路径：bluetooth/IBluetoothConnector.java
package com.web2blue.shell.bluetooth;

/**
 * 蓝牙连接器统一接口 — SPP 与 BLE 双协议的"共同语言"契约。
 *
 * <p>这是整个蓝牙模块的核心抽象。
 * SppConnector 和 BleConnector 都实现此接口，
 * BluetoothRouter 持有的是这个接口类型，而非具体实现。
 *
 * <p>架构优势（SOLID 原则）：
 * - 依赖倒置（D）：BluetoothRouter 依赖抽象，不依赖具体实现
 * - 里氏替换（L）：SPP / BLE 实现可无缝替换，Router 代码零改动
 * - 开放封闭（O）：新增协议只需新增实现类，不修改已有代码
 * - 可测试性：单元测试可用 Mock 实现代替，无需真实蓝牙硬件
 *
 * <p>线程模型约定（所有实现类必须遵守）：
 * - connect()：内部在后台线程执行（连接是阻塞操作，禁止占用主线程）
 * - send()：内部在后台线程执行（IO 写操作，禁止阻塞主线程）
 * - 回调事件（onConnected/onData/onError）：通过 CallbackDispatcher 分发，不直接操作 UI
 * - getState()：可在任意线程调用，必须线程安全（AtomicReference / volatile）
 */
public interface IBluetoothConnector {

    // ────────────────────────────────────────────────────────
    //  连接状态枚举（所有实现类共用此状态机）
    // ────────────────────────────────────────────────────────

    /**
     * 连接状态枚举，描述连接器当前所处的生命周期阶段。
     */
    enum State {
        /** 初始状态 / 已完全断开，可以发起新连接 */
        DISCONNECTED,
        /** 正在连接中（后台线程执行中），此时不应重复调用 connect() */
        CONNECTING,
        /** 已成功建立连接，可以收发数据 */
        CONNECTED
    }

    // ────────────────────────────────────────────────────────
    //  接口方法定义
    // ────────────────────────────────────────────────────────

    /**
     * 发起连接（异步，内部自行开启后台线程）。
     *
     * <p>实现要求：
     * 1. 内部自行开启后台线程执行阻塞连接操作
     * 2. 连接成功 → 通过 CallbackDispatcher.dispatchConnected() 通知 JS
     * 3. 连接失败 → 通过 CallbackDispatcher.dispatchError() 通知 JS
     * 4. 状态流转：DISCONNECTED → CONNECTING → CONNECTED（或 DISCONNECTED）
     * 5. 实现连接超时机制，避免永久挂起在 CONNECTING 状态
     *
     * @param macAddress 目标蓝牙设备 MAC 地址，格式 "XX:XX:XX:XX:XX:XX"
     */
    void connect(String macAddress);

    /**
     * 断开连接并释放所有协议相关资源。
     *
     * <p>实现要求：
     * 1. 关闭底层 Socket / Gatt 连接
     * 2. 关闭所有 IO 流（InputStream / OutputStream / BluetoothGatt）
     * 3. 停止数据接收的后台线程（设置退出标志或中断线程）
     * 4. 状态变更为 DISCONNECTED
     * 5. 通过 CallbackDispatcher.dispatchDisconnected() 通知 JS
     * 6. 此方法必须是幂等的：多次调用不报错，重复调用仅执行一次
     */
    void disconnect();

    /**
     * 向已连接的设备发送数据（可能异步执行）。
     *
     * <p>实现要求：
     * 1. 调用前检查状态，非 CONNECTED 时静默返回并记录日志
     * 2. 发送前通过 LogRepository 记录 TX 日志（调试窗可见）
     * 3. BLE 实现需使用串行写队列，防止并发写入导致丢包
     * 4. SPP 实现直接写 OutputStream（已是阻塞串行）
     *
     * @param data 待发送的原始字节数组（由 BluetoothRouter 完成 String/HEX → byte[] 转换）
     */
    void send(byte[] data);

    /**
     * 查询当前连接状态（线程安全，可在任意线程调用）。
     *
     * @return 当前连接状态枚举值
     */
    State getState();

    /**
     * 释放所有系统级资源（在 Activity.onDestroy() 中调用）。
     *
     * <p>比 disconnect() 更彻底：
     * - 除断开连接外，还应注销 BroadcastReceiver、取消扫描、释放 GattCallback 引用等
     * - 调用后此连接器实例不应再被使用
     */
    void release();
}
