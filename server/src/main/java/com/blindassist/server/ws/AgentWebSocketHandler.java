package com.blindassist.server.ws;

import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.service.AgentLoopService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * Thin websocket entrypoint for Android agent events.
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final AgentLoopService agentLoopService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentWebSocketHandler(AgentLoopService agentLoopService) {
        this.agentLoopService = agentLoopService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        logger.info("App connected to agent channel: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMessage = objectMapper.readValue(payload, Map.class);
            String rawType = (String) rawMessage.get("type");
            if ("heartbeat".equals(rawType)) {
                session.sendMessage(new TextMessage("{\"type\":\"pong\",\"timestamp\":" + System.currentTimeMillis() + "}"));
                return;
            }

            AgentMessage agentMessage = objectMapper.readValue(payload, AgentMessage.class);
            String type = agentMessage.getType();
            if (type == null || type.isBlank()) {
                sendError(session, "Missing websocket message type.");
                return;
            }

            switch (type) {
                case "init" -> agentLoopService.handleInit(session, agentMessage);
                case "step" -> agentLoopService.handleStep(session, agentMessage);
                case "location_update" -> agentLoopService.handleLocationUpdate(session, agentMessage);
                case "pause" -> agentLoopService.handlePause(session, agentMessage);
                case "resume" -> agentLoopService.handleResume(session, agentMessage);
                case "stop" -> agentLoopService.handleStop(session, agentMessage);
                default -> {
                    logger.warn("Unknown websocket message type: {}", type);
                    sendError(session, "Unknown message type: " + type);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to process websocket message", e);
            sendError(session, e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("WebSocket transport error", exception);
        agentLoopService.cleanupSession(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logger.info("App disconnected from agent channel: {}, status: {}", session.getId(), status);
        agentLoopService.cleanupSession(session.getId());
    }

    private void sendError(WebSocketSession session, String message) throws Exception {
        if (!session.isOpen()) {
            return;
        }
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        session.sendMessage(new TextMessage("{\"status\":\"error\",\"message\":\"" + escaped + "\"}"));
    }
}
