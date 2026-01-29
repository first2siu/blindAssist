package com.blindassist.server.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 导航客户端：连接到 FastAPI 的导航 WebSocket 服务
 */
public class NavigationAgentClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(NavigationAgentClient.class);

    private final Consumer<String> onMessageCallback;

    public NavigationAgentClient(URI serverUri, Consumer<String> onMessageCallback) {
        super(serverUri, new HashMap<>());
        this.onMessageCallback = onMessageCallback;

        // 设置连接超时
        this.setConnectionLostTimeout(120);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("[NavigationAgentClient] 已连接到导航服务: {}", getURI());
    }

    @Override
    public void onMessage(String message) {
        log.debug("[NavigationAgentClient] 收到消息，长度: {}", message.length());
        if (onMessageCallback != null) {
            onMessageCallback.accept(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("[NavigationAgentClient] 连接已断开 - code: {}, reason: {}, remote: {}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.error("[NavigationAgentClient] 连接错误: {} - {}", ex.getClass().getName(), ex.getMessage());
        ex.printStackTrace();
    }
}
