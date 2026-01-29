package com.blindassist.server.service;

import com.blindassist.server.api.dto.NavigationRouteRequest;
import com.blindassist.server.api.dto.NavigationRouteResponse;
import com.blindassist.server.config.AmapProperties;
import com.blindassist.server.model.SensorData;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导航服务
 * 与 AutoGLM 配合提供导航功能，导航指令通过 Redis TTS 队列播报
 */
@Service
public class NavigationService {

    private static final Logger logger = LoggerFactory.getLogger(NavigationService.class);

    private final AmapProperties amapProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TtsMessageQueue ttsQueue;

    // 存储活跃导航会话的状态
    private final Map<String, NavigationSession> activeSessions = new ConcurrentHashMap<>();

    public NavigationService(AmapProperties amapProperties, TtsMessageQueue ttsQueue) {
        this.amapProperties = amapProperties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.ttsQueue = ttsQueue;
    }

    /**
     * 导航会话状态
     */
    public static class NavigationSession {
        private String userId;
        private String destination;
        private double destinationLat;
        private double destinationLng;
        private long startTime;
        private int lastStepIndex = -1;

        public NavigationSession(String userId, String destination, double lat, double lng) {
            this.userId = userId;
            this.destination = destination;
            this.destinationLat = lat;
            this.destinationLng = lng;
            this.startTime = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getUserId() { return userId; }
        public String getDestination() { return destination; }
        public double getDestinationLat() { return destinationLat; }
        public double getDestinationLng() { return destinationLng; }
        public long getStartTime() { return startTime; }
        public int getLastStepIndex() { return lastStepIndex; }
        public void setLastStepIndex(int index) { this.lastStepIndex = index; }
    }

    /**
     * 规划导航路线（HTTP API接口）
     * 提供基础的路径规划功能，实际导航指令由 AutoGLM 通过 WebSocket 处理
     *
     * @deprecated 实际导航应通过 AutoGLM WebSocket 连接处理
     */
    @Deprecated
    public NavigationRouteResponse planRoute(NavigationRouteRequest req) {
        NavigationRouteResponse response = new NavigationRouteResponse();
        List<String> voiceSteps = new ArrayList<>();

        try {
            // 调用高德地图步行路径规划API
            String url = "https://restapi.amap.com/v3/direction/walking";
            String apiKey = amapProperties.getApiKey();

            String requestUrl = String.format("%s?key=%s&origin=%s,%s&destination=%s,%s",
                url,
                apiKey,
                req.getStartLng(), req.getStartLat(),
                req.getEndLng(), req.getEndLat()
            );

            String responseBody = restTemplate.getForObject(requestUrl, String.class);
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析路径步骤
            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (paths.isArray() && paths.size() > 0) {
                JsonNode steps = paths.get(0).path("steps");
                if (steps.isArray()) {
                    for (JsonNode step : steps) {
                        String instruction = step.path("instruction").asText();
                        // 基础转换，实际友好化由 AutoGLM 处理
                        voiceSteps.add(convertToVoiceInstruction(instruction));
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to plan route", e);
            voiceSteps.add("路线规划失败，请稍后重试");
        }

        response.setVoiceSteps(voiceSteps);
        return response;
    }

    /**
     * 基础指令转换（仅用于备用，实际由 AutoGLM 处理）
     */
    private String convertToVoiceInstruction(String instruction) {
        return instruction
            .replace("沿", "")
            .replace("向前", "直行")
            .replace("向左", "左转")
            .replace("向右", "右转");
    }

    /**
     * 开始导航
     * 创建导航会话并发送初始提示到TTS队列
     *
     * @param userId 用户ID
     * @param destination 目的地名称
     * @param destinationLat 目的地纬度
     * @param destinationLng 目的地经度
     */
    public void startNavigation(String userId, String destination,
                                double destinationLat, double destinationLng,
                                SensorData sensorData) {
        logger.info("Start navigation for user {}, destination: {} ({}, {})",
                    userId, destination, destinationLat, destinationLng);

        // 创建导航会话
        NavigationSession session = new NavigationSession(userId, destination, destinationLat, destinationLng);
        activeSessions.put(userId, session);

        // 发送导航开始提示到TTS队列（NORMAL优先级，可被避障打断）
        String startMessage = "开始导航，前往" + destination;
        ttsQueue.enqueue(userId, startMessage, TtsPriority.NORMAL, "navigation");
    }

    /**
     * 停止导航
     * 结束导航会话并发送停止提示
     *
     * @param userId 用户ID
     * @param reason 停止原因（到达目的地/用户取消/错误等）
     */
    public void stopNavigation(String userId, String reason) {
        logger.info("Stop navigation for user {}, reason: {}", userId, reason);

        // 移除导航会话
        NavigationSession session = activeSessions.remove(userId);

        if (session != null) {
            // 发送导航结束提示
            String stopMessage;
            switch (reason) {
                case "arrived":
                    stopMessage = "已到达目的地" + session.getDestination();
                    break;
                case "cancelled":
                    stopMessage = "导航已取消";
                    break;
                case "off_route":
                    stopMessage = "偏离路线，导航已停止";
                    break;
                default:
                    stopMessage = "导航已结束";
            }
            ttsQueue.enqueue(userId, stopMessage, TtsPriority.NORMAL, "navigation");
        }
    }

    /**
     * 停止导航（简化版本，默认原因为用户取消）
     */
    public void stopNavigation(String userId) {
        stopNavigation(userId, "cancelled");
    }

    /**
     * 更新导航位置
     * 当用户位置更新时调用，检查是否需要发送新的导航指令
     *
     * @param userId 用户ID
     * @param sensorData 传感器数据（包含GPS位置）
     * @param nextStepInstruction 下一步指令（由AutoGLM计算）
     * @param stepIndex 当前步骤索引
     */
    public void updateNavigation(String userId, SensorData sensorData,
                                String nextStepInstruction, int stepIndex) {
        logger.debug("Navigation update for user: {}, step: {}", userId, stepIndex);

        NavigationSession session = activeSessions.get(userId);
        if (session == null) {
            logger.warn("No active navigation session for user {}", userId);
            return;
        }

        // 只有步骤索引变化时才发送新指令（避免重复播报）
        if (stepIndex > session.getLastStepIndex()) {
            session.setLastStepIndex(stepIndex);

            if (nextStepInstruction != null && !nextStepInstruction.isEmpty()) {
                // 发送导航指令到TTS队列（NORMAL优先级，可被避障打断）
                ttsQueue.enqueue(userId, nextStepInstruction, TtsPriority.NORMAL, "navigation");
                logger.debug("Enqueued navigation instruction for user {}: {}", userId, nextStepInstruction);
            }
        }
    }

    /**
     * 更新导航位置（简化版本，仅更新GPS）
     */
    public void updateNavigation(String userId, SensorData sensorData) {
        NavigationSession session = activeSessions.get(userId);
        if (session == null) {
            logger.debug("No active navigation session for user {}", userId);
            return;
        }

        // 这里可以添加基于GPS的位置更新逻辑
        // 例如：检查是否偏离路线、是否接近目的地等
        logger.debug("GPS update for user {}: lat={}, lng={}",
                    userId, sensorData.getLatitude(), sensorData.getLongitude());
    }

    /**
     * 发送导航指令到TTS队列
     * 供其他服务（如AutoGLM）直接调用
     *
     * @param userId 用户ID
     * @param instruction 导航指令文本
     * @param priority 优先级（可选，默认NORMAL）
     */
    public void sendNavigationInstruction(String userId, String instruction, TtsPriority priority) {
        TtsPriority actualPriority = (priority != null) ? priority : TtsPriority.NORMAL;
        ttsQueue.enqueue(userId, instruction, actualPriority, "navigation");
        logger.debug("Sent navigation instruction to user {}: {}", userId, instruction);
    }

    /**
     * 检查用户是否有活跃的导航会话
     */
    public boolean hasActiveNavigation(String userId) {
        return activeSessions.containsKey(userId);
    }

    /**
     * 获取用户的导航会话
     */
    public NavigationSession getNavigationSession(String userId) {
        return activeSessions.get(userId);
    }

    /**
     * 清除指定用户的导航会话（内部使用，不发送TTS）
     */
    public void clearNavigationSession(String userId) {
        activeSessions.remove(userId);
    }
}
