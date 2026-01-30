package com.example.test_android_dev.navigation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 障碍检测客户端
 * 通过WebSocket将摄像头帧发送到Spring Boot，然后转发到Redis TTS队列
 *
 * 架构更新：
 * - 不再直接播报避障警告
 * - 所有TTS消息通过Redis队列统一管理
 */
public class ObstacleDetectionClient {
    private static final String TAG = "ObstacleDetectionClient";

    private static ObstacleDetectionClient instance;

    private final Context context;
    private final OkHttpClient httpClient;

    private WebSocket obstacleWebSocket;
    private boolean isConnected = false;
    private String userId;
    private String wsUrl;

    private ObstacleCallback callback;

    public interface ObstacleCallback {
        void onObstacleDetected(String warning, String urgency);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private ObstacleDetectionClient(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized ObstacleDetectionClient getInstance(Context context) {
        if (instance == null) {
            instance = new ObstacleDetectionClient(context);
        }
        return instance;
    }

    /**
     * 设置障碍检测回调
     */
    public void setCallback(ObstacleCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置用户ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 连接到指定URL的WebSocket服务
     */
    public void connect(String url) {
        if (isConnected) {
            Log.w(TAG, "Already connected");
            return;
        }

        this.wsUrl = url;
        Log.i(TAG, "Connecting to obstacle detection service: " + url);
        Log.i(TAG, "User ID for this session: " + (userId != null ? userId : "default"));

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            obstacleWebSocket = httpClient.newWebSocket(request, new ObstacleWebSocketListener());

        } catch (Exception e) {
            Log.e(TAG, "Failed to connect obstacle WebSocket", e);
            if (callback != null) {
                callback.onError("连接避障服务失败: " + e.getMessage());
            }
        }
    }

    /**
     * 使用默认URL连接（已弃用，请使用 connect(String url)）
     */
    @Deprecated
    public void connect() {
        connect(wsUrl != null ? wsUrl : "ws://10.181.78.161:8090/ws/obstacle");
    }

    /**
     * 注册用户（连接成功后调用）
     */
    public void register() {
        if (!isConnected || obstacleWebSocket == null) {
            Log.w(TAG, "Cannot register: not connected");
            return;
        }

        try {
            JSONObject registerMsg = new JSONObject();
            registerMsg.put("type", "register");
            registerMsg.put("user_id", userId != null ? userId : "android_user_default");
            obstacleWebSocket.send(registerMsg.toString());
            Log.d(TAG, "Sent register message for user: " + userId);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send register message", e);
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (!isConnected) {
            return;
        }

        Log.d(TAG, "Disconnecting obstacle detection");

        if (obstacleWebSocket != null) {
            obstacleWebSocket.close(1000, "User disconnect");
            obstacleWebSocket = null;
        }

        isConnected = false;

        if (callback != null) {
            callback.onDisconnected();
        }
    }

    /**
     * 发送摄像头帧
     * @param bitmap 摄像头捕获的图像
     * @param sensorData 当前传感器数据
     */
    public void sendFrame(Bitmap bitmap, SensorCollector.SensorData sensorData) {
        if (!isConnected) {
            Log.w(TAG, "sendFrame skipped: not connected (isConnected=" + isConnected + "), ws state: " +
                    (obstacleWebSocket != null ? "exists" : "null"));
            return;
        }
        if (obstacleWebSocket == null) {
            Log.w(TAG, "sendFrame skipped: obstacleWebSocket is null (should not happen if isConnected=true)");
            return;
        }

        try {
            // 压缩图像 - 使用稍高的分辨率和质量以获得更好的检测效果
            Bitmap resizedBitmap = resizeBitmap(bitmap, 800, 600);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream);

            byte[] imageBytes = outputStream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            // 构造消息
            JSONObject message = new JSONObject();
            message.put("type", "frame");
            message.put("user_id", userId != null ? userId : "default");
            message.put("frame_data", base64Image);
            message.put("timestamp", System.currentTimeMillis());

            // 添加传感器数据
            if (sensorData != null && sensorData.isValid()) {
                JSONObject sensorJson = new JSONObject();
                sensorJson.put("latitude", sensorData.latitude);
                sensorJson.put("longitude", sensorData.longitude);
                sensorJson.put("heading", sensorData.heading);
                sensorJson.put("altitude", sensorData.altitude);
                message.put("sensor_data", sensorJson);
            }

            obstacleWebSocket.send(message.toString());
            Log.d(TAG, "Frame sent successfully, base64 size: " + base64Image.length() +
                    " chars, message size: " + message.length() + " chars, sensor_data: " +
                    (sensorData != null ? "included" : "null"));

        } catch (Exception e) {
            Log.e(TAG, "Failed to send frame", e);
        }
    }

    /**
     * 调整图像大小
     */
    private Bitmap resizeBitmap(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        float ratio = Math.min(
                (float) maxWidth / width,
                (float) maxHeight / height
        );

        int newWidth = Math.round(width * ratio);
        int newHeight = Math.round(height * ratio);

        Matrix matrix = new Matrix();
        matrix.postScale(newWidth / (float) width, newHeight / (float) height);

        Bitmap resizedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, width, height, matrix, true);

        return resizedBitmap;
    }

    /**
     * 发送心跳保活
     */
    public void sendHeartbeat() {
        if (!isConnected || obstacleWebSocket == null) {
            return;
        }

        try {
            JSONObject message = new JSONObject();
            message.put("type", "keep_alive");
            message.put("user_id", userId != null ? userId : "default");
            message.put("timestamp", System.currentTimeMillis());

            obstacleWebSocket.send(message.toString());

        } catch (JSONException e) {
            Log.e(TAG, "Failed to send heartbeat", e);
        }
    }

    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * 障碍检测WebSocket监听器
     */
    private class ObstacleWebSocketListener extends WebSocketListener {
            @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "Obstacle WebSocket connected, response: " + response.code());
            isConnected = true;

            // 启动TTS轮询服务（因为避障警告通过Spring Boot TTS队列播报）
            TtsPollingService ttsPolling = TtsPollingService.getInstance(context);
            ttsPolling.startPolling();
            Log.d(TAG, "TTS polling started for obstacle detection");

            // 发送注册消息
            register();

            if (callback != null) {
                callback.onConnected();
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "Received obstacle message: " + text);

            try {
                JSONObject message = new JSONObject(text);
                String type = message.optString("type");

                if ("obstacle".equals(type)) {
                    String warning = message.optString("warning", "");
                    String urgency = message.optString("urgency", "normal");

                    // 不再直接播报，消息已通过 Spring Boot 加入 Redis 队列
                    Log.i(TAG, "Obstacle warning queued to Redis: " + warning + ", urgency: " + urgency);

                    if (callback != null) {
                        callback.onObstacleDetected(warning, urgency);
                    }
                } else if ("error".equals(type)) {
                    String error = message.optString("message", "未知错误");
                    if (callback != null) {
                        callback.onError(error);
                    }
                } else if ("connected".equals(type)) {
                    Log.d(TAG, "Registration confirmed");
                }

            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse obstacle message", e);
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "Obstacle WebSocket closed: " + reason);
            isConnected = false;
            obstacleWebSocket = null;

            if (callback != null) {
                callback.onDisconnected();
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            String errorMsg = "WebSocket failed: " + t.getClass().getSimpleName() + " - " + t.getMessage();
            if (response != null) {
                errorMsg += ", response code: " + response.code() + ", message: " + response.message();
            }
            Log.e(TAG, "Obstacle WebSocket failed: " + errorMsg, t);
            isConnected = false;
            obstacleWebSocket = null;

            if (callback != null) {
                callback.onError("避障连接失败: " + t.getMessage());
            }
        }
    }
}
