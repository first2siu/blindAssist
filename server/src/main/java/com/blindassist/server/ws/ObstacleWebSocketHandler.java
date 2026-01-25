package com.blindassist.server.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 避障WebSocket Handler
 * 接收Android端的摄像头帧，转发给FastAPI避障服务
 */
@Component
public class ObstacleWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ObstacleWebSocketHandler.class);

    // 存储用户Session到WebSocket的映射
    private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public ObstacleWebSocketHandler() {
        this.objectMapper = new ObjectMapper();
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
            String type = root.path("type").asText();
            String userId = root.path("user_id").asText();

            // 注册用户Session
            if (userId != null && !userId.isEmpty()) {
                userSessions.put(userId, session);
            }

            if ("register".equals(type)) {
                // 用户注册
                logger.info("User registered for obstacle detection: {}", userId);

            } else if ("frame".equals(type)) {
                // 接收视频帧
                String frameData = root.path("frame_data").asText();
                JsonNode sensorData = root.path("sensor_data");

                // TODO: 转发到FastAPI避障服务进行处理
                // 当前为示例实现
                handleObstacleFrame(userId, frameData, sensorData);

            } else if ("keep_alive".equals(type)) {
                // 心跳保活
                session.sendMessage(new TextMessage("{\"status\":\"alive\"}"));
            }

        } catch (Exception e) {
            logger.error("Error handling obstacle message", e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Obstacle WebSocket transport error", exception);
        // 移除失效的Session
        userSessions.values().removeIf(s -> s.equals(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Obstacle WebSocket closed: {}, status: {}", session.getId(), status);
        // 移除关闭的Session
        userSessions.values().removeIf(s -> s.equals(session));
    }

    /**
     * 处理避障帧
     * TODO: 实际实现需要转发到FastAPI避障服务
     */
    private void handleObstacleFrame(String userId, String frameData, JsonNode sensorData) {
        // 示例：每处理10帧返回一条假指令
        // 实际实现中，这里应该转发到FastAPI避障服务

        logger.debug("Received frame from user {}, size: {} bytes", userId, frameData != null ? frameData.length() : 0);

        // 暂时不做处理，等待FastAPI服务实现
    }

    /**
     * 向用户发送避障警告
     */
    public void sendObstacleWarning(String userId, String warning, String urgency) {
        WebSocketSession session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                String message = String.format(
                    "{\"type\":\"obstacle\",\"warning\":\"%s\",\"urgency\":\"%s\"}",
                    warning, urgency
                );
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                logger.error("Failed to send obstacle warning to user {}", userId, e);
            }
        }
    }
}
