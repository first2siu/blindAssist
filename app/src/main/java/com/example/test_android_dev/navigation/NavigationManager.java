package com.example.test_android_dev.navigation;

import android.content.Context;
import android.util.Log;

import com.example.test_android_dev.manager.WebSocketConnectionManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 导航管理器
 * 负责导航功能的启动、停止和WebSocket通信
 */
public class NavigationManager {
    private static final String TAG = "NavigationManager";
    private static final String NAV_WS_URL = "ws://10.184.17.161:8081/ws/navigation/";

    private static NavigationManager instance;

    private final Context context;
    private final SensorCollector sensorCollector;
    private final OkHttpClient httpClient;
    private final WebSocketConnectionManager wsManager;

    private boolean isNavigating = false;
    private String userId;
    private WebSocket navigationWebSocket;

    private NavigationCallback callback;
    private TtsPollingService ttsPollingService;

    public interface NavigationCallback {
        void onNavigationStarted(String destination);
        void onNavigationStopped();
        void onInstructionReceived(String instruction);
        void onError(String error);
    }

    private NavigationManager(Context context) {
        this.context = context.getApplicationContext();
        this.sensorCollector = new SensorCollector(context);
        this.wsManager = WebSocketConnectionManager.getInstance();
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        // 设置传感器数据变化回调
        this.sensorCollector.setCallback(sensorData -> {
            if (isNavigating) {
                sendSensorUpdate(sensorData);
            }
        });
    }

    public static synchronized NavigationManager getInstance(Context context) {
        if (instance == null) {
            instance = new NavigationManager(context);
        }
        return instance;
    }

    /**
     * 设置导航回调
     */
    public void setCallback(NavigationCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置用户ID
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 启动导航
     * @param destination 目的地
     */
    public void startNavigation(String destination) {
        if (isNavigating) {
            Log.w(TAG, "Navigation already in progress");
            return;
        }

        Log.d(TAG, "Starting navigation to: " + destination);
        isNavigating = true;

        // 启动传感器收集
        sensorCollector.start();

        // 等待 GPS 获取有效位置后再连接 WebSocket
        waitForValidLocationAndConnect(destination);
    }

    /**
     * 等待 GPS 获取有效位置后再连接导航服务
     */
    private void waitForValidLocationAndConnect(String destination) {
        final int MAX_WAIT_TIME_MS = 10000; // 最多等待 10 秒
        final int CHECK_INTERVAL_MS = 500;  // 每 500ms 检查一次

        new Thread(() -> {
            int elapsedTime = 0;
            SensorCollector.SensorData sensorData;

            Log.d(TAG, "等待 GPS 获取有效位置...");

            while (elapsedTime < MAX_WAIT_TIME_MS) {
                sensorData = sensorCollector.getCurrentData();
                if (sensorData.isValid()) {
                    Log.d(TAG, "GPS 位置已获取: " + sensorData);
                    // 在主线程连接 WebSocket
                    connectNavigationWebSocket(destination, sensorData);
                    return;
                }

                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "GPS 等待被中断", e);
                    break;
                }
                elapsedTime += CHECK_INTERVAL_MS;
            }

            // 超时：使用当前位置连接（可能是无效位置）
            Log.w(TAG, "GPS 获取超时，使用当前位置连接");
            sensorData = sensorCollector.getCurrentData();
            connectNavigationWebSocket(destination, sensorData);
        }, "GPS-Wait-Thread").start();
    }

    /**
     * 停止导航
     */
    public void stopNavigation() {
        if (!isNavigating) {
            return;
        }

        Log.d(TAG, "Stopping navigation");
        isNavigating = false;

        // 停止传感器收集
        sensorCollector.stop();

        // 停止 TTS 轮询
        if (ttsPollingService != null) {
            ttsPollingService.stopPolling();
            Log.d(TAG, "TTS polling stopped");
        }

        // 断开WebSocket
        if (navigationWebSocket != null) {
            navigationWebSocket.close(1000, "User stopped navigation");
            navigationWebSocket = null;
        }

        wsManager.disconnect();

        if (callback != null) {
            callback.onNavigationStopped();
        }
    }

    /**
     * 连接导航WebSocket
     * @param destination 目的地
     * @param sensorData 传感器数据（已等待 GPS 获取）
     */
    private void connectNavigationWebSocket(String destination, SensorCollector.SensorData sensorData) {
        try {
            String url = NAV_WS_URL + URLEncoder.encode(userId, "UTF-8");

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            navigationWebSocket = httpClient.newWebSocket(request, new NavigationWebSocketListener());

            // 发送初始化导航消息 - 格式需与 NavigationAgent 服务匹配
            JSONObject startMessage = new JSONObject();
            startMessage.put("type", "init");
            startMessage.put("user_task", destination);
            // 可选：添加高德API Key
            startMessage.put("amap_api_key", "fed91a0ffe7891336b2398249a2faf53");

            // 使用传入的传感器数据
            if (sensorData.isValid()) {
                // origin 字段包含经纬度
                JSONObject originJson = new JSONObject();
                originJson.put("lon", sensorData.longitude);
                originJson.put("lat", sensorData.latitude);
                startMessage.put("origin", originJson);

                // sensor_data 字段包含朝向和精度
                JSONObject sensorJson = new JSONObject();
                sensorJson.put("heading", sensorData.heading);
                sensorJson.put("accuracy", sensorData.accuracy);
                startMessage.put("sensor_data", sensorJson);

                Log.i(TAG, "发送导航请求，位置: lat=" + sensorData.latitude + ", lon=" + sensorData.longitude);
            } else {
                Log.w(TAG, "GPS 数据无效，使用默认位置 (可能无法正常导航)");
                // 如果GPS数据无效，使用默认值
                JSONObject originJson = new JSONObject();
                originJson.put("lon", 0.0);
                originJson.put("lat", 0.0);
                startMessage.put("origin", originJson);

                JSONObject sensorJson = new JSONObject();
                sensorJson.put("heading", 0.0);
                sensorJson.put("accuracy", 0.0);
                startMessage.put("sensor_data", sensorJson);
            }

            navigationWebSocket.send(startMessage.toString());

            // 启动 TTS 轮询服务
            if (ttsPollingService == null) {
                ttsPollingService = TtsPollingService.getInstance(context);
            }
            ttsPollingService.startPolling();
            Log.d(TAG, "TTS polling started");

            // 通知回调（在主线程）
            if (callback != null) {
                android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                mainHandler.post(() -> callback.onNavigationStarted(destination));
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to connect navigation WebSocket", e);
            if (callback != null) {
                callback.onError("连接导航服务失败: " + e.getMessage());
            }
        }
    }

    /**
     * 发送传感器更新
     */
    private void sendSensorUpdate(SensorCollector.SensorData sensorData) {
        if (navigationWebSocket == null) {
            Log.w(TAG, "sendSensorUpdate skipped: navigationWebSocket is null");
            return;
        }

        if (!sensorData.isValid()) {
            Log.w(TAG, "sendSensorUpdate skipped: sensorData invalid (lat=" + sensorData.latitude + ", lon=" + sensorData.longitude + ")");
            return;
        }

        try {
            // 格式需与 NavigationAgent 服务的 location_update 匹配
            JSONObject message = new JSONObject();
            message.put("type", "location_update");

            // origin 字段包含经纬度
            JSONObject originJson = new JSONObject();
            originJson.put("lon", sensorData.longitude);
            originJson.put("lat", sensorData.latitude);
            message.put("origin", originJson);

            // sensor_data 字段包含朝向和精度
            JSONObject sensorJson = new JSONObject();
            sensorJson.put("heading", sensorData.heading);
            sensorJson.put("accuracy", sensorData.accuracy);
            message.put("sensor_data", sensorJson);

            navigationWebSocket.send(message.toString());
            Log.d(TAG, "位置更新已发送: lat=" + sensorData.latitude + ", lon=" + sensorData.longitude);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to send sensor update", e);
        }
    }

    /**
     * 导航WebSocket监听器
     */
    private class NavigationWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "Navigation WebSocket connected");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "Received navigation message: " + text);

            try {
                JSONObject message = new JSONObject(text);
                String status = message.optString("status");
                String type = message.optString("type");

                // 处理错误状态
                if ("error".equals(status)) {
                    String error = message.optString("message", "未知错误");
                    if (callback != null) {
                        callback.onError(error);
                    }
                    return;
                }

                // 处理不同类型的成功响应
                if ("route_planned".equals(type) || "navigation_update".equals(type)) {
                    String instruction = message.optString("instruction", "");
                    if (!instruction.isEmpty() && callback != null) {
                        callback.onInstructionReceived(instruction);
                    }
                } else if ("arrived".equals(type)) {
                    String instruction = message.optString("instruction", "已到达目的地");
                    if (callback != null) {
                        callback.onInstructionReceived(instruction);
                        // 到达后自动停止导航
                        stopNavigation();
                    }
                } else if ("off_route".equals(type)) {
                    String instruction = message.optString("instruction", "您偏离了路线");
                    if (callback != null) {
                        callback.onError(instruction);
                    }
                }

            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse navigation message", e);
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "Navigation WebSocket closed: " + reason);
            navigationWebSocket = null;
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "Navigation WebSocket failed", t);
            if (callback != null) {
                callback.onError("导航连接失败: " + t.getMessage());
            }
        }
    }

    /**
     * 获取当前传感器数据
     */
    public SensorCollector.SensorData getCurrentSensorData() {
        return sensorCollector.getCurrentData();
    }

    /**
     * 检查是否正在导航
     */
    public boolean isNavigating() {
        return isNavigating;
    }
}
