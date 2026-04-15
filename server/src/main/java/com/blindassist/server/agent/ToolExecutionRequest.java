package com.blindassist.server.agent;

import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import org.springframework.web.socket.WebSocketSession;

/**
 * Execution request passed into the tool router.
 */
public class ToolExecutionRequest {

    private AgentState state;
    private VoiceCommandResponse.ToolCall toolCall;
    private AgentMessage agentMessage;
    private WebSocketSession webSocketSession;

    public AgentState getState() {
        return state;
    }

    public void setState(AgentState state) {
        this.state = state;
    }

    public VoiceCommandResponse.ToolCall getToolCall() {
        return toolCall;
    }

    public void setToolCall(VoiceCommandResponse.ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    public AgentMessage getAgentMessage() {
        return agentMessage;
    }

    public void setAgentMessage(AgentMessage agentMessage) {
        this.agentMessage = agentMessage;
    }

    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }

    public void setWebSocketSession(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }
}
