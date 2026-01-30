package com.blindassist.server.ws;

import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.service.AgentService;
import com.blindassist.server.service.IntentClassificationService;
import com.blindassist.server.service.NavigationAgentService;
import com.blindassist.server.service.NavigationService;
import com.blindassist.server.model.SensorData;
import com.blindassist.server.api.dto.VoiceCommandResponse;
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

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final AgentService agentService;
    private final IntentClassificationService intentService;
    private final NavigationAgentService navigationAgentService;
    private final NavigationService navigationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 存储会话类型（用于清理时知道要清理哪个服务）
    private final Map<String, String> sessionTypes = new HashMap<>();

    public AgentWebSocketHandler(AgentService agentService,
                                  IntentClassificationService intentService,
                                  NavigationAgentService navigationAgentService,
                                  NavigationService navigationService) {
        this.agentService = agentService;
        this.intentService = intentService;
        this.navigationAgentService = navigationAgentService;
        this.navigationService = navigationService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("App 接入 Agent 通道: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        logger.debug("收到 WebSocket 消息: {}", payload);

        try {
            // 先解析为 Map 检查消息类型
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMessage = objectMapper.readValue(payload, Map.class);
            String type = (String) rawMessage.get("type");

            // 处理心跳消息
            if ("heartbeat".equals(type)) {
                // 回复 pong
                String pongResponse = "{\"type\":\"pong\",\"timestamp\":" + System.currentTimeMillis() + "}";
                session.sendMessage(new TextMessage(pongResponse));
                logger.debug("回复心跳 pong");
                return;
            }

            // 处理其他消息，解析为 AgentMessage
            AgentMessage msg = objectMapper.readValue(payload, AgentMessage.class);
            String sessionId = session.getId();
            logger.info("消息类型: {}, Session: {}, 任务: {}", msg.getType(), sessionId, msg.getTask());

            // 根据类型调度给 Service
            if ("init".equals(msg.getType())) {
                // ========== 意图分类路由 ==========
                VoiceCommandResponse intent = intentService.classify(msg.getTask());
                logger.info("========== 意图分类结果 ==========");
                logger.info("任务: \"{}\"", msg.getTask());
                logger.info("意图类型: {}", intent.getFeature());
                logger.info("详细描述: {}", intent.getDetail());
                logger.info("================================");

                switch (intent.getFeature()) {
                    case "STOP":
                        // 停止当前任务
                        logger.info("停止当前任务");
                        handleStopRequest(session, sessionId);
                        break;

                    case "PAUSE":
                        // 暂停当前任务
                        logger.info("暂停当前任务");
                        handlePauseRequest(session, sessionId);
                        break;

                    case "RESUME":
                        // 恢复当前任务
                        logger.info("恢复当前任务");
                        handleResumeRequest(session, sessionId);
                        break;

                    case "NAVIGATION":
                        // 导航请求：使用 NavigationAgentService（连接 FastAPI 导航 WebSocket）
                        logger.info("路由到导航服务 (NavigationAgentService → FastAPI /ws/navigation/)");
                        handleNavigationRequest(session, msg);  // 传递完整消息
                        sessionTypes.put(sessionId, "NAVIGATION");
                        break;

                    case "OBSTACLE":
                        // 避障请求：使用避障服务
                        logger.info("路由到避障服务");
                        handleObstacleRequest(session, msg.getTask());
                        sessionTypes.put(sessionId, "OBSTACLE");
                        break;

                    case "PHONE_CONTROL":
                    default:
                        // 手机操控：使用 AutoGLM
                        logger.info("路由到 AutoGLM 手机操控服务");
                        agentService.startTask(sessionId, session, msg.getTask(), msg.getScreenshot(), msg.getScreenInfo());
                        sessionTypes.put(sessionId, "PHONE_CONTROL");
                        break;
                }

            } else if ("step".equals(msg.getType())) {
                // step 消息继续走 AutoGLM（只有手机操控才有 step）
                agentService.processStep(sessionId, msg.getScreenshot(), msg.getScreenInfo());
            } else if ("location_update".equals(msg.getType())) {
                // 位置更新消息（导航中使用）
                handleLocationUpdateRequest(session, sessionId, msg);
            } else {
                logger.warn("未知消息类型: {}", msg.getType());
            }

        } catch (Exception e) {
            logger.error("处理消息失败: {}", e.getMessage(), e);
            try {
                session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}"));
            } catch (IOException ioException) {
                logger.error("发送错误响应失败", ioException);
            }
        }
    }

    /**
     * 处理导航请求
     * 连接到 FastAPI 导航 WebSocket 服务
     *
     * @param session WebSocket 会话
     * @param msg 完整的消息（包含 task、location 等）
     */
    private void handleNavigationRequest(WebSocketSession session, AgentMessage msg) throws IOException {
        try {
            logger.info("处理导航请求: \"{}\"", msg.getTask());
            logger.info("GPS位置: {}", msg.getLocation());

            // 获取用户ID：优先使用消息中的 user_id，否则使用 session ID
            String userId = msg.getUserId() != null ? msg.getUserId() : session.getId();
            logger.info("用户ID: {}", userId);

            // 从消息中获取 GPS 位置
            Map<String, Object> origin;
            SensorData sensorData = new SensorData();

            if (msg.getLocation() != null) {
                // 使用 Android 发送的真实 GPS
                origin = Map.of(
                    "lon", msg.getLocation().getLongitude(),
                    "lat", msg.getLocation().getLatitude()
                );
                sensorData.setLatitude(msg.getLocation().getLatitude());
                sensorData.setLongitude(msg.getLocation().getLongitude());
                sensorData.setHeading(msg.getLocation().getHeading() != null ? msg.getLocation().getHeading() : 0.0f);
                sensorData.setAccuracy(msg.getLocation().getAccuracy() != null ? msg.getLocation().getAccuracy() : 10.0f);
                logger.info("使用 Android GPS: lon={}, lat={}", origin.get("lon"), origin.get("lat"));
            } else {
                // 没有GPS，使用默认位置（北京市中心）
                origin = Map.of("lon", 116.397428, "lat", 39.90923);
                sensorData.setHeading(0.0f);
                sensorData.setAccuracy(100.0f);
                logger.warn("未收到 GPS 数据，使用默认位置（北京）");
            }

            // 调用导航服务，传递完整任务（让模型提取目的地）
            navigationAgentService.startNavigation(
                session.getId(),
                session,
                userId,
                msg.getTask(),  // 完整任务，如"带我去最近的肯德基"
                origin,
                sensorData
            );

            // 标记会话类型为导航
            sessionTypes.put(session.getId(), "NAVIGATION");

        } catch (Exception e) {
            logger.error("处理导航请求失败", e);
            session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"导航处理失败: " + e.getMessage() + "\"}"));
        }
    }

    /**
     * 处理停止请求
     * 清理当前会话的所有资源，停止正在运行的任务
     */
    private void handleStopRequest(WebSocketSession session, String sessionId) throws IOException {
        try {
            logger.info("处理停止请求: sessionId={}", sessionId);

            // 获取当前会话类型
            String sessionType = sessionTypes.get(sessionId);
            logger.info("当前任务类型: {}", sessionType);

            // 根据不同类型清理资源
            if ("NAVIGATION".equals(sessionType)) {
                // 停止导航任务
                navigationAgentService.cancelNavigation(sessionId);
                // 发送停止障碍物检测消息
                session.sendMessage(new TextMessage("{\"status\":\"stopped\",\"type\":\"stop_obstacle_detection\",\"message\":\"导航已停止\"}"));
            } else if ("OBSTACLE".equals(sessionType)) {
                // 停止避障任务
                navigationAgentService.cleanup(sessionId);
                session.sendMessage(new TextMessage("{\"status\":\"stopped\",\"message\":\"环境感知已停止\"}"));
            } else {
                // 停止手机操控任务
                agentService.cleanup(sessionId);
                session.sendMessage(new TextMessage("{\"status\":\"stopped\",\"message\":\"任务已停止\"}"));
            }

            // 清理会话类型标记
            sessionTypes.remove(sessionId);

        } catch (Exception e) {
            logger.error("处理停止请求失败", e);
            session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"停止任务失败: " + e.getMessage() + "\"}"));
        }
    }

    /**
     * 处理暂停请求
     * 仅对导航任务有效，暂停导航但不释放资源
     */
    private void handlePauseRequest(WebSocketSession session, String sessionId) throws IOException {
        try {
            String sessionType = sessionTypes.get(sessionId);

            if ("NAVIGATION".equals(sessionType)) {
                navigationAgentService.pauseNavigation(sessionId);
                session.sendMessage(new TextMessage("{\"status\":\"success\",\"type\":\"paused\",\"message\":\"导航已暂停\"}"));
            } else {
                session.sendMessage(new TextMessage("{\"status\":\"warning\",\"message\":\"暂停功能仅适用于导航任务\"}"));
            }
        } catch (Exception e) {
            logger.error("处理暂停请求失败", e);
            session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"暂停失败: " + e.getMessage() + "\"}"));
        }
    }

    /**
     * 处理恢复请求
     * 仅对暂停的导航任务有效
     */
    private void handleResumeRequest(WebSocketSession session, String sessionId) throws IOException {
        try {
            String sessionType = sessionTypes.get(sessionId);

            if ("NAVIGATION".equals(sessionType)) {
                navigationAgentService.resumeNavigation(sessionId);
                session.sendMessage(new TextMessage("{\"status\":\"success\",\"type\":\"resumed\",\"message\":\"导航已恢复\"}"));
            } else {
                session.sendMessage(new TextMessage("{\"status\":\"warning\",\"message\":\"恢复功能仅适用于导航任务\"}"));
            }
        } catch (Exception e) {
            logger.error("处理恢复请求失败", e);
            session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"恢复失败: " + e.getMessage() + "\"}"));
        }
    }

    /**
     * 处理避障请求
     */
    private void handleObstacleRequest(WebSocketSession session, String task) throws IOException {
        try {
            // 通知客户端进行环境感知
            session.sendMessage(new TextMessage("{\"status\":\"obstacle_detection\",\"message\":\"正在感知周围环境\"}"));

            // TODO: 调用避障服务
            session.sendMessage(new TextMessage(
                "{\"status\":\"obstacle\",\"action\":\"finish\",\"message\":\"环境感知功能开发中\"}"
            ));

        } catch (Exception e) {
            logger.error("处理避障请求失败", e);
            session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"避障处理失败: " + e.getMessage() + "\"}"));
        }
    }

    /**
     * 处理位置更新请求（导航中使用）
     * 将 Android 的位置更新转发给 NavigationAgent 服务
     */
    private void handleLocationUpdateRequest(WebSocketSession session, String sessionId, AgentMessage msg) throws IOException {
        try {
            // 检查是否是导航会话
            String sessionType = sessionTypes.get(sessionId);
            if (!"NAVIGATION".equals(sessionType)) {
                logger.warn("位置更新仅对导航会话有效，当前类型: {}", sessionType);
                return;
            }

            // 从消息中获取 GPS 位置
            Map<String, Object> origin;
            SensorData sensorData = new SensorData();

            if (msg.getLocation() != null) {
                origin = Map.of(
                    "lon", msg.getLocation().getLongitude(),
                    "lat", msg.getLocation().getLatitude()
                );
                sensorData.setHeading(msg.getLocation().getHeading() != null ? msg.getLocation().getHeading() : 0.0f);
                sensorData.setAccuracy(msg.getLocation().getAccuracy() != null ? msg.getLocation().getAccuracy() : 10.0f);

                // 转发位置更新到 NavigationAgent 服务
                navigationAgentService.updateLocation(sessionId, origin, sensorData);
            } else {
                logger.warn("位置更新消息缺少位置信息");
            }

        } catch (Exception e) {
            logger.error("处理位置更新失败", e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // Connection reset by peer 是客户端正常断开，降低日志级别
        if (exception.getMessage() != null && exception.getMessage().contains("Connection reset by peer")) {
            logger.debug("WebSocket 客户端已断开: " + session.getId());
        } else {
            logger.error("WebSocket 传输错误", exception);
        }
        cleanupSession(session.getId());
        // 只在会话仍然打开时尝试关闭
        if (session.isOpen()) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception e) {
                // 忽略关闭时的错误，会话可能已经关闭
                logger.debug("关闭会话时出错（已忽略）: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("App 断开 Agent 通道: {}, status: {}", session.getId(), status);
        cleanupSession(session.getId());
    }

    /**
     * 清理会话资源
     * 根据会话类型清理对应的服务资源
     */
    private void cleanupSession(String sessionId) {
        String sessionType = sessionTypes.remove(sessionId);
        logger.info("清理会话: {}, 类型: {}", sessionId, sessionType);

        if ("NAVIGATION".equals(sessionType)) {
            navigationAgentService.cleanup(sessionId);
        } else {
            // PHONE_CONTROL 或其他类型使用 AgentService
            agentService.cleanup(sessionId);
        }
    }
}