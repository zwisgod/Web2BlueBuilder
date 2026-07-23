// 文件路径：java/com/web2blue/shell/WebViewActivity.java
package com.web2blue.shell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.web2blue.shell.bridge.BlueAppBridge;
import com.web2blue.shell.bridge.CloudConfigBridge;
import com.web2blue.shell.bridge.PillBoxNativeBridge;
import com.web2blue.shell.bridge.SpeechRecognitionBridge;
import com.web2blue.shell.bridge.TextToSpeechBridge;
import com.web2blue.shell.debug.DebugConsoleView;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * WebViewActivity — 应用主界面，承载用户 HTML 页面的 WebView 容器。
 *
 * <p>职责：
 * 1. 配置 WebView（开启 JS、file:// 访问、DOM Storage 等）
 * 2. 注入 BlueAppBridge，将 Android 蓝牙能力暴露给 HTML 的 JS
 * 3. 加载 assets/web/index.html（用户的 HTML 项目入口）
 * 4. 监听屏幕顶部边缘的 5 连击手势，触发调试悬浮控制台
 * 5. 管理 DebugConsoleView 的生命周期（创建→使用→销毁）
 *
 * <p>权限策略：
 * 蓝牙权限采用"按需触发"策略——不在 onCreate 主动弹窗，
 * 而是由 BlueAppBridge.selectDevice() / connect() 在用户真正需要蓝牙时触发。
 * 麦克风权限在启动时预先申请，避免 WebView 首次使用语音时被系统直接拦截。
 * 权限结果由 onRequestPermissionsResult() 统一接收并回传给 JS。
 *
 * <p>5 连击手势说明：
 * ─ 触发区域：屏幕顶部 56dp 高的水平条带
 * ─ 判断逻辑：在 2000ms 内连续点击 5 次
 * ─ 触发后：弹出调试悬浮控制台
 * ─ 再次 5 连击：隐藏悬浮控制台
 */
public class WebViewActivity extends AppCompatActivity {

    /**
     * 蓝牙权限请求码，与 BlueAppBridge.REQUEST_CODE_BT_PERMISSIONS 保持一致（均为 2001）。
     * 权限请求由 BlueAppBridge 发起，结果在此 Activity 的回调中处理。
     */
    static final int REQUEST_CODE_BT_PERMISSIONS = 2001;
    private static final int REQUEST_CODE_AUDIO_CAPTURE = 2002;

    // ─── 5 连击手势参数 ────────────────────────────────────────────
    private static final int  TAP_THRESHOLD  = 5;
    private static final long TAP_TIMEOUT_MS = 2_000L;
    private static final int  TAP_ZONE_DP    = 56;

    // ─── 组件引用 ──────────────────────────────────────────────────
    private WebView webView;
    private BlueAppBridge blueAppBridge;
    private CloudConfigBridge cloudConfigBridge;
    private PillBoxNativeBridge pillBoxNativeBridge;
    private SpeechRecognitionBridge speechRecognitionBridge;
    private TextToSpeechBridge textToSpeechBridge;
    private DebugConsoleView debugConsoleView;
    private final LogRepository logRepository = LogRepository.getInstance();
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private PermissionRequest pendingAudioPermissionRequest;
    private boolean audioPermissionRequestInFlight;

    // ─── 连击计数状态 ──────────────────────────────────────────────
    private int  tapCount          = 0;
    private long firstTapTimestamp = 0L;

    // ════════════════════════════════════════════════════════════════
    //  Activity 生命周期
    // ════════════════════════════════════════════════════════════════

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        webView = findViewById(R.id.webview);

        // 必须先创建 DebugConsoleView，再创建 BlueAppBridge
        debugConsoleView = new DebugConsoleView(this);

        // 创建 JS 桥接对象
        blueAppBridge = new BlueAppBridge(this, webView, debugConsoleView);
        cloudConfigBridge = new CloudConfigBridge(this, webView);
        pillBoxNativeBridge = new PillBoxNativeBridge(this, webView);
        speechRecognitionBridge = new SpeechRecognitionBridge(this, webView);
        textToSpeechBridge = new TextToSpeechBridge(this, webView);
        scanLauncher = registerForActivityResult(new ScanContract(), this::handleScanResult);
        pillBoxNativeBridge.setScannerLauncher(scanLauncher);
        fileChooserLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Uri[] uris = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        uris = WebChromeClient.FileChooserParams.parseResult(
                                result.getResultCode(),
                                result.getData());
                    }
                    if (pendingFileChooserCallback != null) {
                        pendingFileChooserCallback.onReceiveValue(uris);
                        pendingFileChooserCallback = null;
                    }
                });

        // 配置 WebView
        configureWebView();

        // Launcher 直接进入 WebViewActivity，需在网页使用语音前申请麦克风权限。
        requestAudioPermissionIfNeeded();

        // 注入 JS 接口（在 loadUrl 之前注入，否则页面加载期间调用 API 会失败）
        webView.addJavascriptInterface(blueAppBridge, "BlueApp");
        webView.addJavascriptInterface(cloudConfigBridge, "NativeCloudConfig");
        webView.addJavascriptInterface(pillBoxNativeBridge, "AndroidPillBox");
        webView.addJavascriptInterface(pillBoxNativeBridge, "PillBoxNative");
        webView.addJavascriptInterface(speechRecognitionBridge, "NativeSpeech");
        webView.addJavascriptInterface(textToSpeechBridge, "NativeTTS");

        // 加载用户 HTML 入口
        webView.loadUrl("file:///android_asset/web/index.html");

        logRepository.addLog(LogEntry.system("[系统] WebViewActivity 已启动，正在加载页面..."));

        // 绑定顶部 5 连击手势
        setupDebugGesture();

        // ── 注意：此处不主动申请蓝牙权限 ────────────────────────────
        // 权限采用"按需触发"策略：
        //   用户点击 HTML 中的"选择设备"或"连接"按钮
        //   → JS 调用 BlueApp.selectDevice() 或 BlueApp.connect()
        //   → BlueAppBridge 检测到权限缺失时，自动调用 requestPermissions
        //   → 结果回调至下方的 onRequestPermissionsResult()
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pillBoxNativeBridge != null) {
            pillBoxNativeBridge.release();
        }
        if (cloudConfigBridge != null) {
            cloudConfigBridge.release();
        }
        if (speechRecognitionBridge != null) {
            speechRecognitionBridge.release();
        }
        if (textToSpeechBridge != null) {
            textToSpeechBridge.release();
        }
        if (debugConsoleView != null) {
            debugConsoleView.destroy();
        }
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
        }
        logRepository.addLog(LogEntry.system("[系统] WebViewActivity 已销毁，资源已释放"));
    }

    // ════════════════════════════════════════════════════════════════
    //  权限结果回调（由 BlueAppBridge 发起请求后在此处接收结果）
    // ════════════════════════════════════════════════════════════════

    /**
     * 接收系统权限对话框的用户选择结果，并将结果转发给 BlueAppBridge。
     *
     * <p>请求由 BlueAppBridge 通过 ActivityCompat.requestPermissions() 发起，
     * 但系统回调只能到达 Activity，所以在此中转，再调用 Bridge 的处理方法。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_BT_PERMISSIONS) {
            boolean allGranted = (grantResults.length > 0);
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                logRepository.addLog(LogEntry.system("[系统] 蓝牙权限全部授予"));
                blueAppBridge.notifyPermissionGranted();
            } else {
                logRepository.addLog(LogEntry.system("[系统] 蓝牙权限被拒绝"));
                Toast.makeText(this,
                        "必须授予蓝牙权限才能连接设备",
                        Toast.LENGTH_LONG).show();
                blueAppBridge.notifyPermissionDenied();
            }
            return;
        }

        if (requestCode == REQUEST_CODE_AUDIO_CAPTURE) {
            audioPermissionRequestInFlight = false;
            boolean granted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PermissionRequest request = pendingAudioPermissionRequest;
            pendingAudioPermissionRequest = null;

            if (granted) {
                if (request != null) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                }
                logRepository.addLog(LogEntry.system("[系统] 麦克风权限已授予"));
            } else {
                if (request != null) {
                    request.deny();
                }
                logRepository.addLog(LogEntry.system("[系统] 麦克风权限被拒绝"));
                Toast.makeText(this,
                        "需要授予麦克风权限才能使用语音",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        pillBoxNativeBridge.onRequestPermissionsResult(requestCode, grantResults);
    }

    /**
     * 拦截返回键：让 WebView 的 JS 历史栈先后退，
     * 若 WebView 已无法后退，再退出 Activity。
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  WebView 配置
    // ════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDefaultTextEncodingName("UTF-8");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                logRepository.addLog(LogEntry.system("[系统] 页面加载完成：" + url));
                pillBoxNativeBridge.notifyBridgeReady();
            }

            @Override
            public void onReceivedError(WebView view,
                                        WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    logRepository.addLog(LogEntry.system(
                            "[系统] 页面加载错误：" + error.getDescription() +
                            " (" + error.getErrorCode() + ")"));
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String[] resources = request.getResources();
                boolean wantsAudio = false;
                for (String resource : resources) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        wantsAudio = true;
                        break;
                    }
                }

                if (!wantsAudio) {
                    request.deny();
                    return;
                }

                if (ContextCompat.checkSelfPermission(
                        WebViewActivity.this,
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    return;
                }

                pendingAudioPermissionRequest = request;
                requestAudioPermissionIfNeeded();
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception error) {
                    pendingFileChooserCallback = null;
                    Toast.makeText(WebViewActivity.this,
                            "无法打开图片选择器",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }

                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception error) {
                    pendingFileChooserCallback = null;
                    Toast.makeText(WebViewActivity.this,
                            "当前手机没有可用的图片选择器",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });
        webView.setBackgroundColor(0xFF000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  调试控制台手势：顶部 5 连击
    // ════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private void setupDebugGesture() {
        final float density   = getResources().getDisplayMetrics().density;
        final float tapZonePx = TAP_ZONE_DP * density;

        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
            if (event.getY() > tapZonePx) return false;

            long now = System.currentTimeMillis();
            if (tapCount == 0 || (now - firstTapTimestamp) > TAP_TIMEOUT_MS) {
                tapCount          = 1;
                firstTapTimestamp = now;
            } else {
                tapCount++;
            }

            if (tapCount >= TAP_THRESHOLD) {
                tapCount = 0;
                runOnUiThread(() -> {
                    boolean willShow = !debugConsoleView.isVisible();
                    if (willShow) {
                        debugConsoleView.show();
                        Toast.makeText(WebViewActivity.this,
                                "调试控制台已开启", Toast.LENGTH_SHORT).show();
                    } else {
                        debugConsoleView.hide();
                        Toast.makeText(WebViewActivity.this,
                                "调试控制台已关闭", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            return false;
        });
    }

    private void requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED || audioPermissionRequestInFlight) {
            return;
        }

        audioPermissionRequestInFlight = true;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_CODE_AUDIO_CAPTURE
        );
    }

    private void handleScanResult(ScanIntentResult result) {
        if (pillBoxNativeBridge == null) {
            return;
        }
        pillBoxNativeBridge.onScanResult(result == null ? null : result.getContents());
    }
}
