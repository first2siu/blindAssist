package com.example.test_android_dev.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.test_android_dev.model.ConnectionState;
import com.example.test_android_dev.model.ConnectionStatus;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket连接管理器
 * 负责维护与服务器的长连接，包含心跳和重连机制
 */
public class WebSocketConnectionManager {
    private static final String TAG = "WebSocketConnManager";

    // 配置常量
    public static final long HEARTBEAT_INTERVAL_MS = 30_000;  // 30秒心跳间隔
    public static final long HEARTBEAT_TIMEOUT_MS = 10_000;   // 10秒心跳超时
    public static final int MAX_RECONNECT_ATTEMPTS = 5;       // 最大重连次数
    public static final long[] RECONNECT_DELAYS_MS = {1000, 2000, 4000, 8000, 30000}; // 指数退避延迟

    private static WebSocketConnectionManager instance;

    private OkHttpClient client;
    private WebSocket webSocket;
    private String serverUrl;
    private ConnectionCallback callback;
    private ConnectionStatus connectionStatus;

    // 心跳相关
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> heartbeatTimeoutFuture;
    private boolean awaitingHeartbeatResponse;

    // 重连相关
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;
    private boolean isReconnecting;

    /**
     * 连接回调接口
     */
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onReconnecting(int attempt, long nextRetryMs);
        void onReconnectFailed();
        void onMessage(String message);
        void onConnectionStateChanged(ConnectionState state);
    }

    private WebSocketConnectionManager() {
        client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS) // 禁用OkHttp自带ping，使用自定义心跳
                .build();
        connectionStatus = new ConnectionStatus();
        reconnectHandler = new Handler(Looper.getMainLooper());
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    }

    public static synchronized WebSocketConnectionManager getInstance() {
        if (instance == null) {
            instance = new WebSocketConnectionManager();
        }
        return instance;
    }

    /**
     * 连接到WebSocket服务器
     */
    public void connect(String url, ConnectionCallback callback) {
        this.serverUrl = url;
        this.callback = callback;
        this.isReconnecting = false;

        updateConnectionState(ConnectionState.CONNECTING);
        connectionStatus.resetReconnectAttempts();

        doConnect();
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        Log.d(TAG, "主动断开连接");
        isReconnecting = false;
        stopHeartbeat();
        cancelReconnect();

        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }

        updateConnectionState(ConnectionState.DISCONNECTED);
    }

    /**
     * 发送消息
     */
    public boolean send(String message) {
        if (webSocket != null && connectionStatus.getState() == ConnectionState.CONNECTED) {
            return webSocket.send(message);
        }
        Log.w(TAG, "无法发送消息，连接未建立");
        return false;
    }

    /**
     * 获取当前连接状态
     */
    public ConnectionState getConnectionState() {
        return connectionStatus.getState();
    }

    /**
     * 获取详细连接状态
     */
    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    /**
     * 检查连接是否健康
     */
    public boolean isHealthy() {
        return connectionStatus.isHealthy();
    }

    /**
     * 检查连接健康状态并在需要时重连
     */
    public void checkAndReconnectIfNeeded() {
        if (connectionStatus.getState() == ConnectionState.CONNECTED && !isHealthy()) {
            Log.w(TAG, "连接不健康，触发重连");
            triggerReconnect("Connection unhealthy");
        } else if (connectionStatus.getState() == ConnectionState.DISCONNECTED && serverUrl != null) {
            Log.d(TAG, "连接已断开，触发重连");
            scheduleReconnect(0);
        }
    }

    // ==================== 内部方法 ====================

    private void doConnect() {
        Request request = new Request.Builder().url(serverUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket连接成功");
                connectionStatus.resetReconnectAttempts();
                connectionStatus.updateHeartbeat();
                updateConnectionState(ConnectionState.CONNECTED);
                startHeartbeat();

                if (callback != null) {
                    callback.onConnected();
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                // 任何消息都表示连接活跃，更新心跳时间
                connectionStatus.updateHeartbeat();

                // 检查是否是心跳响应
                if (isHeartbeatResponse(text)) {
                    handleHeartbeatResponse();
                    return;
                }

                if (callback != null) {
                    callback.onMessage(text);
                }
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket已关闭: " + reason);
                handleDisconnect(reason);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                Log.e(TAG, "WebSocket连接失败", t);
                connectionStatus.setLastError(t.getMessage());
                handleDisconnect(t.getMessage());
            }
        });
    }

    private void handleDisconnect(String reason) {
        stopHeartbeat();
        updateConnectionState(ConnectionState.DISCONNECTED);

        if (callback != null) {
            callback.onDisconnected(reason);
        }

        // 如果不是主动断开，尝试重连
        if (!isReconnecting && serverUrl != null) {
            triggerReconnect(reason);
        }
    }

    private void triggerReconnect(String reason) {
        if (connectionStatus.getReconnectAttempts() >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数，停止重连");
            if (callback != null) {
                callback.onReconnectFailed();
            }
            return;
        }

        scheduleReconnect(connectionStatus.getReconnectAttempts());
    }

    /**
     * 计算重连延迟（指数退避）
     */
    public static long calculateReconnectDelay(int attempt) {
        if (attempt < 0) return RECONNECT_DELAYS_MS[0];
        if (attempt >= RECONNECT_DELAYS_MS.length) {
            return RECONNECT_DELAYS_MS[RECONNECT_DELAYS_MS.length - 1];
        }
        return RECONNECT_DELAYS_MS[attempt];
    }

    private void scheduleReconnect(int attempt) {
        isReconnecting = true;
        long delay = calculateReconnectDelay(attempt);
        connectionStatus.setNextRetryTime(System.currentTimeMillis() + delay);
        connectionStatus.incrementReconnectAttempts();

        updateConnectionState(ConnectionState.RECONNECTING);

        Log.d(TAG, "计划重连，第" + connectionStatus.getReconnectAttempts() + "次，延迟" + delay + "ms");

        if (callback != null) {
            callback.onReconnecting(connectionStatus.getReconnectAttempts(), delay);
        }

        cancelReconnect();
        reconnectRunnable = () -> {
            Log.d(TAG, "执行重连...");
            updateConnectionState(ConnectionState.CONNECTING);
            doConnect();
        };
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    // ==================== 心跳相关 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        Log.d(TAG, "启动心跳，间隔" + HEARTBEAT_INTERVAL_MS + "ms");

        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatTimeoutFuture != null) {
            heartbeatTimeoutFuture.cancel(false);
            heartbeatTimeoutFuture = null;
        }
        awaitingHeartbeatResponse = false;
    }

    private void sendHeartbeat() {
        if (webSocket == null || connectionStatus.getState() != ConnectionState.CONNECTED) {
            return;
        }

        Log.d(TAG, "发送心跳");
        awaitingHeartbeatResponse = true;

        // 发送心跳消息
        String heartbeatMsg = "{\"type\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}";
        webSocket.send(heartbeatMsg);

        // 设置心跳超时检测
        heartbeatTimeoutFuture = heartbeatExecutor.schedule(
                this::handleHeartbeatTimeout,
                HEARTBEAT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private boolean isHeartbeatResponse(String message) {
        return message.contains("\"type\":\"heartbeat\"") || message.contains("\"type\":\"pong\"");
    }

    private void handleHeartbeatResponse() {
        Log.d(TAG, "收到心跳响应");
        awaitingHeartbeatResponse = false;
        connectionStatus.updateHeartbeat();

        if (heartbeatTimeoutFuture != null) {
            heartbeatTimeoutFuture.cancel(false);
            heartbeatTimeoutFuture = null;
        }
    }

    private void handleHeartbeatTimeout() {
        if (!awaitingHeartbeatResponse) {
            return;
        }

        Log.w(TAG, "心跳超时，连接可能不健康");
        awaitingHeartbeatResponse = false;

        // 标记连接不健康，但不立即断开
        // 下一次心跳如果还是超时，才触发重连
        if (!isHealthy()) {
            Log.w(TAG, "连接持续不健康，触发重连");
            if (webSocket != null) {
                webSocket.close(1000, "Heartbeat timeout");
            }
        }
    }

    private void updateConnectionState(ConnectionState state) {
        connectionStatus.setState(state);
        Log.d(TAG, "连接状态更新: " + state.getDisplayName());

        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    callback.onConnectionStateChanged(state)
            );
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        disconnect();
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdown();
        }
    }
}
