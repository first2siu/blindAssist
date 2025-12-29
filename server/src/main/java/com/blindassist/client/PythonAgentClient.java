package com.blindassist.server.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.function.Consumer;

/**
 * 负责与 Python AutoGLM 服务维持长连接
 */
public class PythonAgentClient extends WebSocketClient {

    private final Consumer<String> onMessageCallback;

    public PythonAgentClient(URI serverUri, Consumer<String> onMessageCallback) {
        super(serverUri);
        this.onMessageCallback = onMessageCallback;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("已连接到 Python 模型服务: " + getURI());
    }

    @Override
    public void onMessage(String message) {
        // 收到 Python 的回复，触发回调传回给 AgentService
        if (onMessageCallback != null) {
            onMessageCallback.accept(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("Python 模型连接已断开: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("Python 连接错误: " + ex.getMessage());
    }
}