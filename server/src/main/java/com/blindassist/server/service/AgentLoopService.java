package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.AgentStatus;
import com.blindassist.server.agent.DelegationStatus;
import com.blindassist.server.agent.Observation;
import com.blindassist.server.agent.PlannerAction;
import com.blindassist.server.agent.PlannerDecision;
import com.blindassist.server.agent.TaskTraceRecord;
import com.blindassist.server.agent.ToolExecutionRequest;
import com.blindassist.server.agent.ToolExecutionResult;
import com.blindassist.server.agent.ToolHistoryEntry;
import com.blindassist.server.agent.ToolKind;
import com.blindassist.server.agent.TraceEventType;
import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-level agent loop runtime with durable checkpoints and specialist handoffs.
 */
@Service
public class AgentLoopService {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_INTERNAL_REPLAN_DEPTH = 3;

    private final IntentClassificationService plannerService;
    private final ToolExecutionRouter toolExecutionRouter;
    private final AgentStateStore agentStateStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, AgentState> sessionStates = new ConcurrentHashMap<>();
    private final Map<String, String> connectionBindings = new ConcurrentHashMap<>();

    public AgentLoopService(
        IntentClassificationService plannerService,
        ToolExecutionRouter toolExecutionRouter,
        AgentStateStore agentStateStore
    ) {
        this.plannerService = plannerService;
        this.toolExecutionRouter = toolExecutionRouter;
        this.agentStateStore = agentStateStore;
    }

    public void handleInit(WebSocketSession session, AgentMessage message) throws IOException {
        String sessionKey = resolveDurableSessionKey(session, message);
        bindConnection(session, sessionKey);

        AgentState existingState = loadOrCreateState(sessionKey);
        if (existingState.getActiveToolName() != null) {
            toolExecutionRouter.cleanupActiveTool(existingState);
        }

        String userId = message.getUserId() != null ? message.getUserId() : sessionKey;
        existingState.setSessionId(sessionKey);
        existingState.resetForNewGoal(userId, message.getTask());
        cacheAndPersist(existingState);
        trace(existingState, TraceEventType.SESSION_INIT, existingState.getStatus().name(), null, "Session initialized.", compactMessagePayload(message));

        runPlanningLoop(session, existingState, message, 0);
    }

    public void handleStep(WebSocketSession session, AgentMessage message) throws IOException {
        AgentState state = resolveState(session, message);
        if (state == null) {
            sendJson(session, Map.of("status", "warning", "message", "No active session. Please start a task first."));
            return;
        }

        if (!isPhoneControlActive(state)) {
            sendJson(session, Map.of("status", "warning", "message", "Step is only valid during phone control."));
            return;
        }

        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName("phone_control.step");
        toolCall.setToolKind("SESSION");
        toolCall.setArguments(Map.of(
            "session_id", state.getSessionId(),
            "screenshot", message.getScreenshot() != null ? message.getScreenshot() : "",
            "screen_info", message.getScreenInfo() != null ? message.getScreenInfo() : ""
        ));

        ToolExecutionResult executionResult = executeSingleTool(session, state, message, toolCall);
        processExecutionResult(session, state, message, executionResult, 0, true);
    }

    public void handleLocationUpdate(WebSocketSession session, AgentMessage message) throws IOException {
        AgentState state = resolveState(session, message);
        if (state == null) {
            sendJson(session, Map.of("status", "warning", "message", "No active session. Please start a task first."));
            return;
        }

        if (!isNavigationActive(state)) {
            sendJson(session, Map.of("status", "warning", "message", "Location updates are only valid during navigation."));
            return;
        }

        Map<String, Object> origin = Map.of(
            "lon", message.getLocation() != null ? message.getLocation().getLongitude() : 116.397428,
            "lat", message.getLocation() != null ? message.getLocation().getLatitude() : 39.90923
        );
        Map<String, Object> sensorData = Map.of(
            "heading", message.getLocation() != null && message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0,
            "accuracy", message.getLocation() != null && message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0
        );

        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName("navigation.update");
        toolCall.setToolKind("SESSION");
        toolCall.setArguments(Map.of(
            "session_id", state.getSessionId(),
            "origin", origin,
            "sensor_data", sensorData
        ));

        ToolExecutionResult executionResult = executeSingleTool(session, state, message, toolCall);
        processExecutionResult(session, state, message, executionResult, 0, true);
    }

    public void handlePause(WebSocketSession session, AgentMessage message) throws IOException {
        handleControlTool(session, message, "control.pause");
    }

    public void handleResume(WebSocketSession session, AgentMessage message) throws IOException {
        handleControlTool(session, message, "control.resume");
    }

    public void handleStop(WebSocketSession session, AgentMessage message) throws IOException {
        handleControlTool(session, message, "control.stop");
    }

    public void cleanupSession(String connectionId) {
        detachConnection(connectionId);
    }

    public void detachConnection(String connectionId) {
        String sessionKey = connectionBindings.remove(connectionId);
        if (sessionKey == null) {
            return;
        }

        AgentState state = getState(sessionKey);
        if (state != null) {
            trace(state, TraceEventType.SESSION_CLOSED, state.getStatus().name(), state.getActiveToolName(), "Client connection closed.", Map.of("connection_id", connectionId));
            cacheAndPersist(state);
        }
    }

    public AgentState getState(String sessionId) {
        AgentState inMemory = sessionStates.get(sessionId);
        if (inMemory != null) {
            return inMemory;
        }

        Optional<AgentState> restored = agentStateStore.loadState(sessionId);
        restored.ifPresent(state -> sessionStates.put(sessionId, state));
        return restored.orElse(null);
    }

    private void runPlanningLoop(
        WebSocketSession session,
        AgentState state,
        AgentMessage message,
        int depth
    ) throws IOException {
        if (depth > MAX_INTERNAL_REPLAN_DEPTH) {
            state.setStatus(AgentStatus.FAILED);
            cacheAndPersist(state);
            trace(state, TraceEventType.SESSION_FAILED, state.getStatus().name(), state.getActiveToolName(), "Planner depth limit exceeded.", Map.of("depth", depth));
            sendJson(session, Map.of("status", "error", "message", "Planner depth limit exceeded."));
            return;
        }

        state.setStatus(AgentStatus.PLANNING);
        cacheAndPersist(state);
        trace(state, TraceEventType.PLANNER_REQUEST, state.getStatus().name(), state.getActiveToolName(), "Planner request issued.", compactMessagePayload(message));

        PlannerDecision decision = plannerService.plan(state, message);
        state.setPlannerTurn(state.getPlannerTurn() + 1);
        state.setLatestPlan(decision.getPlan() != null ? decision.getPlan() : new ArrayList<>());
        cacheAndPersist(state);
        trace(state, TraceEventType.PLANNER_RESPONSE, "success", decision.getPrimaryToolCall() != null ? decision.getPrimaryToolCall().getName() : null,
            "Planner returned " + decision.getPlannerAction() + ".", plannerPayload(decision));

        logger.info("Planner turn {} for session {} => action={}, intent={}, tool={}",
            state.getPlannerTurn(),
            state.getSessionId(),
            decision.getPlannerAction(),
            decision.getIntent(),
            decision.getPrimaryToolCall() != null ? decision.getPrimaryToolCall().getName() : "none");

        if (decision.getPlannerAction() == PlannerAction.FINISH) {
            state.setStatus(AgentStatus.FINISHED);
            cacheAndPersist(state);
            sendFinishMessage(session, state, decision);
            trace(state, TraceEventType.FINAL_RESPONSE, "success", state.getActiveToolName(), textOrFallback(decision.getFinalResponseText(), decision.getResponseText()), Map.of());
            return;
        }

        if (decision.getPlannerAction() == PlannerAction.ASK_USER) {
            state.setStatus(AgentStatus.WAITING_OBSERVATION);
            cacheAndPersist(state);
            sendPlannerMessage(session, "planner_response", textOrFallback(decision.getResponseText(), decision.getDetail()));
            return;
        }

        if (decision.getPlannerAction() == PlannerAction.WAIT_FOR_OBSERVATION) {
            state.setStatus(AgentStatus.WAITING_OBSERVATION);
            cacheAndPersist(state);
            if (decision.getResponseText() != null && !decision.getResponseText().isBlank()) {
                sendPlannerMessage(session, "planner_response", decision.getResponseText());
            }
            return;
        }

        VoiceCommandResponse.ToolCall toolCall = decision.getPrimaryToolCall();
        if (toolCall == null) {
            state.setStatus(AgentStatus.FAILED);
            cacheAndPersist(state);
            trace(state, TraceEventType.SESSION_FAILED, state.getStatus().name(), null, "Planner returned EXECUTE_TOOL without a tool call.", Map.of());
            sendJson(session, Map.of("status", "error", "message", "Planner returned EXECUTE_TOOL without a tool call."));
            return;
        }

        ToolExecutionResult executionResult = executeSingleTool(session, state, message, toolCall);
        processExecutionResult(session, state, message, executionResult, depth, ToolKind.fromValue(toolCall.getToolKind()) != ToolKind.CONTROL);
    }

    private ToolExecutionResult executeSingleTool(
        WebSocketSession session,
        AgentState state,
        AgentMessage message,
        VoiceCommandResponse.ToolCall toolCall
    ) throws IOException {
        ToolKind requestedToolKind = ToolKind.fromValue(toolCall.getToolKind());
        state.setStatus(AgentStatus.EXECUTING_TOOL);
        if (requestedToolKind != ToolKind.CONTROL) {
            state.setActiveToolName(toolCall.getName());
            state.setActiveToolKind(requestedToolKind);
        }
        cacheAndPersist(state);
        trace(state, TraceEventType.TOOL_CALL, "dispatched", toolCall.getName(), "Executing tool.", Map.of("arguments", toolCall.getArguments()));

        ToolExecutionRequest executionRequest = new ToolExecutionRequest();
        executionRequest.setState(state);
        executionRequest.setToolCall(toolCall);
        executionRequest.setAgentMessage(message);
        executionRequest.setWebSocketSession(session);

        ToolExecutionResult executionResult = toolExecutionRouter.execute(executionRequest);
        if (executionResult.getObservation() != null) {
            state.setLatestObservation(executionResult.getObservation());
        }

        appendToolHistory(state, toolCall, executionResult);

        if (executionResult.getClientMessage() != null && !executionResult.getClientMessage().isEmpty()) {
            sendJson(session, executionResult.getClientMessage());
        }

        if ("control.pause".equals(toolCall.getName()) && "success".equalsIgnoreCase(executionResult.getStatus())) {
            state.setStatus(AgentStatus.PAUSED);
        } else if ("control.stop".equals(toolCall.getName()) || "navigation.cancel".equals(toolCall.getName()) || "phone_control.reset".equals(toolCall.getName())) {
            state.setStatus(AgentStatus.FINISHED);
            state.setActiveToolName(null);
            state.setActiveToolKind(ToolKind.UNKNOWN);
        } else if ("control.resume".equals(toolCall.getName()) && "success".equalsIgnoreCase(executionResult.getStatus())) {
            state.setStatus(AgentStatus.WAITING_OBSERVATION);
        }

        cacheAndPersist(state);
        trace(state, TraceEventType.TOOL_RESULT, executionResult.getStatus(), toolCall.getName(),
            executionResult.getObservation() != null ? executionResult.getObservation().getSummary() : "Tool executed.",
            executionResult.getRawPayload());

        if (executionResult.getObservation() != null) {
            trace(state, TraceEventType.OBSERVATION_RECORDED, executionResult.getObservation().getStatus(), toolCall.getName(),
                executionResult.getObservation().getSummary(), Map.of(
                    "delegation_status", executionResult.getObservation().getDelegationStatus().name(),
                    "observation_type", executionResult.getObservation().getType()
                ));
        }

        if (state.getStatus() == AgentStatus.FINISHED) {
            trace(state, TraceEventType.SESSION_CLOSED, state.getStatus().name(), toolCall.getName(), "Session finished.", Map.of());
        }

        return executionResult;
    }

    private void processExecutionResult(
        WebSocketSession session,
        AgentState state,
        AgentMessage message,
        ToolExecutionResult executionResult,
        int depth,
        boolean allowReplan
    ) throws IOException {
        if (executionResult == null) {
            return;
        }

        if (!allowReplan) {
            if (state.getStatus() != AgentStatus.PAUSED && state.getStatus() != AgentStatus.FINISHED) {
                state.setStatus(AgentStatus.WAITING_OBSERVATION);
                cacheAndPersist(state);
            }
            return;
        }

        DelegationStatus delegationStatus = executionResult.getDelegationStatus();
        if (delegationStatus == DelegationStatus.FINISHED
            || delegationStatus == DelegationStatus.ASK_USER
            || delegationStatus == DelegationStatus.NEED_GLOBAL_REPLAN
            || executionResult.isShouldReplan()) {
            runPlanningLoop(session, state, message, depth + 1);
            return;
        }

        if (state.getStatus() != AgentStatus.PAUSED && state.getStatus() != AgentStatus.FINISHED) {
            state.setStatus(AgentStatus.WAITING_OBSERVATION);
            cacheAndPersist(state);
        }
    }

    private void handleControlTool(WebSocketSession session, AgentMessage message, String toolName) throws IOException {
        AgentState state = resolveState(session, message);
        if (state == null) {
            sendJson(session, Map.of("status", "warning", "message", "No active session to control."));
            return;
        }

        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName(toolName);
        toolCall.setToolKind("CONTROL");
        toolCall.setArguments(Map.of("session_id", state.getSessionId()));

        ToolExecutionResult executionResult = executeSingleTool(session, state, message, toolCall);
        processExecutionResult(session, state, message, executionResult, 0, false);
    }

    private void appendToolHistory(
        AgentState state,
        VoiceCommandResponse.ToolCall toolCall,
        ToolExecutionResult executionResult
    ) {
        ToolHistoryEntry entry = new ToolHistoryEntry();
        entry.setTurnIndex(state.getPlannerTurn());
        entry.setToolName(toolCall.getName());
        entry.setResultStatus(executionResult.getStatus());
        entry.setArgumentsSummary(summaryOf(toolCall.getArguments()));
        entry.setObservationSummary(executionResult.getObservation() != null ? executionResult.getObservation().getSummary() : null);
        state.getToolHistory().add(entry);
    }

    private void sendFinishMessage(
        WebSocketSession session,
        AgentState state,
        PlannerDecision decision
    ) throws IOException {
        Observation latestObservation = state.getLatestObservation();
        if (latestObservation != null && "obstacle".equals(latestObservation.getType())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("type", "obstacle_result");
            payload.put("message", textOrFallback(decision.getFinalResponseText(), latestObservation.getSummary()));
            payload.put("result", latestObservation.getStructuredData());
            sendJson(session, payload);
            return;
        }

        sendPlannerMessage(session, "final_response", textOrFallback(decision.getFinalResponseText(), decision.getResponseText()));
    }

    private void sendPlannerMessage(WebSocketSession session, String type, String message) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "success");
        payload.put("type", type);
        payload.put("message", message);
        sendJson(session, payload);
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        }
    }

    private AgentState resolveState(WebSocketSession session, AgentMessage message) {
        String sessionKey = resolveDurableSessionKey(session, message);
        bindConnection(session, sessionKey);
        AgentState state = sessionStates.get(sessionKey);
        if (state != null) {
            return state;
        }

        Optional<AgentState> restored = agentStateStore.loadState(sessionKey);
        if (restored.isPresent()) {
            sessionStates.put(sessionKey, restored.get());
            return restored.get();
        }
        return null;
    }

    private AgentState loadOrCreateState(String sessionKey) {
        AgentState inMemory = sessionStates.get(sessionKey);
        if (inMemory != null) {
            return inMemory;
        }

        AgentState restored = agentStateStore.loadState(sessionKey).orElseGet(AgentState::new);
        restored.setSessionId(sessionKey);
        sessionStates.put(sessionKey, restored);
        return restored;
    }

    private String resolveDurableSessionKey(WebSocketSession session, AgentMessage message) {
        if (message != null && message.getClientSessionId() != null && !message.getClientSessionId().isBlank()) {
            return message.getClientSessionId();
        }

        String bound = connectionBindings.get(session.getId());
        if (bound != null && !bound.isBlank()) {
            return bound;
        }

        logger.warn("Missing client_session_id for connection {}. Falling back to websocket session id; durable recovery will not be guaranteed.", session.getId());
        return session.getId();
    }

    private void bindConnection(WebSocketSession session, String sessionKey) {
        connectionBindings.put(session.getId(), sessionKey);
    }

    private void cacheAndPersist(AgentState state) {
        sessionStates.put(state.getSessionId(), state);
        agentStateStore.saveState(state);
    }

    private void trace(
        AgentState state,
        TraceEventType eventType,
        String status,
        String toolName,
        String summary,
        Map<String, Object> payload
    ) {
        TaskTraceRecord traceRecord = new TaskTraceRecord();
        traceRecord.setSessionId(state.getSessionId());
        traceRecord.setUserId(state.getUserId());
        traceRecord.setTurnIndex(state.getPlannerTurn());
        traceRecord.setEventType(eventType);
        traceRecord.setStatus(status);
        traceRecord.setToolName(toolName);
        traceRecord.setSummary(summary);
        traceRecord.setPayload(payload != null ? payload : Map.of());
        agentStateStore.appendTrace(traceRecord);
    }

    private Map<String, Object> compactMessagePayload(AgentMessage message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", message.getType());
        payload.put("task", message.getTask());
        payload.put("client_session_id", message.getClientSessionId());
        payload.put("has_screenshot", message.getScreenshot() != null && !message.getScreenshot().isBlank());
        payload.put("screen_info_present", message.getScreenInfo() != null && !message.getScreenInfo().isBlank());
        payload.put("has_location", message.getLocation() != null);
        return payload;
    }

    private Map<String, Object> plannerPayload(PlannerDecision decision) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("planner_action", decision.getPlannerAction() != null ? decision.getPlannerAction().name() : null);
        payload.put("intent", decision.getIntent());
        payload.put("tool_name", decision.getPrimaryToolCall() != null ? decision.getPrimaryToolCall().getName() : null);
        payload.put("plan", decision.getPlan());
        payload.put("response_text", decision.getResponseText());
        payload.put("final_response_text", decision.getFinalResponseText());
        return payload;
    }

    private boolean isPhoneControlActive(AgentState state) {
        return "phone_control.start".equals(state.getActiveToolName()) || "phone_control.step".equals(state.getActiveToolName());
    }

    private boolean isNavigationActive(AgentState state) {
        return "navigation.start".equals(state.getActiveToolName()) || "navigation.update".equals(state.getActiveToolName());
    }

    private String textOrFallback(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null ? fallback : "";
    }

    private String summaryOf(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        String summary = arguments.toString();
        if (summary.length() > 240) {
            return summary.substring(0, 240) + "...";
        }
        return summary;
    }
}
