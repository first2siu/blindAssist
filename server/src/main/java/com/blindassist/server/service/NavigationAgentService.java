package com.blindassist.server.service;

import com.blindassist.server.client.NavigationAgentClient;
import com.blindassist.server.model.SensorData;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导航代理服务
 * 连接到 FastAPI 的导航 WebSocket 端点，处理导航请求
 *
 * 架构变更：
 * - 导航指令不再直接通过 WebSocket 返回给 Android
 * - 而是放入 Redis TTS 优先级队列，由 Android 轮询获取
 */
@Service
public class NavigationAgentService {

    private static final Logger log = LoggerFactory.getLogger(NavigationAgentService.class);

    // 导航超时时间（毫秒）：30分钟无位置更新则自动停止
    private static final long NAVIGATION_TIMEOUT_MS = 30 * 60 * 1000;

    // 存储 "App Session ID" -> "Navigation Client" 的映射
    private final Map<String, NavigationAgentClient> navigationClients = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivityTimestamps = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> appSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TtsMessageQueue ttsQueue;

    // 导航 WebSocket 基础地址 (从配置文件读取，连接到新的 NavigationAgent 服务)
    @Value("${fastapi.websocket-navigation-base-url:ws://10.184.17.161:8081/ws/navigation/}")
    private String navigationServerBaseUri;

    // 高德地图 API Key (从配置文件读取)
    @Value("${amap.api-key:}")
    private String amapApiKey;

    public NavigationAgentService(TtsMessageQueue ttsQueue) {
        this.ttsQueue = ttsQueue;
    }

    /**
     * 启动导航任务：建立与 FastAPI 导航服务的连接
     *
     * @param sessionId 会话ID
     * @param appSession Android 客户端 WebSocket 会话
     * @param userId 用户ID（用于TTS队列）
     * @param userTask 用户任务（完整指令，如"带我去最近的肯德基"）
     * @param origin 起点坐标 {"lon": xxx, "lat": xxx}
     * @param sensorData 传感器数据
     */
    public void startNavigation(String sessionId, WebSocketSession appSession, String userId,
                               String userTask, Map<String, Object> origin, SensorData sensorData) {
        long startTime = System.currentTimeMillis();
        try {
            // 保存 App session 用于超时通知
            appSessions.put(sessionId, appSession);

            // 1. 构造导航服务连接地址
            URI uri = new URI(navigationServerBaseUri + sessionId);
            log.info("[NavigationAgentService] 开始连接导航服务: {}, 任务: {}", uri, userTask);

            // 2. 创建 Client 并定义回调
            NavigationAgentClient navClient = new NavigationAgentClient(uri, messageFromNav -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[NavigationAgentService] 收到导航响应，耗时: {}ms", elapsed);
                lastActivityTimestamps.put(sessionId, System.currentTimeMillis());  // 更新活动时间
                // 将导航指令添加到 TTS 队列，而不是直接发送给 Android
                handleNavigationResponse(sessionId, appSession, messageFromNav);
            });

            // 3. 建立连接
            boolean connected = navClient.connectBlocking(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!connected) {
                throw new RuntimeException("连接导航服务超时 (30秒)");
            }
            navigationClients.put(sessionId, navClient);
            sessionUserIds.put(sessionId, userId);
            lastActivityTimestamps.put(sessionId, startTime);

            // 4. 发送导航初始化消息
            Map<String, Object> sensorMap = Map.of(
                "heading", sensorData != null ? (double) sensorData.getHeading() : 0.0,
                "accuracy", sensorData != null ? (double) sensorData.getAccuracy() : 0.0
            );

            // 传递完整任务给模型，让模型理解并提取目的地
            Map<String, Object> initPayload = Map.of(
                "type", "init",
                "user_task", userTask,  // 完整用户任务，如"带我去最近的肯德基"
                "origin", origin != null ? origin : getDefaultOrigin(),
                "sensor_data", sensorMap,
                "amap_api_key", amapApiKey != null ? amapApiKey : ""  // 传递高德 API Key
            );

            String payload = objectMapper.writeValueAsString(initPayload);
            log.info("[NavigationAgentService] 发送导航 init 消息: {}", payload);
            navClient.send(payload);

            // 5. 通知 Android 启动障碍物检测
            String obstacleStartMsg = "{\"status\":\"success\",\"type\":\"start_obstacle_detection\",\"message\":\"导航已开始，正在启动避障服务\",\"obstacle_url\":\"ws://10.184.17.161:8004/ws\"}";
            sendMessageToApp(appSession, obstacleStartMsg);
            log.info("[NavigationAgentService] 已发送障碍物检测启动通知");

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[NavigationAgentService] 启动导航失败，耗时: {}ms, 错误: {}", elapsed, e.getMessage(), e);
            appSessions.remove(sessionId);
            sendErrorToApp(appSession, "无法连接导航服务: " + e.getMessage());
        }
    }

    /**
     * 更新导航位置
     *
     * @param sessionId 会话ID
     * @param origin 当前位置
     * @param sensorData 传感器数据
     */
    public void updateLocation(String sessionId, Map<String, Object> origin, SensorData sensorData) {
        NavigationAgentClient client = navigationClients.get(sessionId);
        if (client != null && client.isOpen()) {
            try {
                Map<String, Object> sensorMap = Map.of(
                    "heading", sensorData != null ? (double) sensorData.getHeading() : 0.0,
                    "accuracy", sensorData != null ? (double) sensorData.getAccuracy() : 0.0
                );

                Map<String, Object> updatePayload = Map.of(
                    "type", "location_update",
                    "origin", origin,
                    "sensor_data", sensorMap
                );

                String payload = objectMapper.writeValueAsString(updatePayload);
                log.info("[NavigationAgentService] 发送位置更新: {}", payload);
                client.send(payload);
            } catch (Exception e) {
                log.error("[NavigationAgentService] 发送位置更新失败", e);
            }
        } else {
            log.warn("[NavigationAgentService] 导航客户端不存在或未连接: {}", sessionId);
        }
    }

    /**
     * 取消导航
     *
     * @param sessionId 会话ID
     */
    public void cancelNavigation(String sessionId) {
        log.info("[NavigationAgentService] 取消导航: {}", sessionId);
        NavigationAgentClient client = navigationClients.remove(sessionId);
        sessionUserIds.remove(sessionId);
        if (client != null) {
            try {
                Map<String, Object> cancelPayload = Map.of("type", "cancel");
                client.send(objectMapper.writeValueAsString(cancelPayload));
            } catch (Exception e) {
                log.error("[NavigationAgentService] 发送取消消息失败", e);
            }
            client.close();
        }
    }

    /**
     * 暂停导航
     *
     * @param sessionId 会话ID
     */
    public void pauseNavigation(String sessionId) {
        log.info("[NavigationAgentService] 暂停导航: {}", sessionId);
        NavigationAgentClient client = navigationClients.get(sessionId);
        if (client != null && client.isOpen()) {
            try {
                Map<String, Object> pausePayload = Map.of("type", "pause");
                client.send(objectMapper.writeValueAsString(pausePayload));
            } catch (Exception e) {
                log.error("[NavigationAgentService] 发送暂停消息失败", e);
            }
        } else {
            log.warn("[NavigationAgentService] 导航客户端不存在或未连接: {}", sessionId);
        }
    }

    /**
     * 恢复导航
     *
     * @param sessionId 会话ID
     */
    public void resumeNavigation(String sessionId) {
        log.info("[NavigationAgentService] 恢复导航: {}", sessionId);
        NavigationAgentClient client = navigationClients.get(sessionId);
        if (client != null && client.isOpen()) {
            try {
                // 获取当前位置（如果有缓存）
                Map<String, Object> resumePayload = Map.of(
                    "type", "resume",
                    "origin", getDefaultOrigin(),  // 理想情况下应该从缓存获取真实位置
                    "sensor_data", Map.of("heading", 0.0, "accuracy", 10.0)
                );
                client.send(objectMapper.writeValueAsString(resumePayload));
            } catch (Exception e) {
                log.error("[NavigationAgentService] 发送恢复消息失败", e);
            }
        } else {
            log.warn("[NavigationAgentService] 导航客户端不存在或未连接: {}", sessionId);
        }
    }

    /**
     * 处理导航响应
     * 将导航指令添加到 TTS 队列，而非直接发送给 Android
     */
    private void handleNavigationResponse(String sessionId, WebSocketSession appSession, String messageFromNav) {
        try {
            Map<String, Object> response = objectMapper.readValue(messageFromNav, Map.class);
            String status = (String) response.get("status");
            String type = (String) response.get("type");

            // 处理错误状态
            if ("error".equals(status)) {
                String errorMsg = (String) response.get("message");
                sendErrorToApp(appSession, errorMsg != null ? errorMsg : "导航错误");
                return;
            }

            // 处理导航指令 - 添加到 TTS 队列
            if (response.containsKey("instruction")) {
                String instruction = (String) response.get("instruction");
                String userId = sessionUserIds.get(sessionId);

                if (instruction != null && !instruction.isEmpty() && userId != null) {
                    // 根据类型确定优先级
                    TtsPriority priority = TtsPriority.NORMAL;
                    if ("arrived".equals(type)) {
                        // 到达目的地，使用高优先级
                        priority = TtsPriority.HIGH;
                    }

                    ttsQueue.enqueue(userId, instruction, priority, "navigation");
                    log.info("[NavigationAgentService] 导航指令已加入 TTS 队列: {}", instruction);
                }

                // 对于到达目的地，额外发送通知给 Android 停止导航
                if ("arrived".equals(type)) {
                    String arrivedMsg = "{\"status\":\"success\",\"type\":\"arrived\",\"message\":\"已到达目的地\"}";
                    sendMessageToApp(appSession, arrivedMsg);
                }
            }

            // 处理其他类型的状态消息（如路线规划完成）
            if ("route_planned".equals(type)) {
                String statusMsg = "{\"status\":\"success\",\"type\":\"route_planned\",\"message\":\"路线已规划\"}";
                sendMessageToApp(appSession, statusMsg);
            }

        } catch (Exception e) {
            log.error("[NavigationAgentService] 处理导航响应失败", e);
        }
    }

    /**
     * 清理资源
     *
     * @param sessionId 会话ID
     */
    public void cleanup(String sessionId) {
        log.info("[NavigationAgentService] 清理会话: {}", sessionId);
        NavigationAgentClient client = navigationClients.remove(sessionId);
        sessionUserIds.remove(sessionId);
        lastActivityTimestamps.remove(sessionId);
        appSessions.remove(sessionId);
        if (client != null) {
            client.close();
        }
    }

    /**
     * 获取用户ID
     *
     * @param sessionId 会话ID
     * @return 用户ID
     */
    public String getUserId(String sessionId) {
        return sessionUserIds.get(sessionId);
    }

    /**
     * 定时检查导航超时
     * 每60秒执行一次，检查是否有导航超过30分钟无活动
     */
    @Scheduled(fixedRate = 60000)
    public void checkNavigationTimeouts() {
        long currentTime = System.currentTimeMillis();
        ArrayList<String> timeoutSessions = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastActivityTimestamps.entrySet()) {
            String sessionId = entry.getKey();
            long lastActivity = entry.getValue();
            long elapsed = currentTime - lastActivity;

            if (elapsed > NAVIGATION_TIMEOUT_MS) {
                log.warn("[NavigationAgentService] 导航超时: {}, 已运行: {}ms", sessionId, elapsed);
                timeoutSessions.add(sessionId);
            }
        }

        // 清理超时的会话
        for (String sessionId : timeoutSessions) {
            cleanup(sessionId);
            // 通知客户端导航超时
            WebSocketSession appSession = appSessions.get(sessionId);
            if (appSession != null && appSession.isOpen()) {
                sendErrorToApp(appSession, "导航超时自动停止（30分钟无活动）");
            }
        }
    }

    // --- 辅助方法 ---

    private void sendMessageToApp(WebSocketSession session, String message) {
        if (session.isOpen()) {
            synchronized (session) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("[NavigationAgentService] 发送消息到 App 失败", e);
                }
            }
        } else {
            log.warn("[NavigationAgentService] App WebSocket 已关闭，无法发送消息");
        }
    }

    private void sendErrorToApp(WebSocketSession session, String errorMsg) {
        sendMessageToApp(session, "{\"status\":\"error\", \"message\":\"" + errorMsg + "\"}");
    }

    /**
     * 获取默认起点（北京市中心）
     */
    private Map<String, Object> getDefaultOrigin() {
        return Map.of("lon", 116.397428, "lat", 39.90923);
    }
}
