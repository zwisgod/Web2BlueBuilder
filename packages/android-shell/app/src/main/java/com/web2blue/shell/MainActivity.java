// 文件路径：java/com/web2blue/shell/MainActivity.java
package com.web2blue.shell;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — 启动 Activity（过渡页 + 运行时权限申请）。
 *
 * <p>职责：
 * 1. 展示品牌 Logo 和启动进度条（1.5 秒过渡动画）
 * 2. 集中申请所有蓝牙运行时权限，处理完整的权限状态机：
 *    ─ 首次请求  → 直接弹系统权限对话框
 *    ─ 曾经拒绝  → 先显示解释弹窗，再弹系统对话框
 *    ─ "不再询问" → 引导用户前往系统设置手动开启
 * 3. 权限流程完成后跳转到 WebViewActivity
 *
 * <p>权限矩阵：
 * ┌──────────────────────────┬───────────────────────────────────┐
 * │ 权限                      │ 适用版本                          │
 * ├──────────────────────────┼───────────────────────────────────┤
 * │ BLUETOOTH_CONNECT         │ API >= 31（Android 12+）          │
 * │ BLUETOOTH_SCAN            │ API >= 31（Android 12+）          │
 * │ ACCESS_FINE_LOCATION      │ API 23~30（BLE 扫描强制要求）      │
 * └──────────────────────────┴───────────────────────────────────┘
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    /** 最短展示时间（毫秒），避免权限立即通过时出现闪屏 */
    private static final long MIN_SPLASH_DURATION_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long splashStartTime;

    /** 是否已完成权限流程（授予、拒绝或引导设置均视为完成） */
    private boolean permissionDone = false;

    /** 是否已过最短展示时间 */
    private boolean splashTimeDone = false;

    // ════════════════════════════════════════════════════════════════
    //  Activity 生命周期
    // ════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式（隐藏状态栏，保持品牌页干净）
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_main);

        splashStartTime = System.currentTimeMillis();

        // 从 strings.xml 读取应用名，动态填充到 Splash 页
        TextView tvAppName = findViewById(R.id.tv_app_name);
        if (tvAppName != null) {
            tvAppName.setText(getString(R.string.app_name));
        }

        // 启动最短展示时间计时器
        mainHandler.postDelayed(() -> {
            splashTimeDone = true;
            tryNavigateToMain();
        }, MIN_SPLASH_DURATION_MS);

        // 发起权限申请（包含完整三段式状态机）
        requestBluetoothPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ════════════════════════════════════════════════════════════════
    //  权限申请（三段式状态机）
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据 Android 版本和当前权限状态，决定本次权限请求策略：
     *   阶段 1 - 首次 / 可直接请求：直接弹系统权限对话框
     *   阶段 2 - 曾拒绝，应显示说明：先展示解释弹窗，用户同意后再弹系统对话框
     *   阶段 3 - "不再询问"已生效：引导用户前往应用设置手动开启
     */
    private void requestBluetoothPermissions() {
        List<String> needed = collectMissingPermissions();

        if (needed.isEmpty()) {
            // 所有权限已就绪，直接进入主界面
            onPermissionDone(true);
            return;
        }

        // 检查是否有权限处于"已拒绝，但可再次弹窗"状态
        boolean shouldShowRationale = false;
        for (String perm : needed) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                shouldShowRationale = true;
                break;
            }
        }

        if (shouldShowRationale) {
            // 阶段 2：先告知用户为什么需要权限，再请求
            showRationaleDialog(needed);
        } else {
            // 阶段 1 / 阶段 3：直接发起请求
            // 首次请求：系统会弹出权限对话框
            // "不再询问"后：系统静默拒绝，在 onRequestPermissionsResult 中再处理
            ActivityCompat.requestPermissions(
                    this,
                    needed.toArray(new String[0]),
                    REQUEST_CODE_PERMISSIONS
            );
        }
    }

    /**
     * 弹出解释对话框（用户曾拒绝但未选"不再询问"时调用）。
     * 用户点"授予权限"后才发起系统权限请求。
     */
    private void showRationaleDialog(final List<String> needed) {
        new AlertDialog.Builder(this)
                .setTitle("需要设备权限")
                .setMessage("此应用需要蓝牙权限连接硬件设备，也需要麦克风权限使用语音控制。\n\n" +
                        "请在接下来的系统对话框中选择【允许】。")
                .setCancelable(false)
                .setPositiveButton("授予权限", (dialog, which) ->
                        ActivityCompat.requestPermissions(
                                this,
                                needed.toArray(new String[0]),
                                REQUEST_CODE_PERMISSIONS
                        ))
                .setNegativeButton("取消", (dialog, which) -> {
                    Toast.makeText(this,
                            "必须授予蓝牙权限才能连接设备",
                            Toast.LENGTH_LONG).show();
                    onPermissionDone(false);
                })
                .show();
    }

    /**
     * 弹出"前往设置"对话框（权限被永久拒绝时调用）。
     */
    private void showSettingsRedirectDialog() {
        new AlertDialog.Builder(this)
                .setTitle("设备权限已被禁用")
                .setMessage("必须授予蓝牙权限才能连接设备，必须授予麦克风权限才能使用语音控制。\n\n" +
                        "请前往「应用设置 → 权限」手动开启蓝牙和麦克风权限，然后重新启动应用。")
                .setCancelable(false)
                .setPositiveButton("前往设置", (dialog, which) -> {
                    // 跳转到本应用的系统设置页
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                    // 无论用户是否在设置中开启，返回后仍继续跳主界面（功能受限）
                    onPermissionDone(false);
                })
                .setNegativeButton("暂不设置", (dialog, which) -> {
                    Toast.makeText(this,
                            "必须授予蓝牙权限才能连接设备",
                            Toast.LENGTH_LONG).show();
                    onPermissionDone(false);
                })
                .show();
    }

    /**
     * 系统权限对话框回调。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_PERMISSIONS) return;

        // 统计是否全部授予
        boolean allGranted = (grantResults.length > 0);
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            onPermissionDone(true);
            return;
        }

        // 有权限被拒绝：检查是否存在"不再询问"的权限（永久拒绝）
        boolean anyPermanentlyDenied = false;
        for (String perm : permissions) {
            if (!isGranted(perm)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                anyPermanentlyDenied = true;
                break;
            }
        }

        if (anyPermanentlyDenied) {
            // 阶段 3：引导用户前往设置手动开启
            showSettingsRedirectDialog();
        } else {
            // 普通拒绝：提示后仍跳转主界面（蓝牙功能受限）
            Toast.makeText(this,
                    "必须授予蓝牙权限才能连接设备",
                    Toast.LENGTH_LONG).show();
            onPermissionDone(false);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  跳转逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 权限流程结束（无论结果如何）后调用，触发跳转检查。
     */
    private void onPermissionDone(boolean granted) {
        permissionDone = true;
        tryNavigateToMain();
    }

    /**
     * 当权限完成 AND Splash 最短时间到达时，跳转 WebViewActivity。
     * 两个条件必须同时满足，避免闪屏或等待超时。
     */
    private void tryNavigateToMain() {
        if (permissionDone && splashTimeDone) {
            startActivity(new Intent(this, WebViewActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 收集当前尚未授予的必要权限列表。
     *
     * @return 缺失的权限列表，全部已授予时返回空列表
     */
    private List<String> collectMissingPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+（API 31+）：新蓝牙运行时权限
            if (!isGranted(Manifest.permission.BLUETOOTH_CONNECT))
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (!isGranted(Manifest.permission.BLUETOOTH_SCAN))
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            // Android 6~11（API 23~30）：BLE 扫描需要位置权限
            if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION))
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            // BLUETOOTH / BLUETOOTH_ADMIN 在 API <= 30 是安装时权限，无需运行时申请
        }

        // 语音控制依赖 WebView getUserMedia / SpeechRecognition 的麦克风能力。
        // 如果等到网页内部触发再申请，部分国产系统或双开环境会直接返回 not-allowed；
        // 启动阶段一并申请更稳定，也能避免页面显示“麦克风被拦截”。
        if (!isGranted(Manifest.permission.RECORD_AUDIO))
            needed.add(Manifest.permission.RECORD_AUDIO);

        return needed;
    }

    /**
     * 检查指定权限是否已授予。
     *
     * @param permission 权限字符串（Manifest.permission.xxx）
     * @return true = 已授予
     */
    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
