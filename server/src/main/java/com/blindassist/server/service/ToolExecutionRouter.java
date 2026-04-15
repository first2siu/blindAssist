package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.DelegationStatus;
import com.blindassist.server.agent.Observation;
import com.blindassist.server.agent.ToolExecutionRequest;
import com.blindassist.server.agent.ToolExecutionResult;
import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.config.FastApiProperties;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapts planner tool calls to specialist HTTP tool endpoints.
 */
@Service
public class ToolExecutionRouter {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionRouter.class);

    private final NavigationToolService navigationToolService;
    private final PhoneControlToolService phoneControlToolService;
    private final ObstacleDetectionService obstacleDetectionService;
    private final TtsMessageQueue ttsMessageQueue;
    private final FastApiProperties fastApiProperties;

    public ToolExecutionRouter(
        NavigationToolService navigationToolService,
        PhoneControlToolService phoneControlToolService,
        ObstacleDetectionService obstacleDetectionService,
        TtsMessageQueue ttsMessageQueue,
        FastApiProperties fastApiProperties
    ) {
        this.navigationToolService = navigationToolService;
        this.phoneControlToolService = phoneControlToolService;
        this.obstacleDetectionService = obstacleDetectionService;
        this.ttsMessageQueue = ttsMessageQueue;
        this.fastApiProperties = fastApiProperties;
    }

    public ToolExecutionResult execute(ToolExecutionRequest request) {
        VoiceCommandResponse.ToolCall toolCall = request.getToolCall();
        AgentState state = request.getState();
        AgentMessage message = request.getAgentMessage();

        if (toolCall == null) {
            return errorResult("runtime", "No tool call to execute.");
        }

        String toolName = toolCall.getName();
        logger.info("Executing tool {} for session {}", toolName, state.getSessionId());

        try {
            return switch (toolName) {
                case "navigation.start" -> startNavigation(state, message, toolCall);
                case "navigation.update" -> updateNavigation(state, message, toolCall);
                case "phone_control.start" -> startPhoneControl(state, message);
                case "phone_control.step" -> stepPhoneControl(state, message);
                case "obstacle.detect_single_frame" -> detectObstacleSingleFrame(state, message);
                case "control.pause" -> pauseCurrentTool(state, message);
                case "control.resume" -> resumeCurrentTool(state, message);
                case "control.stop", "navigation.cancel", "phone_control.reset" -> stopCurrentTool(state);
                default -> errorResult(toolName, "Unsupported tool: " + toolName);
            };
        } catch (Exception e) {
            logger.error("Tool execution failed for {}", toolName, e);
            ToolExecutionResult result = errorResult(toolName, e.getMessage());
            result.setClientMessage(Map.of("status", "error", "message", "Tool execution failed: " + e.getMessage()));
            return result;
        }
    }

    public void cleanupActiveTool(AgentState state) {
        if (state == null || state.getSessionId() == null || state.getSessionId().isBlank()) {
            return;
        }

        String sessionId = state.getSessionId();
        String activeToolName = state.getActiveToolName();

        if (activeToolName == null || activeToolName.isBlank()) {
            navigationToolService.cancel(sessionId);
            phoneControlToolService.reset(sessionId);
            return;
        }

        if (isNavigationTool(activeToolName)) {
            navigationToolService.cancel(sessionId);
        }
        if (isPhoneControlTool(activeToolName)) {
            phoneControlToolService.reset(sessionId);
        }
    }

    private ToolExecutionResult startNavigation(
        AgentState state,
        AgentMessage message,
        VoiceCommandResponse.ToolCall toolCall
    ) {
        Map<String, Object> args = toolCall.getArguments() != null ? toolCall.getArguments() : Map.of();
        Map<String, Object> response = navigationToolService.start(
            state.getSessionId(),
            message.getTask(),
            extractOrigin(message, args),
            extractSensorData(message, args)
        );

        ToolExecutionResult result = buildResultFromEnvelope(response, "navigation", "navigation.start");
        if (result.getClientMessage() == null) {
            result.setClientMessage(defaultNavigationStartMessage());
        }
        enqueueTtsIfPresent(state, result);
        return result;
    }

    private ToolExecutionResult updateNavigation(
        AgentState state,
        AgentMessage message,
        VoiceCommandResponse.ToolCall toolCall
    ) {
        if (!isNavigationTool(state.getActiveToolName())) {
            ToolExecutionResult result = new ToolExecutionResult();
            result.setStatus("warning");
            Observation observation = Observation.of("navigation", "warning", "Location updates are only valid during navigation.", "navigation.update");
            observation.setDelegationStatus(DelegationStatus.CONTINUE);
            result.setObservation(observation);
            result.setClientMessage(Map.of("status", "warning", "message", "Location update ignored because navigation is not active."));
            return result;
        }

        Map<String, Object> args = toolCall.getArguments() != null ? toolCall.getArguments() : Map.of();
        Map<String, Object> response = navigationToolService.update(
            state.getSessionId(),
            extractOrigin(message, args),
            extractSensorData(message, args)
        );

        ToolExecutionResult result = buildResultFromEnvelope(response, "navigation", "navigation.update");
        enqueueTtsIfPresent(state, result);
        return result;
    }

    private ToolExecutionResult startPhoneControl(AgentState state, AgentMessage message) {
        Map<String, Object> response = phoneControlToolService.start(
            state.getSessionId(),
            message.getTask(),
            message.getScreenshot() != null ? message.getScreenshot() : "",
            message.getScreenInfo() != null ? message.getScreenInfo() : ""
        );
        return buildResultFromEnvelope(response, "phone_control", "phone_control.start");
    }

    private ToolExecutionResult stepPhoneControl(AgentState state, AgentMessage message) {
        if (!isPhoneControlTool(state.getActiveToolName())) {
            ToolExecutionResult result = new ToolExecutionResult();
            result.setStatus("warning");
            Observation observation = Observation.of("phone_control", "warning", "Step is only valid during phone control.", "phone_control.step");
            observation.setDelegationStatus(DelegationStatus.CONTINUE);
            result.setObservation(observation);
            result.setClientMessage(Map.of("status", "warning", "message", "Step ignored because phone control is not active."));
            return result;
        }

        Map<String, Object> response = phoneControlToolService.step(
            state.getSessionId(),
            message.getScreenshot() != null ? message.getScreenshot() : "",
            message.getScreenInfo() != null ? message.getScreenInfo() : ""
        );
        return buildResultFromEnvelope(response, "phone_control", "phone_control.step");
    }

    private ToolExecutionResult detectObstacleSingleFrame(AgentState state, AgentMessage message) {
        Map<String, Object> sensorData = new HashMap<>();
        sensorData.put("query", message.getTask());
        if (message.getLocation() != null) {
            sensorData.put("heading", message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0);
            sensorData.put("accuracy", message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0);
            sensorData.put("latitude", message.getLocation().getLatitude());
            sensorData.put("longitude", message.getLocation().getLongitude());
        }

        Map<String, Object> response = obstacleDetectionService.detectSingleFrame(message.getScreenshot(), sensorData);
        ToolExecutionResult result = buildResultFromEnvelope(response, "obstacle", "obstacle.detect_single_frame");
        enqueueTtsIfPresent(state, result);
        return result;
    }

    private ToolExecutionResult pauseCurrentTool(AgentState state, AgentMessage message) {
        if (isNavigationTool(state.getActiveToolName())) {
            Map<String, Object> response = navigationToolService.pause(state.getSessionId());
            ToolExecutionResult result = buildResultFromEnvelope(response, "control", "control.pause");
            if (result.getClientMessage() == null) {
                result.setClientMessage(Map.of("status", "success", "type", "paused", "message", "Navigation paused."));
            }
            enqueueTtsIfPresent(state, result);
            return result;
        }

        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus("warning");
        Observation observation = Observation.of("control", "warning", "Pause is only supported for navigation.", "control.pause");
        observation.setDelegationStatus(DelegationStatus.CONTINUE);
        result.setObservation(observation);
        result.setClientMessage(Map.of("status", "warning", "message", "Pause is only supported for navigation."));
        return result;
    }

    private ToolExecutionResult resumeCurrentTool(AgentState state, AgentMessage message) {
        if (isNavigationTool(state.getActiveToolName())) {
            Map<String, Object> response = navigationToolService.resume(
                state.getSessionId(),
                extractOrigin(message, Map.of()),
                extractSensorData(message, Map.of())
            );
            ToolExecutionResult result = buildResultFromEnvelope(response, "control", "control.resume");
            if (result.getClientMessage() == null) {
                result.setClientMessage(Map.of("status", "success", "type", "resumed", "message", "Navigation resumed."));
            }
            enqueueTtsIfPresent(state, result);
            return result;
        }

        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus("warning");
        Observation observation = Observation.of("control", "warning", "Resume is only supported for navigation.", "control.resume");
        observation.setDelegationStatus(DelegationStatus.CONTINUE);
        result.setObservation(observation);
        result.setClientMessage(Map.of("status", "warning", "message", "Resume is only supported for navigation."));
        return result;
    }

    private ToolExecutionResult stopCurrentTool(AgentState state) {
        if (isNavigationTool(state.getActiveToolName())) {
            Map<String, Object> response = navigationToolService.cancel(state.getSessionId());
            ToolExecutionResult result = buildResultFromEnvelope(response, "control", "control.stop");
            if (result.getClientMessage() == null) {
                result.setClientMessage(Map.of(
                    "status", "stopped",
                    "type", "stop_obstacle_detection",
                    "message", "Navigation stopped."
                ));
            }
            return result;
        }

        if (isPhoneControlTool(state.getActiveToolName())) {
            phoneControlToolService.reset(state.getSessionId());
            ToolExecutionResult result = new ToolExecutionResult();
            result.setStatus("success");
            Observation observation = Observation.of("control", "success", "Phone control stopped.", "control.stop");
            observation.setDelegationStatus(DelegationStatus.FINISHED);
            result.setObservation(observation);
            result.setClientMessage(Map.of("status", "stopped", "message", "Phone control stopped."));
            return result;
        }

        navigationToolService.cancel(state.getSessionId());
        phoneControlToolService.reset(state.getSessionId());

        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus("success");
        Observation observation = Observation.of("control", "success", "Session stopped.", "control.stop");
        observation.setDelegationStatus(DelegationStatus.FINISHED);
        result.setObservation(observation);
        result.setClientMessage(Map.of("status", "stopped", "message", "Session stopped."));
        return result;
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult buildResultFromEnvelope(Map<String, Object> envelope, String defaultType, String sourceTool) {
        Map<String, Object> safeEnvelope = envelope != null ? envelope : Map.of();
        String status = stringValue(safeEnvelope.get("status"), "success");
        String summary = stringValue(
            safeEnvelope.get("summary"),
            stringValue(safeEnvelope.get("instruction"),
                stringValue(safeEnvelope.get("message"), stringValue(safeEnvelope.get("raw_response"), "")))
        );
        String observationType = stringValue(
            safeEnvelope.get("observation_type"),
            stringValue(safeEnvelope.get("type"), defaultType)
        );
        DelegationStatus delegationStatus = DelegationStatus.fromValue(stringValue(safeEnvelope.get("delegation_status"), null));
        if (delegationStatus == DelegationStatus.UNKNOWN) {
            if ("error".equalsIgnoreCase(status)) {
                delegationStatus = DelegationStatus.ASK_USER;
            } else if (sourceTool.startsWith("obstacle.")) {
                delegationStatus = DelegationStatus.NEED_GLOBAL_REPLAN;
            } else {
                delegationStatus = DelegationStatus.CONTINUE;
            }
        }

        Observation observation = new Observation();
        observation.setType(observationType);
        observation.setStatus(status);
        observation.setDelegationStatus(delegationStatus);
        observation.setSummary(summary);
        observation.setSourceTool(stringValue(safeEnvelope.get("source_tool"), sourceTool));
        observation.setTimestamp(longValue(safeEnvelope.get("timestamp"), System.currentTimeMillis()));

        Object structuredData = safeEnvelope.get("structured_data");
        if (structuredData instanceof Map<?, ?> structuredMap) {
            observation.setStructuredData((Map<String, Object>) structuredMap);
        } else {
            observation.setStructuredData(new HashMap<>(safeEnvelope));
        }

        Map<String, Object> clientMessage = normalizeClientMessage(safeEnvelope.get("client_message"), status, summary);
        observation.setClientMessage(clientMessage);
        observation.setTtsMessage(stringValue(safeEnvelope.get("tts_message"), null));

        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus(status);
        result.setObservation(observation);
        result.setRawPayload(new HashMap<>(safeEnvelope));
        result.setClientMessage(clientMessage);
        result.setShouldReplan(
            delegationStatus == DelegationStatus.FINISHED
                || delegationStatus == DelegationStatus.ASK_USER
                || delegationStatus == DelegationStatus.NEED_GLOBAL_REPLAN
        );
        return result;
    }

    private ToolExecutionResult errorResult(String sourceTool, String message) {
        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus("error");
        Observation observation = Observation.of("runtime", "error", message, sourceTool);
        observation.setDelegationStatus(DelegationStatus.ASK_USER);
        result.setObservation(observation);
        result.setShouldReplan(true);
        return result;
    }

    private Map<String, Object> extractOrigin(AgentMessage message, Map<String, Object> args) {
        Object originValue = args.get("origin");
        if (originValue instanceof Map<?, ?> originMap && originMap.containsKey("lon") && originMap.containsKey("lat")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) originMap;
            return normalized;
        }

        if (message != null && message.getLocation() != null) {
            return Map.of(
                "lon", message.getLocation().getLongitude(),
                "lat", message.getLocation().getLatitude()
            );
        }

        return Map.of("lon", 116.397428, "lat", 39.90923);
    }

    private Map<String, Object> extractSensorData(AgentMessage message, Map<String, Object> args) {
        Object sensorValue = args.get("sensor_data");
        if (sensorValue instanceof Map<?, ?> sensorMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) sensorMap;
            return normalized;
        }

        if (message != null && message.getLocation() != null) {
            return Map.of(
                "heading", message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0,
                "accuracy", message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0
            );
        }

        return Map.of("heading", 0.0, "accuracy", 10.0);
    }

    private Map<String, Object> normalizeClientMessage(Object rawValue, String status, String summary) {
        if (rawValue instanceof Map<?, ?> clientMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = (Map<String, Object>) clientMap;
            return normalized;
        }
        if (rawValue instanceof String rawMessage && !rawMessage.isBlank()) {
            return Map.of("status", status, "message", rawMessage);
        }
        return null;
    }

    private void enqueueTtsIfPresent(AgentState state, ToolExecutionResult result) {
        if (state == null || state.getUserId() == null || result == null || result.getObservation() == null) {
            return;
        }

        String ttsMessage = result.getObservation().getTtsMessage();
        if (ttsMessage == null || ttsMessage.isBlank()) {
            return;
        }

        TtsPriority priority = result.getDelegationStatus() == DelegationStatus.FINISHED
            ? TtsPriority.HIGH
            : TtsPriority.NORMAL;
        ttsMessageQueue.enqueue(state.getUserId(), ttsMessage, priority, result.getObservation().getType());
    }

    private Map<String, Object> defaultNavigationStartMessage() {
        String obstacleBaseUrl = fastApiProperties.getObstacle() != null ? fastApiProperties.getObstacle().getBaseUrl() : "http://localhost:8004";
        String obstacleUrl = obstacleBaseUrl;
        if (obstacleUrl.startsWith("http://")) {
            obstacleUrl = "ws://" + obstacleUrl.substring("http://".length());
        } else if (obstacleUrl.startsWith("https://")) {
            obstacleUrl = "wss://" + obstacleUrl.substring("https://".length());
        }
        if (!obstacleUrl.endsWith("/ws")) {
            obstacleUrl = obstacleUrl + "/ws";
        }

        return Map.of(
            "status", "success",
            "type", "start_obstacle_detection",
            "message", "Navigation started, obstacle detection is now available.",
            "obstacle_url", obstacleUrl
        );
    }

    private boolean isNavigationTool(String toolName) {
        return "navigation.start".equals(toolName) || "navigation.update".equals(toolName);
    }

    private boolean isPhoneControlTool(String toolName) {
        return "phone_control.start".equals(toolName) || "phone_control.step".equals(toolName);
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return fallback;
    }
}
