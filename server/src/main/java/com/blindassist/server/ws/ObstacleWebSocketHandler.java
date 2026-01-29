package com.blindassist.server.ws;

import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 避障WebSocket Handler
 * 接收来自FastAPI避障服务的检测结果，转发到Redis TTS队列
 *
 * 数据流:
 * FastAPI避障服务 → Spring Boot ObstacleWebSocketHandler → Redis TTS队列 → Android客户端
 */
@Component
public class ObstacleWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ObstacleWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final TtsMessageQueue ttsQueue;

    // 存储用户ID到Session的映射
    private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    public ObstacleWebSocketHandler(TtsMessageQueue ttsQueue) {
        this.objectMapper = new ObjectMapper();
        this.ttsQueue = ttsQueue;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Obstacle WebSocket connected: {}", session.getId());
        session.sendMessage(new TextMessage("{\"status\":\"connected\",\"message\":\"避障通道已建立\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String msgType = root.path("type").asText();
            String userId = root.path("user_id").asText();

            // 注册用户
            if ("register".equals(msgType) && userId != null && !userId.isEmpty()) {
                userSessions.put(userId, session);
                logger.info("User registered for obstacle detection: {}", userId);
                return;
            }

            // 处理避障检测结果
            if ("obstacle".equals(msgType)) {
                handleObstacleDetection(root, userId);
            }
            // 处理心跳
            else if ("keep_alive".equals(msgType)) {
                session.sendMessage(new TextMessage("{\"status\":\"alive\"}"));
            }

        } catch (Exception e) {
            logger.error("Error handling obstacle message", e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Obstacle WebSocket transport error", exception);
        cleanupSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Obstacle WebSocket closed: {}, status: {}", session.getId(), status);
        cleanupSession(session);
    }

    /**
     * 处理避障检测结果，转发到TTS队列
     *
     * 预期消息格式:
     * {
     *   "type": "obstacle",
     *   "user_id": "user123",
     *   "warning": "前方三米有台阶，请减速",
     *   "urgency": "high",  // critical/high/medium/low
     *   "obstacle": {
     *     "type": "台阶",
     *     "position": "前方",
     *     "distance": 3.0,
     *     "instruction": "前方三米有台阶，请减速并注意脚下"
     *   }
     * }
     */
    private void handleObstacleDetection(JsonNode root, String userId) {
        if (userId == null || userId.isEmpty()) {
            logger.warn("Missing user_id in obstacle detection message");
            return;
        }

        String warning = root.path("warning").asText();
        String urgency = root.path("urgency").asText("medium");
        JsonNode obstacle = root.path("obstacle");

        // 解析语音指令
        String ttsContent = warning;
        if (obstacle != null && obstacle.has("instruction")) {
            ttsContent = obstacle.path("instruction").asText();
        }

        // 根据紧急程度确定优先级
        TtsPriority priority = mapUrgencyToPriority(urgency);

        // 加入TTS队列
        ttsQueue.enqueue(userId, ttsContent, priority, "obstacle");

        logger.info("Enqueued obstacle warning for user {}: priority={}, content={}",
                    userId, priority, ttsContent);
    }

    /**
     * 将紧急程度映射到TTS优先级
     */
    private TtsPriority mapUrgencyToPriority(String urgency) {
        if (urgency == null) {
            return TtsPriority.NORMAL;
        }
        return switch (urgency.toLowerCase()) {
            case "critical" -> TtsPriority.CRITICAL;
            case "high" -> TtsPriority.HIGH;
            case "medium" -> TtsPriority.NORMAL;
            case "low" -> TtsPriority.LOW;
            default -> TtsPriority.NORMAL;
        };
    }

    /**
     * 清理Session
     */
    private void cleanupSession(WebSocketSession session) {
        userSessions.entrySet().removeIf(entry -> entry.getValue().equals(session));
    }

    /**
     * 向指定用户发送消息
     */
    public void sendMessageToUser(String userId, String message) {
        WebSocketSession session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (Exception e) {
                logger.error("Failed to send message to user {}", userId, e);
            }
        }
    }
}
