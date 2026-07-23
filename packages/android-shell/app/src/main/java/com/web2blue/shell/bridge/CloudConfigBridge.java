package com.web2blue.shell.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public final class CloudConfigBridge {
    private static final String PREFS_NAME = "cloud_config_secure";
    private static final String FALLBACK_PREFS_NAME = "cloud_config_private";
    private static final String DEEPSEEK_KEY = "deepseek_api_key";
    private static final String DASHSCOPE_KEY = "dashscope_api_key";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    private static final String QWEN_TTS_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final int MAX_TTS_AUDIO_BYTES = 2 * 1024 * 1024;

    private final WebView webView;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public CloudConfigBridge(Activity activity, WebView webView) {
        this.webView = webView;
        this.preferences = createPreferences(activity);
    }

    @JavascriptInterface
    public boolean saveDeepSeekApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) {
            return false;
        }
        return preferences.edit().putString(DEEPSEEK_KEY, value).commit();
    }

    @JavascriptInterface
    public boolean hasDeepSeekApiKey() {
        return !preferences.getString(DEEPSEEK_KEY, "").trim().isEmpty();
    }

    @JavascriptInterface
    public boolean clearDeepSeekApiKey() {
        return preferences.edit().remove(DEEPSEEK_KEY).commit();
    }

    @JavascriptInterface
    public boolean saveDashScopeApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        if (value.isEmpty()) {
            return false;
        }
        return preferences.edit().putString(DASHSCOPE_KEY, value).commit();
    }

    @JavascriptInterface
    public boolean hasDashScopeApiKey() {
        return !preferences.getString(DASHSCOPE_KEY, "").trim().isEmpty();
    }

    @JavascriptInterface
    public boolean clearDashScopeApiKey() {
        return preferences.edit().remove(DASHSCOPE_KEY).commit();
    }

    @JavascriptInterface
    public void requestDeepSeek(String requestId, String requestBody) {
        String safeRequestId = requestId == null ? "" : requestId.trim();
        String body = requestBody == null ? "" : requestBody;
        String apiKey = preferences.getString(DEEPSEEK_KEY, "").trim();
        if (safeRequestId.isEmpty()) {
            return;
        }
        if (apiKey.isEmpty()) {
            emitResponse(safeRequestId, 401, "{\"error\":\"DeepSeek API Key 为空\"}");
            return;
        }

        executor.execute(() -> executeRequest(safeRequestId, body, apiKey));
    }

    @JavascriptInterface
    public void requestQwenTts(String requestId, String requestBody) {
        String safeRequestId = requestId == null ? "" : requestId.trim();
        String body = requestBody == null ? "" : requestBody;
        String apiKey = preferences.getString(DASHSCOPE_KEY, "").trim();
        if (safeRequestId.isEmpty()) {
            return;
        }
        if (apiKey.isEmpty()) {
            emitQwenTtsResponse(safeRequestId, 401, "", "", "DashScope API Key 为空");
            return;
        }
        executor.execute(() -> executeQwenTtsRequest(safeRequestId, body, apiKey));
    }

    public void release() {
        executor.shutdownNow();
    }

    private SharedPreferences createPreferences(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException error) {
            return context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private void executeRequest(String requestId, String requestBody, String apiKey) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(DEEPSEEK_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            emitResponse(requestId, status, readBody(input));
        } catch (Exception error) {
            emitResponse(requestId, 0,
                    "{\"error\":" + JSONObject.quote(error.getClass().getSimpleName()) + "}");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void executeQwenTtsRequest(String requestId, String requestBody, String apiKey) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(QWEN_TTS_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readBody(input);
            if (status < 200 || status >= 300) {
                emitQwenTtsResponse(requestId, status, "", "", responseBody);
                return;
            }

            JSONObject response = new JSONObject(responseBody);
            JSONObject output = response.optJSONObject("output");
            JSONObject audio = output == null ? null : output.optJSONObject("audio");
            String audioUrl = audio == null ? "" : audio.optString("url", "").trim();
            if (audioUrl.isEmpty() && output != null) {
                audioUrl = output.optString("audio_url", "").trim();
            }
            if (audioUrl.isEmpty()) {
                emitQwenTtsResponse(requestId, 502, "", "", "TTS响应缺少音频地址");
                return;
            }

            DownloadedAudio downloaded = downloadAudio(audioUrl);
            String encoded = Base64.encodeToString(downloaded.bytes, Base64.NO_WRAP);
            emitQwenTtsResponse(requestId, 200, downloaded.contentType, encoded, "");
        } catch (Exception error) {
            emitQwenTtsResponse(requestId, 0, "", "", error.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private DownloadedAudio downloadAudio(String audioUrl) throws IOException {
        URLConnection connection = new URL(audioUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        String contentType = connection.getContentType();
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_TTS_AUDIO_BYTES) {
                    throw new IOException("TTS audio too large");
                }
                output.write(buffer, 0, read);
            }
            return new DownloadedAudio(output.toByteArray(), contentType == null ? "" : contentType);
        }
    }

    private String readBody(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private void emitResponse(String requestId, int status, String responseBody) {
        String script = "window.onNativeDeepSeekResponse && window.onNativeDeepSeekResponse("
                + JSONObject.quote(requestId) + ","
                + status + ","
                + JSONObject.quote(responseBody == null ? "" : responseBody)
                + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private void emitQwenTtsResponse(String requestId,
                                     int status,
                                     String contentType,
                                     String audioBase64,
                                     String errorMessage) {
        String script = "window.onNativeQwenTtsResponse && window.onNativeQwenTtsResponse("
                + JSONObject.quote(requestId) + ","
                + status + ","
                + JSONObject.quote(contentType == null ? "" : contentType) + ","
                + JSONObject.quote(audioBase64 == null ? "" : audioBase64) + ","
                + JSONObject.quote(errorMessage == null ? "" : errorMessage)
                + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private static final class DownloadedAudio {
        final byte[] bytes;
        final String contentType;

        DownloadedAudio(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }
}
