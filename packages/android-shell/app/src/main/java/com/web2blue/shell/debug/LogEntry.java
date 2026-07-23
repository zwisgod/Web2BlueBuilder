// 文件路径：debug/LogEntry.java
package com.web2blue.shell.debug;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志条目数据模型 — 调试仓库里存储的每一条日志都是这个不可变对象。
 *
 * <p>设计为不可变（所有字段 public final），线程安全，可跨线程自由传递。
 *
 * <p>使用静态工厂方法代替多个构造函数，语义更清晰，避免参数顺序错误：
 * <pre>
 *   LogEntry.system("权限申请通过")
 *   LogEntry.tx(new byte[]{0x41, 0x54})
 *   LogEntry.rx(new byte[]{0x4F, 0x4B})
 * </pre>
 */
public class LogEntry {

    // ────────────────────────────────────────────────────────
    //  日志类型枚举
    // ────────────────────────────────────────────────────────

    /** 日志类型 */
    public enum Type {
        /** 系统日志：权限、连接状态、UUID 发现结果、错误等 */
        SYSTEM,
        /** 发送日志：App 发向单片机的原始数据 */
        TX,
        /** 接收日志：单片机回传给 App 的原始数据 */
        RX
    }

    // ────────────────────────────────────────────────────────
    //  不可变字段（所有访问均线程安全）
    // ────────────────────────────────────────────────────────

    /** 日志类型 */
    public final Type type;

    /**
     * 时间戳字符串，格式 "HH:mm:ss.SSS"（含毫秒）。
     * 在对象创建时就计算固定，不在显示时动态格式化，保证时序准确。
     */
    public final String timestamp;

    /**
     * 字符串形式的内容。
     * - SYSTEM 类型：直接是事件描述文字
     * - TX/RX 类型：原始字节按 UTF-8 尝试解码，不可打印字符用 '.' 代替
     */
    public final String strContent;

    /**
     * HEX 十六进制形式的内容（仅 TX/RX 类型有意义，SYSTEM 类型为 null）。
     * 格式：每字节两个大写十六进制字符，字节间空格分隔。
     * 例如：{0x4F, 0x4B, 0x0D, 0x0A} → "4F 4B 0D 0A"
     */
    public final String hexContent;

    // ────────────────────────────────────────────────────────
    //  私有构造函数（强制使用静态工厂方法）
    // ────────────────────────────────────────────────────────

    private LogEntry(Type type, String strContent, String hexContent) {
        this.type       = type;
        this.strContent = strContent;
        this.hexContent = hexContent;
        this.timestamp  = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                                .format(new Date());
    }

    // ────────────────────────────────────────────────────────
    //  静态工厂方法（推荐的创建方式）
    // ────────────────────────────────────────────────────────

    /**
     * 创建系统类型日志（无 HEX 视图，仅文字描述）。
     *
     * <p>用于：权限申请结果、蓝牙适配器状态、连接/断开事件、UUID 匹配结果、错误信息等。
     *
     * @param message 事件描述文字（中文）
     * @return 新的 LogEntry 实例
     */
    public static LogEntry system(String message) {
        return new LogEntry(Type.SYSTEM, message == null ? "" : message, null);
    }

    /**
     * 创建 TX 发送日志（App → 单片机方向的数据）。
     *
     * @param rawBytes 实际发送到蓝牙设备的原始字节数组
     * @return 新的 LogEntry 实例
     */
    public static LogEntry tx(byte[] rawBytes) {
        return new LogEntry(
                Type.TX,
                bytesToReadableString(rawBytes),
                bytesToHexSpaced(rawBytes)
        );
    }

    /**
     * 创建 RX 接收日志（单片机 → App 方向的数据）。
     *
     * @param rawBytes 从蓝牙设备实际接收的原始字节数组
     * @return 新的 LogEntry 实例
     */
    public static LogEntry rx(byte[] rawBytes) {
        return new LogEntry(
                Type.RX,
                bytesToReadableString(rawBytes),
                bytesToHexSpaced(rawBytes)
        );
    }

    // ────────────────────────────────────────────────────────
    //  私有工具方法
    // ────────────────────────────────────────────────────────

    /**
     * 将字节数组转为可读字符串。
     * 可打印 ASCII（0x20~0x7E）直接显示，其余用 '.' 代替，
     * 避免终端控制字符破坏调试窗布局。
     *
     * @param bytes 原始字节数组
     * @return 可读字符串
     */
    private static String bytesToReadableString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((b >= 0x20 && b <= 0x7E) ? (char) b : '.');
        }
        return sb.toString();
    }

    /**
     * 将字节数组转为带空格分隔的大写十六进制字符串。
     * 例如：{0x41, 0x54, 0x0D, 0x0A} → "41 54 0D 0A"
     *
     * @param bytes 原始字节数组
     * @return 大写 HEX 字符串
     */
    private static String bytesToHexSpaced(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
