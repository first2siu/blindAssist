package com.example.test_android_dev.navigation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.test_android_dev.Config;
import com.example.test_android_dev.VoiceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * TTS 轮询服务
 * 从 Spring Boot 服务器拉取 TTS 消息并通过 VoiceManager 播报
 *
 * 架构：
 * Android 轮询 → Spring Boot /api/tts/pull → Redis 优先级队列
 * FastAPI 避障 → Spring Boot /api/tts/enqueue → 入队
 *
 * 播报策略：
 * - 空闲时每 1-2 秒轮询一次
 * - 播报中停止轮询
 * - 播报完成后恢复轮询
 * - CRITICAL 优先级消息打断当前播报
 */
public class TtsPollingService {
    private static final String TAG = "TtsPollingService";
    private static final long POLL_INTERVAL_MS = 1500; // 1.5秒轮询间隔

    private static TtsPollingService instance;

    private final Context context;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Handler mainHandler;

    private boolean isPolling = false;
    private boolean isSpeaking = false;
    private String userId;

    // 当前正在播报的消息（用于被打断时保存）
    private TtsMessage currentMessage;
    // 待播报的消息队列
    private final java.util.List<TtsMessage> localQueue = new java.util.ArrayList<>();

    private TtsPollingService(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.userId = Config.USER_ID;
    }

    public static synchronized TtsPollingService getInstance() {
        return instance;
    }

    public static synchronized TtsPollingService getInstance(Context context) {
        if (instance == null) {
            instance = new TtsPollingService(context);
        }
        return instance;
    }

    /**
     * 设置用户 ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
        Log.d(TAG, "User ID set to: " + userId);
    }

    /**
     * 启动轮询
     */
    public void startPolling() {
        if (isPolling) {
            Log.w(TAG, "Already polling");
            return;
        }

        if (scheduler == null || scheduler.isShutdown()) {
            Log.e(TAG, "Scheduler is not available, cannot start polling");
            return;
        }

        isPolling = true;
        try {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!isSpeaking && isPolling) {
                        pullMessages();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in polling loop", e);
                }
            }, 500, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);  // 延迟500ms启动

            Log.d(TAG, "Started polling for TTS messages");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start polling", e);
            isPolling = false;
        }
    }

    /**
     * 停止轮询
     */
    public void stopPolling() {
        isPolling = false;
        Log.d(TAG, "Stopped polling");
    }

    /**
     * 拉取消息
     */
    private void pullMessages() {
        if (userId == null) {
            Log.w(TAG, "userId is null, skipping pull");
            return;
        }

        if (httpClient == null) {
            Log.e(TAG, "httpClient is null, cannot pull messages");
            return;
        }

        try {
            // 构造请求
            JSONObject request = new JSONObject();
            request.put("user_id", userId);
            request.put("limit", 5);

            RequestBody body = RequestBody.create(
                    request.toString(),
                    MediaType.parse("application/json; charset=utf-8"));

            Request httpRequest = new Request.Builder()
                    .url(Config.TTS_PULL_URL)
                    .post(body)
                    .build();

            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    Log.e(TAG, "Failed to pull TTS messages: " + e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            parseAndEnqueueMessages(responseBody);
                        } else if (response.code() >= 400) {
                            Log.w(TAG, "TTS pull failed with status: " + response.code());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing TTS response", e);
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error building/pulling TTS request", e);
        }
    }

    /**
     * 解析消息并入队
     */
    private void parseAndEnqueueMessages(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("messages");

        if (array != null && array.length() > 0) {
            synchronized (localQueue) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    TtsMessage message = new TtsMessage();
                    message.id = obj.optString("id", "");
                    message.content = obj.optString("content", "");
                    message.priority = parsePriority(obj.optString("priority", "NORMAL"));
                    message.source = obj.optString("source", "");
                    message.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
                    localQueue.add(message);
                }
            }
            Log.d(TAG, "Enqueued " + array.length() + " messages");
            // 开始处理队列
            processQueue();
        }
    }

    /**
     * 解析优先级
     */
    private TtsPriority parsePriority(String priorityStr) {
        if (priorityStr == null) {
            return TtsPriority.NORMAL;
        }
        try {
            return TtsPriority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TtsPriority.NORMAL;
        }
    }

    /**
     * 处理本地队列
     */
    private void processQueue() {
        if (isSpeaking) {
            return; // 正在播报，等待完成
        }

        synchronized (localQueue) {
            if (localQueue.isEmpty()) {
                return;
            }

            // 获取最高优先级的消息
            TtsMessage message = getHighestPriorityMessage();
            if (message != null) {
                localQueue.remove(message);
                speakMessage(message);
            }
        }
    }

    /**
     * 获取最高优先级的消息
     */
    private TtsMessage getHighestPriorityMessage() {
        TtsMessage highest = null;
        for (TtsMessage msg : localQueue) {
            if (highest == null || msg.priority.compareTo(highest.priority) > 0) {
                highest = msg;
            }
        }
        return highest;
    }

    /**
     * 播报消息
     */
    private void speakMessage(TtsMessage message) {
        if (message.content == null || message.content.isEmpty()) {
            return;
        }

        // 如果正在播报且新消息优先级更高，打断当前播报
        if (isSpeaking && message.priority == TtsPriority.CRITICAL) {
            // 先保存当前消息（在 currentMessage 被覆盖之前）
            TtsMessage interruptedMessage = currentMessage;
            saveCurrentInterruptedMessage();
            // 停止当前播报并播放紧急消息
            VoiceManager.getInstance().speakImmediate(message.content, () -> {
                isSpeaking = false;
                // 恢复被打断的消息
                restoreInterruptedMessage();
                // 继续处理队列
                mainHandler.post(this::processQueue);
            });
        } else if (!isSpeaking) {
            isSpeaking = true;
            currentMessage = message;
            VoiceManager.getInstance().speak(message.content, () -> {
                isSpeaking = false;
                currentMessage = null;
                // 继续处理队列
                mainHandler.post(this::processQueue);
            });
        }
    }

    /**
     * 保存当前被打断的消息到服务器
     */
    private void saveCurrentInterruptedMessage() {
        if (currentMessage == null) {
            return;
        }

        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("user_id", userId);
                request.put("message", currentMessage.content);
                request.put("priority", currentMessage.priority.name());
                request.put("source", currentMessage.source);

                RequestBody body = RequestBody.create(
                        request.toString(),
                        MediaType.parse("application/json; charset=utf-8"));

                Request httpRequest = new Request.Builder()
                        .url(Config.TTS_INTERRUPT_URL)
                        .post(body)
                        .build();

                httpClient.newCall(httpRequest).execute();

                Log.d(TAG, "Saved interrupted message: " + currentMessage.content);

            } catch (Exception e) {
                Log.e(TAG, "Failed to save interrupted message", e);
            }
        }).start();
    }

    /**
     * 恢复被打断的消息
     */
    private void restoreInterruptedMessage() {
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("user_id", userId);

                RequestBody body = RequestBody.create(
                        request.toString(),
                        MediaType.parse("application/json; charset=utf-8"));

                Request httpRequest = new Request.Builder()
                        .url(Config.TTS_RESUME_URL)
                        .post(body)
                        .build();

                httpClient.newCall(httpRequest).execute();

                Log.d(TAG, "Requested to restore interrupted message");

            } catch (Exception e) {
                Log.e(TAG, "Failed to restore interrupted message", e);
            }
        }).start();
    }

    /**
     * 释放资源
     */
    public void release() {
        stopPolling();
        scheduler.shutdown();
    }

    // ==================== 内部类 ====================

    /**
     * TTS 优先级枚举
     */
    public enum TtsPriority {
        CRITICAL(3, "紧急避障"),
        HIGH(2, "一般避障"),
        NORMAL(1, "导航指令"),
        LOW(0, "普通提示");

        private final int value;
        private final String description;

        TtsPriority(int value, String description) {
            this.value = value;
            this.description = description;
        }

        public int getValue() {
            return value;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * TTS 消息类
     */
    public static class TtsMessage {
        public String id;
        public String userId;
        public String content;
        public TtsPriority priority;
        public String source;
        public long timestamp;

        public TtsMessage() {
            this.priority = TtsPriority.NORMAL;
        }

        public TtsMessage(String content, TtsPriority priority, String source) {
            this.content = content;
            this.priority = priority;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
