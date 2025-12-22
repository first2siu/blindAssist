package com.example.test_android_dev;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * 封装语音输入/输出（ASR/TTS）
 */
public class VoiceManager {
    private static VoiceManager instance;
    private TextToSpeech tts;
    private boolean isReady = false;

    private VoiceManager() {}

    public static synchronized VoiceManager getInstance() {
        if (instance == null) {
            instance = new VoiceManager();
        }
        return instance;
    }

    public void init(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                isReady = true;
            }
        });
    }

    public void speak(String text) {
        if (isReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    public void speakImmediate(String text) {
        if (isReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "immediate");
        }
    }

    public interface VoiceCallback {
        void onResult(String text);
    }

    public void startListening(VoiceCallback callback) {
        // TODO: 集成 ASR (如 Android 原生 SpeechRecognizer 或百度/阿里 ASR)
        // 模拟识别结果
        // callback.onResult("去天安门");
    }
