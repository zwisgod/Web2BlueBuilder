package com.web2blue.shell.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SpeechRecognitionBridge {
    private static final String TAG = "Web2BlueSpeech";
    private static final String OFFLINE_MODEL_ASSET = "vosk-model-small-cn-0.22";
    private static final String OFFLINE_MODEL_STORAGE = "vosk-model-cn-0.22";
    private static final float SAMPLE_RATE = 16000.0f;

    private final Activity activity;
    private final WebView webView;
    private Model offlineModel;
    private Recognizer offlineRecognizer;
    private SpeechService offlineSpeechService;
    private final ExecutorService robotRecognitionExecutor = Executors.newSingleThreadExecutor();
    private boolean offlineModelLoading;
    private boolean pendingOfflineStart;
    private boolean listening;
    private volatile boolean robotRecognizing;

    public SpeechRecognitionBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        initializeOfflineModel();
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return offlineModel != null || offlineModelLoading;
    }

    @JavascriptInterface
    public void start() {
        activity.runOnUiThread(() -> {
            int permission = ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
            );
            Log.i(TAG, "start requested, permission=" + permission
                    + ", modelReady=" + (offlineModel != null)
                    + ", modelLoading=" + offlineModelLoading);
            if (permission != PackageManager.PERMISSION_GRANTED) {
                emitError("not-allowed", "请允许麦克风权限");
                return;
            }
            if (listening) {
                emitError("recognizer-busy", "语音识别正在运行");
                return;
            }
            startOfflineRecognition();
        });
    }

    @JavascriptInterface
    public void stop() {
        activity.runOnUiThread(() -> {
            if (!listening) {
                return;
            }
            if (offlineSpeechService != null) {
                offlineSpeechService.stop();
            }
        });
    }

    @JavascriptInterface
    public void cancel() {
        activity.runOnUiThread(() -> {
            listening = false;
            pendingOfflineStart = false;
            closeOfflineSession();
        });
    }

    @JavascriptInterface
    public boolean isRobotRecognitionAvailable() {
        return offlineModel != null && !robotRecognizing;
    }

    @JavascriptInterface
    public void recognizeRobotPcm(String requestId, String base64Pcm) {
        String safeRequestId = requestId == null ? "" : requestId.trim();
        String encoded = base64Pcm == null ? "" : base64Pcm.trim();
        if (safeRequestId.isEmpty()) {
            return;
        }
        if (encoded.isEmpty()) {
            emitRobotError(safeRequestId, "empty-audio", "机器人没有采集到语音");
            return;
        }
        if (offlineModel == null) {
            emitRobotError(safeRequestId, "model-not-ready", "离线中文语音模型尚未就绪");
            return;
        }
        if (listening || robotRecognizing) {
            emitRobotError(safeRequestId, "recognizer-busy", "语音识别正在运行");
            return;
        }

        robotRecognizing = true;
        robotRecognitionExecutor.execute(() -> {
            Recognizer recognizer = null;
            try {
                byte[] pcm = Base64.decode(encoded, Base64.DEFAULT);
                if (pcm.length < 3200) {
                    emitRobotError(safeRequestId, "audio-too-short", "机器人录音时间太短");
                    return;
                }
                recognizer = new Recognizer(offlineModel, SAMPLE_RATE);
                int offset = 0;
                while (offset < pcm.length) {
                    int chunk = Math.min(4096, pcm.length - offset);
                    byte[] block = new byte[chunk];
                    System.arraycopy(pcm, offset, block, 0, chunk);
                    recognizer.acceptWaveForm(block, chunk);
                    offset += chunk;
                }
                String text = extractOfflineText(recognizer.getFinalResult(), "text");
                if (text.isEmpty()) {
                    emitRobotError(safeRequestId, "no-speech", "未识别到机器人端语音");
                } else {
                    emit("onNativeRobotSpeechResult",
                            JSONObject.quote(safeRequestId) + "," + JSONObject.quote(text));
                }
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Robot PCM recognition failed", error);
                emitRobotError(safeRequestId, "offline-error", "机器人端离线语音识别失败");
            } finally {
                if (recognizer != null) {
                    recognizer.close();
                }
                robotRecognizing = false;
            }
        });
    }

    public void release() {
        listening = false;
        pendingOfflineStart = false;
        closeOfflineSession();
        robotRecognitionExecutor.shutdownNow();
        if (offlineModel != null) {
            offlineModel.close();
            offlineModel = null;
        }
    }

    private void startOfflineRecognition() {
        if (offlineModel == null) {
            pendingOfflineStart = true;
            emit("onNativeSpeechPreparing", JSONObject.quote("正在加载离线中文语音模型..."));
            if (!offlineModelLoading) {
                initializeOfflineModel();
            }
            return;
        }

        try {
            offlineRecognizer = new Recognizer(offlineModel, SAMPLE_RATE);
            offlineSpeechService = new SpeechService(offlineRecognizer, SAMPLE_RATE);
            listening = true;
            Log.i(TAG, "Vosk offline recognition started");
            emit("onNativeSpeechStart", "");
            offlineSpeechService.startListening(new org.vosk.android.RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    String text = extractOfflineText(hypothesis, "partial");
                    if (!text.isEmpty()) {
                        emit("onNativeSpeechPartial", JSONObject.quote(text));
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    String text = extractOfflineText(hypothesis, "text");
                    if (!text.isEmpty()) {
                        emit("onNativeSpeechResult", JSONObject.quote(text));
                    }
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    listening = false;
                    String text = extractOfflineText(hypothesis, "text");
                    if (!text.isEmpty()) {
                        emit("onNativeSpeechResult", JSONObject.quote(text));
                    } else {
                        emitError("no-speech", "未识别到语音");
                    }
                    activity.runOnUiThread(SpeechRecognitionBridge.this::closeOfflineSession);
                }

                @Override
                public void onError(Exception error) {
                    listening = false;
                    emitError("offline-error", "离线语音识别失败");
                    activity.runOnUiThread(SpeechRecognitionBridge.this::closeOfflineSession);
                }

                @Override
                public void onTimeout() {
                    listening = false;
                    emitError("no-speech", "未识别到语音");
                    activity.runOnUiThread(SpeechRecognitionBridge.this::closeOfflineSession);
                }
            });
        } catch (IOException | RuntimeException error) {
            listening = false;
            closeOfflineSession();
            Log.e(TAG, "Vosk start failed", error);
            emitError("offline-start-failed", "离线语音识别启动失败");
        }
    }

    private void initializeOfflineModel() {
        if (offlineModel != null || offlineModelLoading) {
            return;
        }
        offlineModelLoading = true;
        StorageService.unpack(
                activity,
                OFFLINE_MODEL_ASSET,
                OFFLINE_MODEL_STORAGE,
                model -> activity.runOnUiThread(() -> {
                    offlineModel = model;
                    offlineModelLoading = false;
                    Log.i(TAG, "Vosk offline model ready");
                    emit("onNativeSpeechReady", "");
                    if (pendingOfflineStart) {
                        pendingOfflineStart = false;
                        startOfflineRecognition();
                    }
                }),
                error -> activity.runOnUiThread(() -> {
                    offlineModelLoading = false;
                    pendingOfflineStart = false;
                    Log.e(TAG, "Vosk model load failed", error);
                    emitError("model-load-failed", "离线语音模型加载失败");
                })
        );
    }

    private String extractOfflineText(String hypothesis, String field) {
        if (hypothesis == null || hypothesis.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(hypothesis)
                    .optString(field, "")
                    .replaceAll("\\s+", "")
                    .trim();
        } catch (JSONException error) {
            return "";
        }
    }

    private void closeOfflineSession() {
        if (offlineSpeechService != null) {
            offlineSpeechService.cancel();
            offlineSpeechService.shutdown();
            offlineSpeechService = null;
        }
        if (offlineRecognizer != null) {
            offlineRecognizer.close();
            offlineRecognizer = null;
        }
    }

    private void emitError(String code, String message) {
        emit("onNativeSpeechError", JSONObject.quote(code) + "," + JSONObject.quote(message));
    }

    private void emitRobotError(String requestId, String code, String message) {
        emit("onNativeRobotSpeechError",
                JSONObject.quote(requestId) + "," + JSONObject.quote(code) + "," + JSONObject.quote(message));
    }

    private void emit(String callback, String arguments) {
        String script = "window." + callback + " && window." + callback + "(" + arguments + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

}
