package com.blindassist.server.ws;

import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.service.AgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentWebSocketHandler(AgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println("App 接入 Agent 通道: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 1. 解析 App 发来的 JSON
        AgentMessage msg = objectMapper.readValue(message.getPayload(), AgentMessage.class);
        String sessionId = session.getId();

        // 2. 根据类型调度给 Service
        if ("init".equals(msg.getType())) {
            agentService.startTask(sessionId, session, msg.getTask(), msg.getScreenshot(), msg.getScreenInfo());
        } else if ("step".equals(msg.getType())) {
            agentService.processStep(sessionId, msg.getScreenshot(), msg.getScreenInfo());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        agentService.cleanup(session.getId());
        session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        System.out.println("App 断开 Agent 通道: " + session.getId());
        agentService.cleanup(session.getId());
    }
}