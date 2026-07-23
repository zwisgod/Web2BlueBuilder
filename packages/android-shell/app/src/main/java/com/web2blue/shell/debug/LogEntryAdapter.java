// 文件路径：debug/LogEntryAdapter.java
package com.web2blue.shell.debug;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * 调试日志列表适配器 — 将 LogEntry 数据渲染为带颜色高亮的列表行。
 *
 * <p>颜色方案（深色背景下的高对比度配色）：
 * <pre>
 *   [SYS] ─ 浅灰色  #CCCCCC  系统事件（权限、连接状态、UUID 结果）
 *   [TX]  ─ 亮绿色  #66FF66  发出的数据（App → 单片机）
 *   [RX]  ─ 金黄色  #FFDD44  收到的数据（单片机 → App）
 * </pre>
 *
 * <p>性能优化：
 * 使用经典 ViewHolder 模式缓存 TextView 引用，
 * 避免 ListView 每次滚动时重复调用 findViewById()（O(n) → O(1)）。
 *
 * <p>HEX / STR 切换：
 * 通过 setHexMode() 切换显示模式，调用方需在切换后调用 notifyDataSetChanged()。
 */
public class LogEntryAdapter extends BaseAdapter {

    // ────────────────────────────────────────────────────────────────
    //  颜色常量（与 DebugConsoleView 保持一致）
    // ────────────────────────────────────────────────────────────────
    private static final int COLOR_SYS = Color.parseColor("#CCCCCC"); // 浅灰：系统日志
    private static final int COLOR_TX  = Color.parseColor("#66FF66"); // 亮绿：发送数据
    private static final int COLOR_RX  = Color.parseColor("#FFDD44"); // 金黄：接收数据

    // ────────────────────────────────────────────────────────────────
    //  字体大小
    // ────────────────────────────────────────────────────────────────
    /** 日志文字大小（sp），调试信息密集展示，尽量小 */
    private static final float TEXT_SIZE_SP = 11f;

    // ────────────────────────────────────────────────────────────────
    //  数据与状态
    // ────────────────────────────────────────────────────────────────
    private final Context context;

    /** 日志数据集（持有外部引用，外部修改后调用 notifyDataSetChanged 即可刷新）*/
    private final List<LogEntry> entries;

    /** HEX 模式标志：true = 显示 HEX，false = 显示 STR（默认）*/
    private boolean hexMode = false;

    /**
     * @param context  Context（用于 inflate View）
     * @param entries  日志数据集（外部持有，适配器不拷贝）
     */
    public LogEntryAdapter(Context context, List<LogEntry> entries) {
        this.context = context;
        this.entries = entries;
    }

    // ════════════════════════════════════════════════════════════════
    //  BaseAdapter 必须实现的方法
    // ════════════════════════════════════════════════════════════════

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public LogEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;  // 使用位置作为 ID（日志条目本身无唯一 ID）
    }

    /**
     * 核心渲染方法：为每个列表项创建或复用 View，填充数据并着色。
     *
     * <p>ViewHolder 模式说明：
     * ─ convertView 为 null 时：inflate 新 View，创建 ViewHolder，用 setTag 缓存
     * ─ convertView 不为 null 时：直接 getTag 取出 ViewHolder，跳过 inflate 和 findView
     * 这使得列表滚动时 getView() 几乎没有额外开销。
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            // ── 首次创建：构建 TextView ─────────────────────────────
            TextView tv = new TextView(context);

            // 等宽字体：HEX 数据需要等宽对齐才整洁
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TEXT_SIZE_SP);
            tv.setBackgroundColor(Color.TRANSPARENT);
            // 水平内边距（左右各 8dp，方便阅读）
            int padH = dpToPx(8);
            int padV = dpToPx(3);
            tv.setPadding(padH, padV, padH, padV);
            // 允许长文本换行（HEX 数据可能很长）
            tv.setSingleLine(false);
            tv.setMaxLines(4);  // 单条日志最多显示 4 行，防止单条撑开整个列表

            holder         = new ViewHolder();
            holder.textView = tv;
            tv.setTag(holder);

            convertView = tv;
        } else {
            // ── 复用：从 tag 取出缓存的 ViewHolder ─────────────────
            holder = (ViewHolder) convertView.getTag();
        }

        // ── 填充数据 ────────────────────────────────────────────────
        LogEntry entry = entries.get(position);
        holder.textView.setText(formatEntry(entry));
        holder.textView.setTextColor(getColor(entry.type));

        return convertView;
    }

    // ════════════════════════════════════════════════════════════════
    //  公开方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 切换 HEX / STR 显示模式。
     * 调用后须执行 notifyDataSetChanged() 刷新列表。
     *
     * @param hexMode true = HEX 格式，false = STR 格式
     */
    public void setHexMode(boolean hexMode) {
        this.hexMode = hexMode;
    }

    /**
     * 获取当前显示模式。
     *
     * @return true = HEX 模式，false = STR 模式
     */
    public boolean isHexMode() {
        return hexMode;
    }

    // ════════════════════════════════════════════════════════════════
    //  私有工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 LogEntry 格式化为单行文本字符串。
     *
     * <p>输出格式：
     * <pre>
     *   [SYS] 14:23:01.456  连接成功
     *   [TX]  14:23:02.100  41 54 2B 4C 45 44 3D 31  ← HEX 模式
     *   [TX]  14:23:02.100  AT+LED=1                  ← STR 模式
     *   [RX]  14:23:02.155  4F 4B 0D 0A
     * </pre>
     *
     * @param entry 日志条目
     * @return 格式化后的显示文本
     */
    private String formatEntry(LogEntry entry) {
        // 类型标签（固定 5 字符宽度，对齐列表）
        String tag;
        switch (entry.type) {
            case TX:     tag = "[TX] "; break;
            case RX:     tag = "[RX] "; break;
            default:     tag = "[SYS]"; break;
        }

        // 内容部分（STR 或 HEX）
        String content;
        if (entry.type == LogEntry.Type.SYSTEM) {
            // 系统日志只有 STR 内容，忽略 hexMode
            content = entry.strContent;
        } else {
            // TX / RX 根据当前模式选择显示格式
            if (hexMode) {
                content = (entry.hexContent != null && !entry.hexContent.isEmpty())
                        ? entry.hexContent
                        : "(empty)";
            } else {
                content = (entry.strContent != null && !entry.strContent.isEmpty())
                        ? entry.strContent
                        : "(empty)";
            }
        }

        // 拼装：[TAG] HH:mm:ss.SSS  内容
        return String.format("%-5s %s  %s", tag, entry.timestamp, content);
    }

    /**
     * 根据日志类型返回对应的显示颜色。
     *
     * @param type 日志类型
     * @return ARGB 颜色值
     */
    private static int getColor(LogEntry.Type type) {
        switch (type) {
            case TX:     return COLOR_TX;
            case RX:     return COLOR_RX;
            default:     return COLOR_SYS;
        }
    }

    /**
     * dp → px 单位转换。
     *
     * @param dp 设计稿中的 dp 值
     * @return 对应的像素值（整数）
     */
    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    // ════════════════════════════════════════════════════════════════
    //  ViewHolder — 缓存 ItemView 中的子 View 引用
    // ════════════════════════════════════════════════════════════════

    /**
     * ViewHolder 静态内部类。
     *
     * <p>每个列表行只有一个 TextView（日志文本），所以 ViewHolder 只持有它。
     * 若未来需要添加图标、时间戳分离显示等，在此扩展即可。
     */
    private static class ViewHolder {
        TextView textView;
    }
}
