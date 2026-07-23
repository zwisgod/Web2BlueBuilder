package com.web2blue.shell.bridge;

import android.app.Activity;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public final class TextToSpeechBridge implements TextToSpeech.OnInitListener {
    private static final String TAG = "Web2BlueTTS";

    private final Activity activity;
    private final WebView webView;
    private TextToSpeech textToSpeech;
    private volatile boolean initialized;
    private volatile boolean ready;
    private volatile String statusMessage = "TTS 正在初始化";
    private String pendingText = "";

    public TextToSpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        activity.runOnUiThread(this::initialize);
    }

    @Override
    public void onInit(int status) {
        initialized = true;
        if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
            ready = false;
            statusMessage = "手机没有可用的系统 TTS 引擎";
            Log.e(TAG, "TextToSpeech initialization failed, status=" + status);
            emit("onNativeTtsError", JSONObject.quote(statusMessage));
            return;
        }

        int languageStatus = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            languageStatus = textToSpeech.setLanguage(Locale.CHINA);
        }

        textToSpeech.setSpeechRate(1.05f);
        textToSpeech.setPitch(1.0f);
        textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.i(TAG, "Playback started: " + utteranceId);
                emit("onNativeTtsStart", "");
            }

            @Override
            public void onDone(String utteranceId) {
                Log.i(TAG, "Playback completed: " + utteranceId);
                emit("onNativeTtsDone", "");
            }

            @Override
            public void onError(String utteranceId) {
                reportPlaybackError("语音播报失败");
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                reportPlaybackError("语音播报失败（" + errorCode + "）");
            }
        });

        ready = true;
        statusMessage = languageStatus >= TextToSpeech.LANG_AVAILABLE
                ? "TTS 已就绪"
                : "TTS 已就绪，但手机缺少中文音色";
        Log.i(TAG, statusMessage + ", languageStatus=" + languageStatus);
        emit("onNativeTtsReady", JSONObject.quote(statusMessage));
        if (!pendingText.isEmpty()) {
            String text = pendingText;
            pendingText = "";
            speakOnMainThread(text);
        }
    }

    @JavascriptInterface
    public boolean isReady() {
        return ready;
    }

    @JavascriptInterface
    public String getStatus() {
        if (ready) {
            return "ready";
        }
        return initialized ? "error" : "initializing";
    }

    @JavascriptInterface
    public String getStatusMessage() {
        return statusMessage;
    }

    @JavascriptInterface
    public boolean speak(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return false;
        }
        if (initialized && !ready) {
            emit("onNativeTtsError", JSONObject.quote(statusMessage));
            return false;
        }
        activity.runOnUiThread(() -> {
            if (!ready || textToSpeech == null) {
                pendingText = value;
                return;
            }
            speakOnMainThread(value);
        });
        return true;
    }

    @JavascriptInterface
    public void stop() {
        activity.runOnUiThread(() -> {
            pendingText = "";
            if (textToSpeech != null) {
                textToSpeech.stop();
            }
        });
    }

    public void release() {
        initialized = true;
        ready = false;
        pendingText = "";
        statusMessage = "TTS 已释放";
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }

    private void initialize() {
        initialized = false;
        ready = false;
        statusMessage = "TTS 正在初始化";
        textToSpeech = new TextToSpeech(activity, this);
    }

    private void speakOnMainThread(String text) {
        String utteranceId = "ai_reply_" + UUID.randomUUID();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int result = textToSpeech.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                params,
                utteranceId
        );
        Log.i(TAG, "speak result=" + result + ", chars=" + text.length());
        if (result == TextToSpeech.ERROR) {
            reportPlaybackError("系统 TTS 拒绝播放");
        }
    }

    private void reportPlaybackError(String message) {
        Log.e(TAG, message);
        emit("onNativeTtsError", JSONObject.quote(message));
    }

    private void emit(String callback, String arguments) {
        String script = "window." + callback + " && window." + callback + "(" + arguments + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }
}
