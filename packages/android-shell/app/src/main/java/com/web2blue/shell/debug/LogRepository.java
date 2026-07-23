// 文件路径：debug/LogRepository.java
package com.web2blue.shell.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 日志仓库 — 整个 App 的日志"数据库"，全局单例。
 *
 * <p>所有模块（桥接层、蓝牙层、Activity）都通过 getInstance() 拿到同一个实例，
 * 调用 addLog() 写入日志。DebugConsoleView 订阅变更通知，实时刷新 UI。
 *
 * <p>线程安全设计：
 * 蓝牙数据在子线程（Binder 线程 / IO 线程）产生，UI 在主线程消费。
 * 内部使用 CopyOnWriteArrayList 保证多线程写入的安全，
 * 写操作复制一份新数组，读操作（getAllLogs）直接访问当前快照，互不阻塞。
 *
 * <p>内存保护：
 * 日志条目上限 MAX_LOG_SIZE，超出后自动 FIFO 淘汰最旧的条目，
 * 防止长时间运行内存溢出（特别是高频 BLE 数据场景）。
 */
public class LogRepository {

    // ────────────────────────────────────────────────────────
    //  单例
    // ────────────────────────────────────────────────────────

    /** 单例实例，volatile 保证多线程可见性 */
    private static volatile LogRepository instance;

    /** 私有构造，禁止外部 new */
    private LogRepository() {}

    /**
     * 获取单例实例（双重检查锁定，线程安全，延迟初始化）。
     *
     * @return LogRepository 唯一实例
     */
    public static LogRepository getInstance() {
        if (instance == null) {
            synchronized (LogRepository.class) {
                if (instance == null) {
                    instance = new LogRepository();
                }
            }
        }
        return instance;
    }

    // ────────────────────────────────────────────────────────
    //  配置常量
    // ────────────────────────────────────────────────────────

    /**
     * 最大日志条数。
     * 按每条日志约 200 字节估算，500 条约占 100KB，安全可控。
     * 如需更长历史，可适当调大，但注意列表滚动性能。
     */
    private static final int MAX_LOG_SIZE = 500;

    // ────────────────────────────────────────────────────────
    //  数据存储
    // ────────────────────────────────────────────────────────

    /**
     * 日志存储列表。
     * CopyOnWriteArrayList 特性：
     * - 写操作（add/remove）：复制整个数组后操作，写入后替换引用
     * - 读操作（迭代/get）：直接访问当前快照，不阻塞，不抛 ConcurrentModificationException
     * 适合"频繁读、偶尔写"的调试日志场景。
     */
    private final CopyOnWriteArrayList<LogEntry> logs = new CopyOnWriteArrayList<>();

    /**
     * 观察者列表：订阅日志变更的监听器集合。
     * DebugConsoleView 在 show() 时注册，hide() 时注销。
     * 同样用 CopyOnWriteArrayList，防止在回调过程中并发修改。
     */
    private final CopyOnWriteArrayList<OnLogAddedListener> listeners =
            new CopyOnWriteArrayList<>();

    // ────────────────────────────────────────────────────────
    //  核心操作方法
    // ────────────────────────────────────────────────────────

    /**
     * 添加一条日志（所有模块的日志写入唯一入口）。
     *
     * <p>此方法可在任意线程调用，内部保证线程安全。
     * 写入完成后立即通知所有监听器（监听器须自行切回主线程操作 UI）。
     *
     * @param entry 日志条目，通过 LogEntry.system()/tx()/rx() 工厂方法创建
     */
    public void addLog(LogEntry entry) {
        if (entry == null) return;

        // 超出上限时，按 FIFO 淘汰最旧的一条
        // CopyOnWriteArrayList.remove(0) 每次都会复制数组，
        // 若极高频场景出现性能问题，可改为 LinkedBlockingDeque 限容队列
        while (logs.size() >= MAX_LOG_SIZE) {
            if (!logs.isEmpty()) logs.remove(0);
        }

        logs.add(entry);

        // 通知所有订阅者（DebugConsoleView 会在这里刷新列表）
        for (OnLogAddedListener listener : listeners) {
            listener.onLogAdded(entry);
        }
    }

    /**
     * 获取所有日志的不可修改快照（供 DebugConsoleView 初次填充历史记录使用）。
     *
     * <p>返回的是当前列表的副本的不可修改视图，调用方修改不影响仓库内部数据。
     *
     * @return 日志列表的不可修改视图（按时间顺序，旧→新）
     */
    public List<LogEntry> getAllLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    /**
     * 获取最后 N 条日志（用于悬浮窗初始化时不需要加载太多历史）。
     *
     * @param count 要获取的最新条数，若总条数不足则返回全部
     * @return 最新 N 条日志列表（时间顺序）
     */
    public List<LogEntry> getLastLogs(int count) {
        List<LogEntry> all = new ArrayList<>(logs);
        int start = Math.max(0, all.size() - count);
        return Collections.unmodifiableList(all.subList(start, all.size()));
    }

    /**
     * 清空所有日志。
     * DebugConsoleView 上的"清除"按钮触发此方法。
     * 清除后自动写入一条分隔标记，方便时序参考。
     */
    public void clear() {
        logs.clear();
        // 直接 add 不走 addLog，避免触发无意义的监听回调
        logs.add(LogEntry.system("─────────── 日志已清除 ───────────"));
        // 重新通知一次，让 UI 刷新空列表 + 分隔符
        LogEntry marker = logs.get(0);
        for (OnLogAddedListener listener : listeners) {
            listener.onLogAdded(marker);
        }
    }

    /**
     * 获取当前日志总条数。
     *
     * @return 日志条数
     */
    public int size() {
        return logs.size();
    }

    // ────────────────────────────────────────────────────────
    //  观察者管理
    // ────────────────────────────────────────────────────────

    /**
     * 注册日志变更监听器（DebugConsoleView 在 show() 时调用）。
     *
     * @param listener 监听器实例，重复注册会被忽略
     */
    public void addListener(OnLogAddedListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 注销监听器（DebugConsoleView 在 hide()/destroy() 时调用，节省性能）。
     *
     * @param listener 要注销的监听器
     */
    public void removeListener(OnLogAddedListener listener) {
        listeners.remove(listener);
    }

    // ────────────────────────────────────────────────────────
    //  监听器接口定义
    // ────────────────────────────────────────────────────────

    /**
     * 日志变更监听器接口。
     * DebugConsoleView 实现此接口以接收实时日志推送。
     */
    public interface OnLogAddedListener {

        /**
         * 当有新日志加入时被回调。
         *
         * <p>重要：此回调在产生日志的线程中执行（不一定是主线程！），
         * 实现者需自行通过 Handler.post() 切回主线程再操作 UI。
         *
         * @param entry 新加入的日志条目
         */
        void onLogAdded(LogEntry entry);
    }
}
