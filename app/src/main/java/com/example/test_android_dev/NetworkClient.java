package com.example.test_android_dev;

import android.content.Context;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 封装与后端接口通信（HTTP + WebSocket）
 */
public class NetworkClient {
    private static NetworkClient instance;
    private OkHttpClient client;
    private static final String BASE_URL = "http://your-backend-api.com";
    private static final String WS_URL = "ws://your-backend-api.com/ws/obstacle";
    private WebSocket webSocket;

    private NetworkClient() {
        client = new OkHttpClient();
    }

    public static synchronized NetworkClient getInstance() {
        if (instance == null) {
            instance = new NetworkClient();
        }
        return instance;
    }

    public void init(Context context) {
        // 初始化逻辑，如配置缓存、超时等
    }

    public void sendVoiceCommand(String text, Callback callback) {
        String json = "{\"text\":\"" + text + "\"}";
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/voice/command")
                .post(body)
                .build();
        client.newCall(request).enqueue(callback);
    }

    public void requestNavigation(double startLat, double startLng, double endLat, double endLng, String description, Callback callback) {
        String json = String.format("{\"startLat\":%f, \"startLng\":%f, \"endLat\":%f, \"endLng\":%f, \"description\":\"%s\"}",
                startLat, startLng, endLat, endLng, description);
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/navigation/route")
                .post(body)
                .build();
        client.newCall(request).enqueue(callback);
    }

    public void askQuestion(String question, String sessionId, Callback callback) {
        String json = String.format("{\"question\":\"%s\", \"sessionId\":\"%s\"}", question, sessionId != null ? sessionId : "");
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/qa/ask")
                .post(body)
                .build();
        client.newCall(request).enqueue(callback);
    }

    public void uploadVisionRequest(String endpoint, byte[] imageData, Callback callback) {
        RequestBody body = RequestBody.create(imageData, MediaType.parse("application/octet-stream"));
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/vision/" + endpoint)
                .post(body)
                .build();
        client.newCall(request).enqueue(callback);
    }

    public void openObstacleWebSocket(WebSocketListener listener) {
        Request request = new Request.Builder().url(WS_URL).build();
        webSocket = client.newWebSocket(request, listener);
    }

    public void sendFrameViaWS(byte[] data) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(data));
        }
    }
