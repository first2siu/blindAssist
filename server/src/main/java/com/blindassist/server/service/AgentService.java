package com.blindassist.server.service;

import com.blindassist.server.client.PythonAgentClient;
import com.blindassist.server.config.FastApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    // 任务超时时间（毫秒）：5分钟无响应则自动停止
    private static final long TASK_TIMEOUT_MS = 5 * 60 * 1000;

    // 存储 "App Session ID" -> "Python Client" 的映射
    private final Map<String, PythonAgentClient> pythonClients = new ConcurrentHashMap<>();
    private final Map<String, Long> requestTimestamps = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> appSessions = new ConcurrentHashMap<>();  // 保存 App session 用于超时通知
    private final ObjectMapper objectMapper = new ObjectMapper();

    // WebSocket 基础地址 (从配置文件读取)
    @Value("${fastapi.websocket.base-url:ws://10.184.17.161:8080/ws/agent/}")
    private String pythonServerBaseUri;

    /**
     * 启动任务：建立与 Python 的连接，并发送第一帧
     */
    public void startTask(String sessionId, WebSocketSession appSession, String task, String screenshot, String screenInfo) {
        long startTime = System.currentTimeMillis();
        try {
            // 保存 App session 用于超时通知
            appSessions.put(sessionId, appSession);

            // 1. 根据 App 的 SessionID 构造 Python 服务的唯一连接地址
            URI uri = new URI(pythonServerBaseUri + sessionId);
            log.info("[AgentService] 开始连接 Python 服务: {}, 任务: {}", uri, task);

            // 2. 创建 Client 并定义回调：收到 Python 消息 -> 转发给 App
            PythonAgentClient pythonClient = new PythonAgentClient(uri, messageFromPython -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[AgentService] 收到 Python 响应，耗时: {}ms", elapsed);
                requestTimestamps.put(sessionId, System.currentTimeMillis());  // 更新时间戳
                sendMessageToApp(appSession, messageFromPython);
            });

            // 3. 建立连接 (阻塞等待连接成功，最多30秒超时)
            boolean connected = pythonClient.connectBlocking(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!connected) {
                throw new RuntimeException("连接 Python 服务超时 (30秒)");
            }
            pythonClients.put(sessionId, pythonClient);
            requestTimestamps.put(sessionId, startTime);

            // 4. 发送初始化消息 (Init) 给 Python
            Map<String, Object> initPayload = Map.of(
                    "type", "init",
                    "task", task,
                    "screenshot", screenshot,
                    "screen_info", screenInfo != null ? screenInfo : ""
            );
            String payload = objectMapper.writeValueAsString(initPayload);
            log.info("[AgentService] 发送 init 消息，大小: {} 字符", payload.length());
            pythonClient.send(payload);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[AgentService] 启动任务失败，耗时: {}ms, 错误: {}", elapsed, e.getMessage(), e);
            requestTimestamps.remove(sessionId);
            appSessions.remove(sessionId);
            sendErrorToApp(appSession, "无法连接 AI 模型服务: " + e.getMessage());
        }
    }

    /**
     * 处理后续步骤：转发 App 的截图给 Python
     */
    public void processStep(String sessionId, String screenshot, String screenInfo) {
        long startTime = System.currentTimeMillis();
        PythonAgentClient client = pythonClients.get(sessionId);
        if (client != null && client.isOpen()) {
            try {
                Map<String, Object> stepPayload = Map.of(
                        "type", "step",
                        "screenshot", screenshot,
                        "screen_info", screenInfo != null ? screenInfo : ""
                );
                String payload = objectMapper.writeValueAsString(stepPayload);
                log.info("[AgentService] 发送 step 消息，大小: {} 字符", payload.length());
                requestTimestamps.put(sessionId, startTime);
                client.send(payload);
            } catch (Exception e) {
                log.error("[AgentService] 发送 step 消息失败", e);
            }
        } else {
            log.warn("[AgentService] 客户端不存在或未连接: {}", sessionId);
        }
    }

    /**
     * 清理资源：断开 Python 连接
     */
    public void cleanup(String sessionId) {
        log.info("[AgentService] 清理会话: {}", sessionId);
        PythonAgentClient client = pythonClients.remove(sessionId);
        requestTimestamps.remove(sessionId);
        appSessions.remove(sessionId);
        if (client != null) {
            client.close();
        }
    }

    /**
     * 定时检查任务超时
     * 每30秒执行一次，检查是否有任务超过5分钟无响应
     */
    @Scheduled(fixedRate = 30000)
    public void checkTaskTimeouts() {
        long currentTime = System.currentTimeMillis();
        java.util.List<String> timeoutSessions = new java.util.ArrayList<>();

        for (Map.Entry<String, Long> entry : requestTimestamps.entrySet()) {
            String sessionId = entry.getKey();
            long lastActivity = entry.getValue();
            long elapsed = currentTime - lastActivity;

            if (elapsed > TASK_TIMEOUT_MS) {
                log.warn("[AgentService] 任务超时: {}, 已运行: {}ms", sessionId, elapsed);
                timeoutSessions.add(sessionId);
            }
        }

        // 清理超时的会话
        for (String sessionId : timeoutSessions) {
            cleanup(sessionId);
            // 通知客户端任务超时
            WebSocketSession appSession = appSessions.get(sessionId);
            if (appSession != null && appSession.isOpen()) {
                sendErrorToApp(appSession, "任务超时自动停止（5分钟无响应）");
            }
        }
    }

    // --- 辅助方法 ---

    private void sendMessageToApp(WebSocketSession session, String message) {
        if (session.isOpen()) {
            synchronized (session) { // 防止并发写入冲突
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("[AgentService] 发送消息到 App 失败", e);
                }
            }
        } else {
            log.warn("[AgentService] App WebSocket 已关闭，无法发送消息");
        }
    }

    private void sendErrorToApp(WebSocketSession session, String errorMsg) {
        sendMessageToApp(session, "{\"status\":\"error\", \"message\":\"" + errorMsg + "\"}");
    }
}