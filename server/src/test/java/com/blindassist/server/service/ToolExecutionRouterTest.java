package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.DelegationStatus;
import com.blindassist.server.agent.ToolExecutionRequest;
import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.config.FastApiProperties;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutionRouterTest {

    @Mock
    private NavigationToolService navigationToolService;

    @Mock
    private PhoneControlToolService phoneControlToolService;

    @Mock
    private ObstacleDetectionService obstacleDetectionService;

    @Mock
    private TtsMessageQueue ttsMessageQueue;

    private ToolExecutionRouter toolExecutionRouter;

    @BeforeEach
    void setUp() {
        FastApiProperties fastApiProperties = new FastApiProperties();
        FastApiProperties.Service obstacle = new FastApiProperties.Service();
        obstacle.setBaseUrl("http://localhost:8004");
        fastApiProperties.setObstacle(obstacle);
        toolExecutionRouter = new ToolExecutionRouter(
            navigationToolService,
            phoneControlToolService,
            obstacleDetectionService,
            ttsMessageQueue,
            fastApiProperties
        );
    }

    @Test
    void phoneControlFinishedEnvelopeRequestsGlobalReplan() {
        AgentState state = new AgentState();
        state.setSessionId("session-1");
        state.setUserId("user-1");

        AgentMessage message = new AgentMessage();
        message.setTask("Finish the flow");
        message.setScreenshot("base64");
        message.setScreenInfo("screen");

        when(phoneControlToolService.start("session-1", "Finish the flow", "base64", "screen")).thenReturn(Map.of(
            "status", "success",
            "observation_type", "phone_control",
            "delegation_status", "FINISHED",
            "summary", "Phone flow completed.",
            "structured_data", Map.of("finished", true),
            "source_tool", "phone_control.start",
            "timestamp", 1L,
            "client_message", Map.of("status", "success", "finished", true)
        ));

        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setState(state);
        request.setAgentMessage(message);
        request.setToolCall(toolCall("phone_control.start", "SESSION"));

        var result = toolExecutionRouter.execute(request);

        assertEquals(DelegationStatus.FINISHED, result.getDelegationStatus());
        assertTrue(result.isShouldReplan());
        assertNotNull(result.getClientMessage());
    }

    @Test
    void navigationStartEnqueuesTtsAndProvidesObstacleClientMessage() {
        AgentState state = new AgentState();
        state.setSessionId("session-1");
        state.setUserId("user-1");

        AgentMessage message = new AgentMessage();
        message.setTask("Navigate to the station");

        when(navigationToolService.start("session-1", "Navigate to the station", Map.of("lon", 116.397428, "lat", 39.90923), Map.of("heading", 0.0, "accuracy", 10.0))).thenReturn(Map.of(
            "status", "success",
            "observation_type", "navigation",
            "delegation_status", "CONTINUE",
            "summary", "Walk forward for ten meters.",
            "structured_data", Map.of("type", "route_planned"),
            "source_tool", "navigation.start",
            "timestamp", 1L,
            "tts_message", "Walk forward for ten meters."
        ));

        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setState(state);
        request.setAgentMessage(message);
        request.setToolCall(toolCall("navigation.start", "SESSION"));

        var result = toolExecutionRouter.execute(request);

        verify(ttsMessageQueue).enqueue("user-1", "Walk forward for ten meters.", TtsPriority.NORMAL, "navigation");
        assertEquals(DelegationStatus.CONTINUE, result.getDelegationStatus());
        assertEquals("start_obstacle_detection", result.getClientMessage().get("type"));
    }

    private VoiceCommandResponse.ToolCall toolCall(String name, String toolKind) {
        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName(name);
        toolCall.setToolKind(toolKind);
        toolCall.setArguments(Map.of());
        return toolCall;
    }
}
