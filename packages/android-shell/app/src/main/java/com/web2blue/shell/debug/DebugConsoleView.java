// 文件路径：debug/DebugConsoleView.java
package com.web2blue.shell.debug;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.web2blue.shell.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 硬件调试悬浮控制台 — PRD 最高优先级功能！
 *
 * <p>这是一个完全独立于 HTML WebView 的 Android 原生悬浮窗。
 * 使用 WindowManager 将 View 叠加在所有内容之上，
 * 即使 WebView 自身崩溃，调试窗依然可见。
 *
 * <p>显示格式（含颜色区分）：
 * <pre>
 *   [SYS] 00:01:23.456  权限申请通过                  ← 白色
 *   [TX]  00:01:24.001  41 54 2B 4C 45 44 3D 31     ← 绿色
 *   [RX]  00:01:24.088  4F 4B 0D 0A                  ← 黄色
 * </pre>
 *
 * <p>触发方式：
 * 1. JS 调用 BlueApp.showDebugConsole()
 * 2. 连续点击屏幕顶部边缘 5 次（手势检测逻辑在 WebViewActivity 中实现）
 *
 * <p>生命周期警告：
 * 必须在 Activity.onDestroy() 中调用 destroy()，
 * 否则 WindowManager 持有 View 引用将导致 Activity 内存泄漏！
 *
 * <p>show() / hide() / destroy() 必须在主线程调用。
 */
public class DebugConsoleView implements LogRepository.OnLogAddedListener {

    // ────────────────────────────────────────────────────────
    //  颜色常量（调试窗深色背景下的对比色）
    // ────────────────────────────────────────────────────────
    private static final int COLOR_SYS = Color.parseColor("#CCCCCC"); // 浅灰：系统日志
    private static final int COLOR_TX  = Color.parseColor("#66FF66"); // 亮绿：发送数据
    private static final int COLOR_RX  = Color.parseColor("#FFDD44"); // 金黄：接收数据
    private static final int COLOR_BG  = Color.parseColor("#CC000000"); // 80% 透明黑：背景

    // ────────────────────────────────────────────────────────
    //  依赖
    // ────────────────────────────────────────────────────────
    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LogRepository logRepository;

    // ────────────────────────────────────────────────────────
    //  View 相关
    // ────────────────────────────────────────────────────────
    /** 悬浮窗根 View */
    private View rootView;

    /** 日志列表 View */
    private ListView logListView;

    /** 当前展示的日志文本列表（已格式化为带颜色的 SpannableString） */
    private final List<SpannableString> displayLines = new ArrayList<>();

    /** ListView 的适配器 */
    private ArrayAdapter<SpannableString> adapter;

    // ────────────────────────────────────────────────────────
    //  状态
    // ────────────────────────────────────────────────────────
    /** HEX / STR 显示模式切换，false = STR（默认），true = HEX */
    private boolean showHexMode = false;

    /** 悬浮窗当前是否可见 */
    private boolean isVisible = false;

    // ════════════════════════════════════════════════════════════════
    //  构造与生命周期
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造函数，在 WebViewActivity 中创建，整个 Activity 生命周期内复用。
     *
     * @param context Activity 的 Context（不能用 ApplicationContext，TYPE_APPLICATION 需要 Activity）
     */
    public DebugConsoleView(Context context) {
        this.context       = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.logRepository = LogRepository.getInstance();
    }

    /**
     * 显示悬浮控制台。
     *
     * <p>首次调用时 inflate 布局并添加到 WindowManager（耗时约 5~10ms）。
     * 后续调用仅切换 View 可见性，极低开销。
     * 必须在主线程调用。
     */
    public void show() {
        if (isVisible) return;

        if (rootView == null) {
            initView();  // 首次调用：初始化整个 View 层级
        }

        // 填充历史日志快照（最近 100 条，避免初始化太慢）
        refreshFromRepository();

        // 注册实时日志监听器
        logRepository.addListener(this);

        rootView.setVisibility(View.VISIBLE);
        isVisible = true;
    }

    /**
     * 隐藏悬浮控制台（不销毁，下次 show() 可快速恢复）。
     * 必须在主线程调用。
     */
    public void hide() {
        if (!isVisible || rootView == null) return;

        // 不可见时注销监听器，停止无用的 UI 刷新
        logRepository.removeListener(this);

        rootView.setVisibility(View.GONE);
        isVisible = false;
    }

    /**
     * Returns whether the debug console is currently visible.
     *
     * @return true if the console is shown, false if hidden or not yet created
     */
    public boolean isVisible() {
        return isVisible;
    }

    /**
     * 完全销毁悬浮窗并释放 WindowManager 资源。
     * 必须在 Activity.onDestroy() 中调用，否则内存泄漏！
     */
    public void destroy() {
        logRepository.removeListener(this);
        if (rootView != null && rootView.isAttachedToWindow()) {
            windowManager.removeView(rootView);
        }
        rootView  = null;
        isVisible = false;
    }

    // ════════════════════════════════════════════════════════════════
    //  LogRepository.OnLogAddedListener 实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 新日志到达时被 LogRepository 回调（可能在子线程！）。
     * 切回主线程后追加到列表并自动滚动到底部。
     *
     * @param entry 新产生的日志条目
     */
    @Override
    public void onLogAdded(LogEntry entry) {
        mainHandler.post(() -> {
            if (!isVisible || rootView == null) return;
            displayLines.add(formatEntry(entry, showHexMode));
            adapter.notifyDataSetChanged();
            // 自动滚动到最新一条，让开发者无需手动翻页
            logListView.setSelection(displayLines.size() - 1);
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：初始化
    // ════════════════════════════════════════════════════════════════

    /**
     * 初始化悬浮窗 View 和 WindowManager 参数（仅首次 show() 时调用）。
     */
    private void initView() {
        rootView = LayoutInflater.from(context).inflate(R.layout.debug_console, null);
        rootView.setBackgroundColor(COLOR_BG);

        logListView = rootView.findViewById(R.id.lv_debug_log);
        adapter     = new ArrayAdapter<SpannableString>(context, android.R.layout.simple_list_item_1, displayLines) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                // 使用系统内置的 simple_list_item_1 布局，文字大小和颜色已在 SpannableString 里设定
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setTextSize(11f);    // 11sp，调试信息尽量密集显示
                tv.setTypeface(android.graphics.Typeface.MONOSPACE); // 等宽字体，HEX 对齐更整洁
                tv.setText(getItem(position));
                return tv;
            }
        };
        logListView.setAdapter(adapter);

        // ── HEX / STR 切换按钮 ──────────────────────────────────
        TextView btnToggle = rootView.findViewById(R.id.btn_toggle_mode);
        btnToggle.setOnClickListener(v -> {
            showHexMode = !showHexMode;
            btnToggle.setText(showHexMode ? "模式：HEX" : "模式：STR");
            // 切换模式需要重新格式化所有日志
            refreshFromRepository();
        });

        // ── 清除按钮 ────────────────────────────────────────────
        rootView.findViewById(R.id.btn_clear).setOnClickListener(v -> {
            logRepository.clear();
            displayLines.clear();
            adapter.notifyDataSetChanged();
        });

        // ── 关闭按钮 ────────────────────────────────────────────
        rootView.findViewById(R.id.btn_close).setOnClickListener(v -> hide());

        // ── WindowManager 布局参数 ──────────────────────────────
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.45f),
                WindowManager.LayoutParams.TYPE_APPLICATION,
                // FLAG_NOT_FOCUSABLE：悬浮窗不抢焦点，WebView 的点击事件不受影响
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM;  // 固定在屏幕底部，不遮挡主要操作区域

        windowManager.addView(rootView, params);
    }

    // ════════════════════════════════════════════════════════════════
    //  私有：日志格式化
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 LogRepository 重新加载并格式化所有日志（用于初次显示和切换模式）。
     */
    private void refreshFromRepository() {
        List<LogEntry> all = logRepository.getLastLogs(200); // 最多显示最近 200 条
        displayLines.clear();
        for (LogEntry e : all) {
            displayLines.add(formatEntry(e, showHexMode));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            if (!displayLines.isEmpty()) {
                logListView.setSelection(displayLines.size() - 1);
            }
        }
    }

    /**
     * 将 LogEntry 格式化为带颜色高亮的 SpannableString。
     *
     * <p>输出格式示例：
     * <pre>
     *   [SYS] 00:01:23.456  权限申请通过
     *   [TX]  00:01:24.001  41 54 2B ...    ← HEX 模式
     *   [TX]  00:01:24.001  AT+LED=1        ← STR 模式
     *   [RX]  00:01:24.088  4F 4B 0D 0A
     * </pre>
     *
     * @param entry   日志条目
     * @param hexMode true 时 TX/RX 显示 HEX 格式，false 时显示 STR 格式
     * @return 带颜色高亮的 SpannableString
     */
    private static SpannableString formatEntry(LogEntry entry, boolean hexMode) {
        // 确定前缀标签
        String tag;
        int color;
        switch (entry.type) {
            case TX:
                tag   = "[TX] ";
                color = COLOR_TX;
                break;
            case RX:
                tag   = "[RX] ";
                color = COLOR_RX;
                break;
            default:
                tag   = "[SYS]";
                color = COLOR_SYS;
                break;
        }

        // 确定数据内容（STR 或 HEX）
        String content;
        if (entry.type == LogEntry.Type.SYSTEM) {
            content = entry.strContent;
        } else {
            // TX/RX 根据模式选择显示格式
            content = hexMode
                    ? (entry.hexContent != null ? entry.hexContent : "")
                    : entry.strContent;
        }

        // 拼装完整行文字：  [TAG] HH:mm:ss.SSS  内容
        String fullText = String.format("%-5s %s  %s", tag, entry.timestamp, content);

        SpannableString spannable = new SpannableString(fullText);
        // 整行着色（标签 + 时间戳 + 内容 统一颜色，视觉上清晰分层）
        spannable.setSpan(
                new ForegroundColorSpan(color),
                0, fullText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        return spannable;
    }
}
