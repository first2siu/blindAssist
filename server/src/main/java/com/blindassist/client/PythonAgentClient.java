package com.blindassist.server.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 负责与 Python AutoGLM 服务维持长连接
 */
public class PythonAgentClient extends WebSocketClient {

    private final Consumer<String> onMessageCallback;

    public PythonAgentClient(URI serverUri, Consumer<String> onMessageCallback) {
        super(serverUri, new HashMap<>());
        this.onMessageCallback = onMessageCallback;

        // 设置连接超时: 连接建立最多等待30秒
        this.setConnectionLostTimeout(120); // 120秒无数据则认为连接丢失

        // 设置WebSocket握手超时
        // 如需自定义headers，请在super构造函数调用时传入
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[PythonAgentClient] 已连接到 Python 模型服务: " + getURI());
    }

    @Override
    public void onMessage(String message) {
        // 收到 Python 的回复，触发回调传回给 AgentService
        System.out.println("[PythonAgentClient] 收到消息，长度: " + message.length() + " 字符");
        if (onMessageCallback != null) {
            onMessageCallback.accept(message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[PythonAgentClient] 连接已断开 - code: " + code + ", reason: " + reason + ", remote: " + remote);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[PythonAgentClient] 连接错误: " + ex.getClass().getName() + " - " + ex.getMessage());
        ex.printStackTrace();
    }
}