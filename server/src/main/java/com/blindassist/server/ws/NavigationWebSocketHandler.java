package com.blindassist.server.ws;

import com.blindassist.server.service.NavigationService;
import com.blindassist.server.service.NavigationAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 导航WebSocket Handler
 * 处理导航相关的WebSocket连接
 *
 * 支持两种模式：
 * 1. 旧模式：直接通过 NavigationService 处理导航指令
 * 2. 新模式：转发到 NavigationAgentService（连接 FastAPI 导航服务）
 */
@Component
public class NavigationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(NavigationWebSocketHandler.class);

    private final NavigationService navigationService;
    private final NavigationAgentService navigationAgentService;
    private final ObjectMapper objectMapper;

    // 存储会话到用户ID的映射
    private final Map<String, String> sessionUserIds = new HashMap<>();

    public NavigationWebSocketHandler(NavigationService navigationService,
                                      NavigationAgentService navigationAgentService) {
        this.navigationService = navigationService;
        this.navigationAgentService = navigationAgentService;
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

            logger.info("[NavigationWS] 收到消息: type={}, session={}", type, session.getId());

            // 处理来自 Android NavigationManager 的消息
            if ("init".equals(type)) {
                handleInitMessage(session, session.getId(), payload);
            } else if ("location_update".equals(type)) {
                handleLocationUpdateMessage(session.getId(), payload);
            } else if ("start".equals(type)) {
                // 旧接口兼容
                handleStartMessage(payload);
            } else if ("stop".equals(type)) {
                handleStopMessage(session.getId());
            } else {
                logger.warn("[NavigationWS] 未知消息类型: {}", type);
            }

        } catch (Exception e) {
            logger.error("[NavigationWS] Error handling navigation message", e);
            sendError(session, "处理消息失败: " + e.getMessage());
        }
    }

    /**
     * 处理初始化消息（来自 Android NavigationManager）
     * 消息格式: {type: "init", user_task: "目的地", origin: {lon, lat}, sensor_data: {heading, accuracy}}
     */
    private void handleInitMessage(WebSocketSession session, String sessionId, Map<String, Object> payload) throws IOException {
        String userTask = (String) payload.get("user_task");
        @SuppressWarnings("unchecked")
        Map<String, Object> origin = (Map<String, Object>) payload.get("origin");
        @SuppressWarnings("unchecked")
        Map<String, Object> sensorDataMap = (Map<String, Object>) payload.get("sensor_data");

        logger.info("[NavigationWS] init - user_task: {}, origin: {}", userTask, origin);

        // 保存用户ID（使用 session ID 作为用户 ID）
        String userId = sessionId;
        sessionUserIds.put(sessionId, userId);

        // 构建 SensorData
        com.blindassist.server.model.SensorData sensorData = new com.blindassist.server.model.SensorData();
        if (origin != null) {
            Object lat = origin.get("lat");
            Object lon = origin.get("lon");
            if (lat instanceof Number) sensorData.setLatitude(((Number) lat).doubleValue());
            if (lon instanceof Number) sensorData.setLongitude(((Number) lon).doubleValue());
        }
        if (sensorDataMap != null) {
            Object heading = sensorDataMap.get("heading");
            Object accuracy = sensorDataMap.get("accuracy");
            if (heading instanceof Number) sensorData.setHeading(((Number) heading).floatValue());
            if (accuracy instanceof Number) sensorData.setAccuracy(((Number) accuracy).floatValue());
        }

        // 转发到 NavigationAgentService
        try {
            navigationAgentService.startNavigation(sessionId, session, userId, userTask, origin, sensorData);
            logger.info("[NavigationWS] 导航已启动: userId={}, task={}", userId, userTask);
        } catch (Exception e) {
            logger.error("[NavigationWS] 启动导航失败", e);
            sendError(session, "启动导航失败: " + e.getMessage());
        }
    }

    /**
     * 处理位置更新消息（来自 Android NavigationManager）
     * 消息格式: {type: "location_update", origin: {lon, lat}, sensor_data: {heading, accuracy}}
     */
    private void handleLocationUpdateMessage(String sessionId, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> origin = (Map<String, Object>) payload.get("origin");
        @SuppressWarnings("unchecked")
        Map<String, Object> sensorDataMap = (Map<String, Object>) payload.get("sensor_data");

        if (origin != null) {
            logger.info("[NavigationWS] location_update - origin: {}", origin);
        }

        // 构建 SensorData
        com.blindassist.server.model.SensorData sensorData = new com.blindassist.server.model.SensorData();
        if (origin != null) {
            Object lat = origin.get("lat");
            Object lon = origin.get("lon");
            if (lat instanceof Number) sensorData.setLatitude(((Number) lat).doubleValue());
            if (lon instanceof Number) sensorData.setLongitude(((Number) lon).doubleValue());
        }
        if (sensorDataMap != null) {
            Object heading = sensorDataMap.get("heading");
            Object accuracy = sensorDataMap.get("accuracy");
            if (heading instanceof Number) sensorData.setHeading(((Number) heading).floatValue());
            if (accuracy instanceof Number) sensorData.setAccuracy(((Number) accuracy).floatValue());
        }

        // 转发到 NavigationAgentService
        if (origin != null) {
            navigationAgentService.updateLocation(sessionId, origin, sensorData);
        }
    }

    /**
     * 处理旧版开始导航消息（兼容）
     */
    private void handleStartMessage(Map<String, Object> payload) {
        String userId = (String) payload.get("user_id");
        String destination = (String) payload.get("destination");
        Number destLat = (Number) payload.get("destination_lat");
        Number destLng = (Number) payload.get("destination_lng");
        @SuppressWarnings("unchecked")
        Map<String, Object> sensorDataMap = (Map<String, Object>) payload.get("sensor_data");

        if (destination != null && destLat != null && destLng != null && sensorDataMap != null) {
            com.blindassist.server.model.SensorData sensorData =
                objectMapper.convertValue(sensorDataMap, com.blindassist.server.model.SensorData.class);
            navigationService.startNavigation(userId, destination,
                destLat.doubleValue(), destLng.doubleValue(), sensorData);
        }
    }

    /**
     * 处理停止导航消息
     */
    private void handleStopMessage(String sessionId) {
        String userId = sessionUserIds.get(sessionId);
        logger.info("[NavigationWS] stop navigation: userId={}", userId);

        if (userId != null) {
            navigationAgentService.cancelNavigation(sessionId);
        } else {
            navigationService.stopNavigation(sessionId);
        }

        sessionUserIds.remove(sessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("[NavigationWS] transport error", exception);
        sendError(session, "连接错误: " + exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("[NavigationWS] closed: {}, status: {}", session.getId(), status);

        // 清理导航会话
        String userId = sessionUserIds.remove(session.getId());
        if (userId != null) {
            navigationAgentService.cancelNavigation(session.getId());
        }
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"" + error + "\"}"));
    }
}
