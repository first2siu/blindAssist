package com.example.test_android_dev;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 封装语音输入/输出（ASR/TTS）
 * 针对小米“系统语音引擎”优化版
 */
public class VoiceManager {

    private static final String TAG = "VoiceManager";
    private static VoiceManager instance;

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;

    private boolean isTtsReady = false;
    private boolean isTtsInitializing = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> utteranceCallbacks = new ConcurrentHashMap<>();
    private final List<PendingUtterance> pendingUtterances = new ArrayList<>();

    private VoiceManager() {}

    public static synchronized VoiceManager getInstance() {
        if (instance == null) {
            instance = new VoiceManager();
        }
        return instance;
    }

    public void init(Context context) {
        Log.d(TAG, "开始初始化 VoiceManager");
        if (tts != null || isTtsInitializing) return;

        isTtsInitializing = true;
        
        // 打印系统安装的所有 TTS 引擎，方便你在 Logcat 中查看真实的包名
        logAvailableEngines(context);

        // 第一步：尝试使用系统默认引擎（即你在设置里选中的“系统语音引擎”）
        mainHandler.post(() -> initTts(context, null));

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        }
    }

    private void initTts(Context context, String enginePackageName) {
        String target = (enginePackageName == null) ? "系统默认" : enginePackageName;
        Log.i(TAG, "正在尝试初始化引擎: " + target);

        try {
            // 如果 enginePackageName 为 null，Android 会使用系统设置里的“首选引擎”
            tts = new TextToSpeech(context.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    onTtsInitialized(target);
                } else {
                    Log.e(TAG, target + " 初始化失败，错误码: " + status);
                    handleInitFailure(context, enginePackageName);
                }
            }, enginePackageName);
        } catch (Exception e) {
            Log.e(TAG, target + " 初始化异常", e);
            handleInitFailure(context, enginePackageName);
        }
    }

    private void onTtsInitialized(String engineName) {
        Log.i(TAG, "TTS 引擎初始化成功: " + engineName);
        
        // 小米“系统语音引擎”适配：优先设置 Locale.CHINA
        int result = tts.setLanguage(Locale.CHINA);
        Log.d(TAG, "设置语言 Locale.CHINA 结果: " + result + " (0代表成功)");

        if (result < 0) {
            result = tts.setLanguage(Locale.CHINESE);
            Log.d(TAG, "尝试 Locale.CHINESE 结果: " + result);
        }

        setupUtteranceListener();
        isTtsReady = true;
        isTtsInitializing = false;

        // 执行排队的播报
        processPendingUtterances();
        
        // 初始化成功后，立即进行一次静默测试播报，看 Logcat 是否有 Done 回调
        speak("语音引擎已就绪");
    }

    private void handleInitFailure(Context context, String currentEngine) {
        isTtsInitializing = false;
        if (currentEngine == null) {
            // 如果默认引擎失败，尝试强制指定小米常用的包名（备选方案）
            Log.w(TAG, "默认引擎失败，尝试强制调用小米小爱引擎包名...");
            mainHandler.post(() -> initTts(context, "com.xiaomi.mibrain.speech"));
        }
    }

    private void logAvailableEngines(Context context) {
        try {
            TextToSpeech temp = new TextToSpeech(context, status -> {});
            List<TextToSpeech.EngineInfo> engines = temp.getEngines();
            Log.d(TAG, "---- 系统已安装的 TTS 引擎列表 ----");
            for (TextToSpeech.EngineInfo info : engines) {
                Log.d(TAG, "引擎名: " + info.label + " | 包名: " + info.name);
            }
            temp.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "无法获取引擎列表", e);
        }
    }

    private void setupUtteranceListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { 
                Log.d(TAG, "开始播报: " + utteranceId); 
            }
            @Override public void onDone(String utteranceId) {
                Log.d(TAG, "播报完成: " + utteranceId);
                Runnable cb = utteranceCallbacks.remove(utteranceId);
                if (cb != null) mainHandler.post(cb);
            }
            @Override public void onError(String utteranceId) {
                Log.e(TAG, "播报错误: " + utteranceId);
                utteranceCallbacks.remove(utteranceId);
            }
        });
    }

    public void speak(String text) {
        if (isTtsReady && tts != null) {
            executeSpeak(text, TextToSpeech.QUEUE_ADD, null);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(new PendingUtterance(text, false, null));
            }
        }
    }

    public void speakImmediate(String text) {
        speakImmediate(text, null);
    }

    public void speakImmediate(String text, Runnable onDone) {
        if (isTtsReady && tts != null) {
            executeSpeak(text, TextToSpeech.QUEUE_FLUSH, onDone);
        } else {
            synchronized (pendingUtterances) {
                pendingUtterances.add(new PendingUtterance(text, true, onDone));
            }
        }
    }

    private void executeSpeak(String text, int queueMode, Runnable onDone) {
        String uid = UUID.randomUUID().toString();
        if (onDone != null) utteranceCallbacks.put(uid, onDone);

        Bundle params = new Bundle();
        // 核心：强制使用音乐流，防止被系统静音
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        
        int result = tts.speak(text, queueMode, params, uid);
        Log.d(TAG, "调用 tts.speak 结果: " + result + " (0为成功) | 文本: " + text);
    }

    private void processPendingUtterances() {
        synchronized (pendingUtterances) {
            for (PendingUtterance p : pendingUtterances) {
                executeSpeak(p.text, p.isImmediate ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, p.onDoneCallback);
            }
            pendingUtterances.clear();
        }
    }

    private static class PendingUtterance {
        String text; boolean isImmediate; Runnable onDoneCallback;
        PendingUtterance(String t, boolean i, Runnable c) { text = t; isImmediate = i; onDoneCallback = c; }
    }

    // ASR 接口...
    public interface VoiceCallback { void onResult(String text); void onError(String error); }
    public void startListening(VoiceCallback callback) { /* 保持原有逻辑 */ }
}
