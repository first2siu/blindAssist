package com.example.test_android_dev.navigation;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.os.Bundle;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * TTS队列管理器
 * 从服务端拉取TTS消息并按顺序播放
 */
public class TtsQueueManager {
    private static final String TAG = "TtsQueueManager";
    private static final long POLL_INTERVAL_MS = 500; // 500ms轮询间隔
    private static final String SERVER_URL = "http://10.181.78.161:8090/api/tts/pull";

    private static TtsQueueManager instance;

    private final Context context;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    private TextToSpeech tts;
    private boolean isInitialized = false;
    private boolean isPolling = false;
    private String userId;
    private boolean isSpeaking = false;

    // 播放完成监听器
    private final CountDownLatch speakLatch = new CountDownLatch(1);

    private TtsQueueManager(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        initTts();
    }

    public static synchronized TtsQueueManager getInstance(Context context) {
        if (instance == null) {
            instance = new TtsQueueManager(context);
        }
        return instance;
    }

    /**
     * 初始化TTS
     */
    private void initTts() {
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.CHINESE);
                    tts.setSpeechRate(1.0f);
                    isInitialized = true;
                    Log.d(TAG, "TTS initialized successfully");
                } else {
                    Log.e(TAG, "TTS initialization failed");
                }
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isSpeaking = true;
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking = false;
                speakLatch.countDown();
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS error for utterance: " + utteranceId);
                isSpeaking = false;
                speakLatch.countDown();
            }
        });
    }

    /**
     * 设置用户ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 启动轮询
     */
    public void startPolling() {
        if (isPolling) {
            Log.w(TAG, "Already polling");
            return;
        }

        isPolling = true;
        scheduler.scheduleAtFixedRate(() -> {
            if (isInitialized && !isSpeaking) {
                pullAndSpeakMessages();
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Log.d(TAG, "Started polling for TTS messages");
    }

    /**
     * 停止轮询
     */
    public void stopPolling() {
        isPolling = false;
        if (tts != null) {
            tts.stop();
        }
        Log.d(TAG, "Stopped polling");
    }

    /**
     * 拉取并播放消息
     */
    private void pullAndSpeakMessages() {
        if (userId == null) {
            return;
        }

        try {
            // 构造请求
            JSONObject request = new JSONObject();
            request.put("user_id", userId);
            request.put("limit", 5);

            RequestBody body = RequestBody.create(
                    request.toString(),
                    MediaType.parse("application/json"));

            Request httpRequest = new Request.Builder()
                    .url(SERVER_URL)
                    .post(body)
                    .build();

            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    Log.e(TAG, "Failed to pull TTS messages", e);
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            List<TtsMessage> messages = parseMessages(responseBody);
                            for (TtsMessage message : messages) {
                                speakAndWait(message.content);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing TTS response", e);
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "Error building request", e);
        }
    }

    /**
     * 解析消息列表
     */
    private List<TtsMessage> parseMessages(String json) throws JSONException {
        List<TtsMessage> messages = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("messages");

        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                TtsMessage message = new TtsMessage();
                message.content = obj.optString("content", "");
                message.priority = obj.optInt("priority", 1);
                message.source = obj.optString("source", "");
                messages.add(message);
            }
        }

        return messages;
    }

    /**
     * 播报并等待完成
     */
    private void speakAndWait(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        speakLatch.countDown(); // 清除之前的latch
        try {
            speakLatch.await();     // 等待之前的播报完成
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for previous TTS", e);
            Thread.currentThread().interrupt();
            return;
        }

        // 重置latch
        // speakLatch = new CountDownLatch(1);

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_id");
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, null);

        try {
            // 等待当前播报完成
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for TTS", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 直接播报文本（不通过队列）
     */
    public void speakImmediately(String text) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized");
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    /**
     * 释放资源
     */
    public void release() {
        stopPolling();
        scheduler.shutdown();
        if (tts != null) {
            tts.shutdown();
        }
    }

    /**
     * TTS消息类
     */
    public static class TtsMessage {
        public String id;
        public String userId;
        public String content;
        public int priority; // 0=LOW, 1=NORMAL, 2=HIGH, 3=CRITICAL
        public String source;
        public long timestamp;

        public TtsMessage() {
        }

        public TtsMessage(String content, int priority, String source) {
            this.content = content;
            this.priority = priority;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
