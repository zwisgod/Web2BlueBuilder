package com.web2blue.shell.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.content.ContentResolver;
import android.content.ContentValues;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanOptions;
import com.web2blue.shell.debug.LogEntry;
import com.web2blue.shell.debug.LogRepository;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class PillBoxNativeBridge {

    public static final int REQUEST_CODE_CAMERA_PERMISSION = 2101;
    public static final int REQUEST_CODE_NETWORK_PERMISSION = 2102;

    private static final String DEFAULT_BASE_URL = "http://192.168.4.1/api";

    private final Activity activity;
    private final Context context;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LogRepository logRepository = LogRepository.getInstance();
    private final ConnectivityManager connectivityManager;

    private ActivityResultLauncher<ScanOptions> scannerLauncher;
    private PendingRequest pendingRequest;
    private ConnectivityManager.NetworkCallback activeNetworkCallback;

    public PillBoxNativeBridge(Context context, WebView webView) {
        this.context = context;
        this.activity = (Activity) context;
        this.webView = webView;
        this.connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setScannerLauncher(ActivityResultLauncher<ScanOptions> scannerLauncher) {
        this.scannerLauncher = scannerLauncher;
    }

    @JavascriptInterface
    public void scanAndConnect(String requestJson) {
        PendingRequest request = PendingRequest.fromRequestJson(requestJson);
        pendingRequest = request;
        logRepository.addLog(LogEntry.system("[PillBoxBridge] 收到扫码连接请求"));

        if (!hasCameraPermission()) {
            requestCameraPermission();
            return;
        }

        launchScanner();
    }

    public void notifyBridgeReady() {
        dispatchBridgeReady("AndroidPillBox", "Android 原生桥已注入");
    }

    public void release() {
        if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(null);
            releaseNetworkCallback(connectivityManager);
        }
    }

    public void onScanResult(String rawContent) {
        if (TextUtils.isEmpty(rawContent)) {
            dispatchStatus("cancelled", "AndroidPillBox", null, "用户取消扫码");
            return;
        }

        ParsedSetup setup = ParsedSetup.fromQrContent(rawContent, pendingRequest);
        if (!setup.valid) {
            dispatchFailure(setup.message);
            return;
        }

        dispatchStatus("scanned", "AndroidPillBox", setup.baseUrl, "已识别药箱二维码");

        if (TextUtils.isEmpty(setup.ssid)) {
            dispatchStatus("connected", "AndroidPillBox", setup.baseUrl, "二维码已提供药箱地址");
            return;
        }

        connectToHotspot(setup);
    }

    @JavascriptInterface
    public String saveReport(String filename, String json) {
        String safeName = TextUtils.isEmpty(filename) ? "pillbox-report.json" : filename;
        safeName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safeName.endsWith(".json")) {
            safeName = safeName + ".json";
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return "{\"ok\":false,\"message\":\"无法创建下载文件\"}";
                }
                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) {
                        return "{\"ok\":false,\"message\":\"无法写入下载文件\"}";
                    }
                    output.write(String.valueOf(json).getBytes(StandardCharsets.UTF_8));
                }
                return "{\"ok\":true,\"path\":\"Downloads/" + escapeJson(safeName) + "\"}";
            }

            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloads.exists() && !downloads.mkdirs()) {
                return "{\"ok\":false,\"message\":\"无法打开下载目录\"}";
            }
            File file = new File(downloads, safeName);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(String.valueOf(json).getBytes(StandardCharsets.UTF_8));
            }
            return "{\"ok\":true,\"path\":\"" + escapeJson(file.getAbsolutePath()) + "\"}";
        } catch (Exception error) {
            return "{\"ok\":false,\"message\":\"" + escapeJson(error.getMessage()) + "\"}";
        }
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull int[] grantResults) {
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (allGranted) {
                launchScanner();
            } else {
                dispatchFailure("未授予相机权限，无法扫码连接");
            }
            return;
        }

        if (requestCode == REQUEST_CODE_NETWORK_PERMISSION) {
            if (allGranted && pendingRequest != null && pendingRequest.parsedSetup != null) {
                connectToHotspot(pendingRequest.parsedSetup);
            } else {
                dispatchFailure("未授予网络权限，无法连接药箱热点");
            }
        }
    }

    private void launchScanner() {
        if (scannerLauncher == null) {
            dispatchFailure("扫码组件尚未初始化");
            return;
        }

        mainHandler.post(() -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("请扫描药箱二维码");
            options.setBeepEnabled(false);
            options.setBarcodeImageEnabled(false);
            options.setOrientationLocked(true);
            scannerLauncher.launch(options);
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(
                activity,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CODE_CAMERA_PERMISSION
        );
    }

    private boolean hasNetworkPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNetworkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (permissions.isEmpty()) {
            return;
        }

        ActivityCompat.requestPermissions(
                activity,
                permissions.toArray(new String[0]),
                REQUEST_CODE_NETWORK_PERMISSION
        );
    }

    private void connectToHotspot(ParsedSetup setup) {
        pendingRequest.parsedSetup = setup;
        if (!hasNetworkPermissions()) {
            requestNetworkPermissions();
            return;
        }

        dispatchStatus("connecting", "AndroidPillBox", setup.baseUrl, "正在连接药箱热点");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithNetworkSpecifier(setup);
            return;
        }

        connectWithLegacyWifi(setup);
    }

    private void connectWithNetworkSpecifier(ParsedSetup setup) {
        if (connectivityManager == null) {
            dispatchFailure("系统网络服务不可用");
            return;
        }

        releaseNetworkCallback(connectivityManager);

        WifiNetworkSpecifier.Builder wifiBuilder = new WifiNetworkSpecifier.Builder()
                .setSsid(setup.ssid);
        if (!TextUtils.isEmpty(setup.password)) {
            wifiBuilder.setWpa2Passphrase(setup.password);
        }

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(wifiBuilder.build())
                .build();

        activeNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                connectivityManager.bindProcessToNetwork(network);
                dispatchStatus("connected", "AndroidPillBox", setup.baseUrl, "已连接药箱热点");
            }

            @Override
            public void onUnavailable() {
                dispatchFailure("无法连接药箱热点");
            }

            @Override
            public void onLost(@NonNull Network network) {
                dispatchStatus("failed", "AndroidPillBox", setup.baseUrl, "药箱热点连接已断开");
            }
        };

        try {
            connectivityManager.requestNetwork(networkRequest, activeNetworkCallback);
        } catch (Exception error) {
            dispatchFailure("请求系统连接药箱热点失败");
        }
    }

    @SuppressWarnings("deprecation")
    private void connectWithLegacyWifi(ParsedSetup setup) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            dispatchFailure("系统 Wi-Fi 服务不可用");
            return;
        }

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + setup.ssid + "\"";
        if (TextUtils.isEmpty(setup.password)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = "\"" + setup.password + "\"";
        }

        try {
            int networkId = wifiManager.addNetwork(config);
            if (networkId < 0) {
                dispatchFailure("保存药箱热点配置失败");
                return;
            }

            boolean enabled = wifiManager.enableNetwork(networkId, true);
            if (!enabled) {
                dispatchFailure("切换到药箱热点失败");
                return;
            }

            dispatchStatus("connected", "AndroidPillBox", setup.baseUrl, "已连接药箱热点");
        } catch (Exception error) {
            dispatchFailure("连接药箱热点失败");
        }
    }

    private void releaseNetworkCallback(ConnectivityManager connectivityManager) {
        if (activeNetworkCallback == null) {
            return;
        }

        try {
            connectivityManager.unregisterNetworkCallback(activeNetworkCallback);
        } catch (Exception ignored) {
        }
        activeNetworkCallback = null;
    }

    private void dispatchBridgeReady(String bridgeName, String message) {
        dispatchToWeb("bridgeReady", bridgeName, null, message);
    }

    private void dispatchFailure(String message) {
        dispatchToWeb("failed", "AndroidPillBox", pendingRequest == null ? null : pendingRequest.expectedBaseUrl,
                TextUtils.isEmpty(message) ? "扫码自动连接失败" : message);
    }

    private void dispatchStatus(String status, String bridgeName, String baseUrl, String message) {
        dispatchToWeb(status, bridgeName, baseUrl, message);
    }

    private void dispatchToWeb(String status, String bridgeName, String baseUrl, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("status", status);
            payload.put("bridgeName", bridgeName);
            if (!TextUtils.isEmpty(baseUrl)) {
                payload.put("baseUrl", baseUrl);
            }
            if (!TextUtils.isEmpty(message)) {
                payload.put("message", message);
            }

            final String js = "(function(){" +
                    "var payload=" + payload.toString() + ";" +
                    "if(window.PillBoxWebBridge && typeof window.PillBoxWebBridge.onNativeEvent==='function'){" +
                    "window.PillBoxWebBridge.onNativeEvent(payload);" +
                    "}else if(typeof window.__pillboxOnNativeEvent==='function'){" +
                    "window.__pillboxOnNativeEvent(payload);" +
                    "}" +
                    "})();";

            mainHandler.post(() -> webView.evaluateJavascript(js, null));
            logRepository.addLog(LogEntry.system("[PillBoxBridge] " + status + " -> " + message));
        } catch (JSONException ignored) {
        }
    }

    private static final class PendingRequest {
        private final String expectedBaseUrl;
        private ParsedSetup parsedSetup;

        private PendingRequest(String expectedBaseUrl) {
            this.expectedBaseUrl = sanitizeBaseUrl(expectedBaseUrl);
        }

        private static PendingRequest fromRequestJson(String requestJson) {
            if (TextUtils.isEmpty(requestJson)) {
                return new PendingRequest(DEFAULT_BASE_URL);
            }

            try {
                JSONObject json = new JSONObject(requestJson);
                return new PendingRequest(json.optString("expectedBaseUrl", DEFAULT_BASE_URL));
            } catch (JSONException ignored) {
                return new PendingRequest(DEFAULT_BASE_URL);
            }
        }
    }

    private static final class ParsedSetup {
        private final boolean valid;
        private final String ssid;
        private final String password;
        private final String baseUrl;
        private final String message;

        private ParsedSetup(boolean valid, String ssid, String password, String baseUrl, String message) {
            this.valid = valid;
            this.ssid = ssid;
            this.password = password;
            this.baseUrl = baseUrl;
            this.message = message;
        }

        private static ParsedSetup fromQrContent(String rawContent, PendingRequest request) {
            String trimmed = rawContent == null ? "" : rawContent.trim();
            if (trimmed.isEmpty()) {
                return new ParsedSetup(false, null, null, null, "二维码内容为空");
            }

            String fallbackBaseUrl = request == null ? DEFAULT_BASE_URL : request.expectedBaseUrl;

            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return new ParsedSetup(true, null, null, sanitizeBaseUrl(trimmed), "二维码已提供药箱地址");
            }

            try {
                JSONObject json = new JSONObject(trimmed);
                String ssid = firstNonEmpty(
                        json.optString("ssid", null),
                        json.optString("apSsid", null),
                        json.optString("wifiSsid", null)
                );
                String password = firstNonEmpty(
                        json.optString("password", null),
                        json.optString("pass", null),
                        json.optString("pwd", null)
                );
                String baseUrl = firstNonEmpty(
                        json.optString("baseUrl", null),
                        json.optString("base_url", null),
                        json.optString("url", null),
                        fallbackBaseUrl
                );
                if (TextUtils.isEmpty(ssid) && TextUtils.isEmpty(baseUrl)) {
                    return new ParsedSetup(false, null, null, null, "二维码中缺少热点名称或设备地址");
                }
                return new ParsedSetup(true, ssid, password, sanitizeBaseUrl(baseUrl), "二维码解析成功");
            } catch (JSONException ignored) {
            }

            String ssid = null;
            String password = null;
            String baseUrl = fallbackBaseUrl;
            String[] parts = trimmed.split("[\\n;&]");
            for (String part : parts) {
                String[] entry = part.split("=", 2);
                if (entry.length != 2) {
                    continue;
                }
                String key = entry[0].trim().toLowerCase();
                String value = decode(entry[1].trim());
                if (TextUtils.isEmpty(value)) {
                    continue;
                }
                if ("ssid".equals(key) || "apssid".equals(key) || "wifissid".equals(key)) {
                    ssid = value;
                } else if ("password".equals(key) || "pass".equals(key) || "pwd".equals(key)) {
                    password = value;
                } else if ("baseurl".equals(key) || "base_url".equals(key) || "url".equals(key)) {
                    baseUrl = value;
                }
            }

            if (TextUtils.isEmpty(ssid) && TextUtils.isEmpty(baseUrl)) {
                return new ParsedSetup(false, null, null, null, "暂不支持当前二维码格式");
            }
            return new ParsedSetup(true, ssid, password, sanitizeBaseUrl(baseUrl), "二维码解析成功");
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String sanitizeBaseUrl(String baseUrl) {
        if (TextUtils.isEmpty(baseUrl)) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? DEFAULT_BASE_URL : trimmed;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
