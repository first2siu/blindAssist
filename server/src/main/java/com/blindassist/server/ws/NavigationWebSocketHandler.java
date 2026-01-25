package com.blindassist.server.ws;

import com.blindassist.server.service.NavigationService;
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

/**
 * 导航WebSocket Handler
 * 处理导航相关的WebSocket连接
 */
@Component
public class NavigationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(NavigationWebSocketHandler.class);

    private final NavigationService navigationService;
    private final ObjectMapper objectMapper;

    public NavigationWebSocketHandler(NavigationService navigationService) {
        this.navigationService = navigationService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Navigation WebSocket connected: {}", session.getId());
        session.sendMessage(new TextMessage("{\"status\":\"connected\",\"message\":\"导航通道已建立\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) payload.get("type");
            String userId = (String) payload.get("user_id");

            if ("start".equals(type)) {
                // 开始导航
                String destination = (String) payload.get("destination");
                Map<String, Object> sensorDataMap = (Map<String, Object>) payload.get("sensor_data");

                if (destination != null && sensorDataMap != null) {
                    com.blindassist.server.model.SensorData sensorData =
                        objectMapper.convertValue(sensorDataMap, com.blindassist.server.model.SensorData.class);
                    navigationService.startNavigation(userId, destination, sensorData);
                }

            } else if ("stop".equals(type)) {
                // 停止导航
                if (userId != null) {
                    navigationService.stopNavigation(userId);
                }

            } else if ("update".equals(type)) {
                // 更新位置
                Map<String, Object> sensorDataMap = (Map<String, Object>) payload.get("sensor_data");
                if (userId != null && sensorDataMap != null) {
                    com.blindassist.server.model.SensorData sensorData =
                        objectMapper.convertValue(sensorDataMap, com.blindassist.server.model.SensorData.class);
                    navigationService.updateNavigation(userId, sensorData);
                }
            }

        } catch (Exception e) {
            logger.error("Error handling navigation message", e);
            sendError(session, "处理消息失败: " + e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("Navigation WebSocket transport error", exception);
        sendError(session, "连接错误: " + exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Navigation WebSocket closed: {}, status: {}", session.getId(), status);
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"" + error + "\"}"));
    }
}
