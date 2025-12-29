package com.blindassist.server.service;

import com.blindassist.server.client.PythonAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentService {

    // 存储 "App Session ID" -> "Python Client" 的映射
    private final Map<String, PythonAgentClient> pythonClients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Python 服务的地址 (注意修改 IP 为实际 Python 服务所在 IP)
    private static final String PYTHON_SERVER_BASE_URI = "ws://10.25.144.51:8080/ws/agent/";

    /**
     * 启动任务：建立与 Python 的连接，并发送第一帧
     */
    public void startTask(String sessionId, WebSocketSession appSession, String task, String screenshot, String screenInfo) {
        try {
            // 1. 根据 App 的 SessionID 构造 Python 服务的唯一连接地址
            URI uri = new URI(PYTHON_SERVER_BASE_URI + sessionId);

            // 2. 创建 Client 并定义回调：收到 Python 消息 -> 转发给 App
            PythonAgentClient pythonClient = new PythonAgentClient(uri, messageFromPython -> {
                sendMessageToApp(appSession, messageFromPython);
            });

            // 3. 建立连接 (阻塞等待连接成功，避免发送消息时连接未就绪)
            pythonClient.connectBlocking();
            pythonClients.put(sessionId, pythonClient);

            // 4. 发送初始化消息 (Init) 给 Python
            Map<String, Object> initPayload = Map.of(
                    "type", "init",
                    "task", task,
                    "screenshot", screenshot,
                    "screen_info", screenInfo != null ? screenInfo : ""
            );
            pythonClient.send(objectMapper.writeValueAsString(initPayload));

        } catch (Exception e) {
            e.printStackTrace();
            sendErrorToApp(appSession, "无法连接 AI 模型服务: " + e.getMessage());
        }
    }

    /**
     * 处理后续步骤：转发 App 的截图给 Python
     */
    public void processStep(String sessionId, String screenshot, String screenInfo) {
        PythonAgentClient client = pythonClients.get(sessionId);
        if (client != null && client.isOpen()) {
            try {
                Map<String, Object> stepPayload = Map.of(
                        "type", "step",
                        "screenshot", screenshot,
                        "screen_info", screenInfo != null ? screenInfo : ""
                );
                client.send(objectMapper.writeValueAsString(stepPayload));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 清理资源：断开 Python 连接
     */
    public void cleanup(String sessionId) {
        PythonAgentClient client = pythonClients.remove(sessionId);
        if (client != null) {
            client.close();
        }
    }

    // --- 辅助方法 ---

    private void sendMessageToApp(WebSocketSession session, String message) {
        if (session.isOpen()) {
            synchronized (session) { // 防止并发写入冲突
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void sendErrorToApp(WebSocketSession session, String errorMsg) {
        sendMessageToApp(session, "{\"status\":\"error\", \"message\":\"" + errorMsg + "\"}");
    }
}