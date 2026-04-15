package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.AgentStatus;
import com.blindassist.server.agent.DelegationStatus;
import com.blindassist.server.agent.Observation;
import com.blindassist.server.agent.PlannerAction;
import com.blindassist.server.agent.PlannerDecision;
import com.blindassist.server.agent.ToolExecutionResult;
import com.blindassist.server.agent.ToolKind;
import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLoopServiceTest {

    @Mock
    private IntentClassificationService plannerService;

    @Mock
    private ToolExecutionRouter toolExecutionRouter;

    @Mock
    private AgentStateStore agentStateStore;

    @Mock
    private WebSocketSession session;

    private AgentLoopService agentLoopService;

    @BeforeEach
    void setUp() {
        agentLoopService = new AgentLoopService(plannerService, toolExecutionRouter, agentStateStore);
        when(session.getId()).thenReturn("session-1");
    }

    @Test
    void initNavigationLeavesSessionWaitingForObservation() throws Exception {
        when(plannerService.plan(any(), any())).thenReturn(
            plannerDecision("NAVIGATION", PlannerAction.EXECUTE_TOOL, toolCall("navigation.start", "SESSION"), null, "navigation")
        );
        when(toolExecutionRouter.execute(any())).thenReturn(dispatchedResult("navigation", "Navigation started."));

        agentLoopService.handleInit(session, initMessage("Take me to the station"));

        AgentState state = agentLoopService.getState("session-1");
        assertEquals(AgentStatus.WAITING_OBSERVATION, state.getStatus());
        assertEquals("navigation.start", state.getActiveToolName());
        assertEquals(ToolKind.SESSION, state.getActiveToolKind());
        assertEquals(1, state.getPlannerTurn());
        assertEquals(1, state.getToolHistory().size());
    }

    @Test
    void oneShotObstacleTriggersReplanAndFinish() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(plannerService.plan(any(), any())).thenReturn(
            plannerDecision("OBSTACLE", PlannerAction.EXECUTE_TOOL, toolCall("obstacle.detect_single_frame", "ONE_SHOT"), null, "obstacle"),
            plannerDecision("OBSTACLE", PlannerAction.FINISH, null, "There is a chair ahead.", "obstacle")
        );

        ToolExecutionResult obstacleResult = new ToolExecutionResult();
        obstacleResult.setStatus("success");
        Observation observation = Observation.of("obstacle", "success", "There is a chair ahead.", "obstacle.detect_single_frame");
        observation.setDelegationStatus(DelegationStatus.NEED_GLOBAL_REPLAN);
        obstacleResult.setObservation(observation);
        obstacleResult.setShouldReplan(true);
        obstacleResult.setRawPayload(Map.of("instruction", "There is a chair ahead."));
        when(toolExecutionRouter.execute(any())).thenReturn(obstacleResult);

        agentLoopService.handleInit(session, initMessage("What is ahead of me?"));

        AgentState state = agentLoopService.getState("session-1");
        assertEquals(AgentStatus.FINISHED, state.getStatus());
        assertEquals(2, state.getPlannerTurn());

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains("\"type\":\"obstacle_result\""));
        assertTrue(messageCaptor.getValue().getPayload().contains("There is a chair ahead."));
    }

    @Test
    void pauseResumeStopTransitionsAreTrackedInState() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(plannerService.plan(any(), any())).thenReturn(
            plannerDecision("NAVIGATION", PlannerAction.EXECUTE_TOOL, toolCall("navigation.start", "SESSION"), null, "navigation")
        );
        when(toolExecutionRouter.execute(any())).thenReturn(
            dispatchedResult("navigation", "Navigation started."),
            controlResult("success", "Navigation paused.", Map.of("status", "success", "type", "paused", "message", "Navigation paused.")),
            controlResult("success", "Navigation resumed.", Map.of("status", "success", "type", "resumed", "message", "Navigation resumed.")),
            controlResult("success", "Navigation stopped.", Map.of("status", "stopped", "message", "Navigation stopped."))
        );

        agentLoopService.handleInit(session, initMessage("Start navigation"));
        agentLoopService.handlePause(session, controlMessage("pause"));
        assertEquals(AgentStatus.PAUSED, agentLoopService.getState("session-1").getStatus());

        agentLoopService.handleResume(session, controlMessage("resume"));
        assertEquals(AgentStatus.WAITING_OBSERVATION, agentLoopService.getState("session-1").getStatus());

        agentLoopService.handleStop(session, controlMessage("stop"));
        AgentState state = agentLoopService.getState("session-1");
        assertEquals(AgentStatus.FINISHED, state.getStatus());
        assertNull(state.getActiveToolName());
        assertEquals(ToolKind.UNKNOWN, state.getActiveToolKind());
    }

    @Test
    void stepRestoresStateFromStoreBeforeContinuing() throws Exception {
        AgentState restoredState = new AgentState();
        restoredState.setSessionId("client-1");
        restoredState.setUserId("user-1");
        restoredState.setGoal("Open the app");
        restoredState.setActiveToolName("phone_control.start");
        restoredState.setActiveToolKind(ToolKind.SESSION);
        restoredState.setStatus(AgentStatus.WAITING_OBSERVATION);
        when(agentStateStore.loadState("client-1")).thenReturn(Optional.of(restoredState));

        ToolExecutionResult stepResult = ToolExecutionResult.dispatched("success");
        Observation observation = Observation.of("phone_control", "success", "Tap the confirm button.", "phone_control.step");
        observation.setDelegationStatus(DelegationStatus.CONTINUE);
        stepResult.setObservation(observation);
        stepResult.setClientMessage(Map.of("status", "success", "type", "action", "message", "Tap confirm"));
        when(toolExecutionRouter.execute(any())).thenReturn(stepResult);
        when(session.isOpen()).thenReturn(true);

        AgentMessage message = new AgentMessage();
        message.setType("step");
        message.setClientSessionId("client-1");
        message.setScreenshot("base64-data");
        message.setScreenInfo("screen");

        agentLoopService.handleStep(session, message);

        AgentState state = agentLoopService.getState("client-1");
        assertEquals(AgentStatus.WAITING_OBSERVATION, state.getStatus());
        assertEquals("phone_control.step", state.getActiveToolName());
        verify(agentStateStore).loadState("client-1");
    }

    private AgentMessage initMessage(String task) {
        AgentMessage message = new AgentMessage();
        message.setType("init");
        message.setTask(task);
        return message;
    }

    private AgentMessage controlMessage(String type) {
        AgentMessage message = new AgentMessage();
        message.setType(type);
        return message;
    }

    private PlannerDecision plannerDecision(
        String intent,
        PlannerAction action,
        VoiceCommandResponse.ToolCall toolCall,
        String finalResponseText,
        String feature
    ) {
        PlannerDecision decision = new PlannerDecision();
        decision.setIntent(intent);
        decision.setDetail(intent);
        decision.setPlannerAction(action);
        decision.setResponseText(finalResponseText);
        decision.setFinalResponseText(finalResponseText);
        decision.setPlan(java.util.List.of("step"));
        if (toolCall != null) {
            decision.setToolCalls(java.util.List.of(toolCall));
        }
        VoiceCommandResponse response = VoiceCommandResponse.of(feature, intent);
        response.setPlannerAction(action.name());
        response.setFinalResponseText(finalResponseText);
        decision.setRawResponse(response);
        return decision;
    }

    private VoiceCommandResponse.ToolCall toolCall(String name, String toolKind) {
        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName(name);
        toolCall.setToolKind(toolKind);
        toolCall.setArguments(Map.of());
        return toolCall;
    }

    private ToolExecutionResult dispatchedResult(String type, String summary) {
        ToolExecutionResult result = ToolExecutionResult.dispatched("dispatched");
        result.setObservation(Observation.of(type, "pending", summary, type + ".start"));
        return result;
    }

    private ToolExecutionResult controlResult(String status, String summary, Map<String, Object> clientMessage) {
        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus(status);
        result.setObservation(Observation.of("control", status, summary, "control"));
        result.setClientMessage(clientMessage);
        return result;
    }
}
