package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.Observation;
import com.blindassist.server.agent.PlannerDecision;
import com.blindassist.server.agent.ToolHistoryEntry;
import com.blindassist.server.agent.ToolKind;
import com.blindassist.server.api.dto.AgentMessage;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.config.FastApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner client used by the Java runtime.
 */
@Service
public class IntentClassificationService {

    private static final Logger logger = LoggerFactory.getLogger(IntentClassificationService.class);

    private static final List<String> STOP_KEYWORDS = List.of("停止", "取消", "结束", "退出", "stop", "cancel");
    private static final List<String> PAUSE_KEYWORDS = List.of("暂停", "等一下", "pause", "wait");
    private static final List<String> RESUME_KEYWORDS = List.of("继续", "恢复", "resume", "continue");
    private static final List<String> NAVIGATION_KEYWORDS = List.of("去", "到", "导航", "怎么走", "带我去", "前往");
    private static final List<String> OBSTACLE_KEYWORDS = List.of("前面", "周围", "障碍", "环境", "看路", "路况");
    private static final List<String> SUPPORTED_TOOLS = List.of(
        "control.pause",
        "control.resume",
        "control.stop",
        "navigation.start",
        "navigation.update",
        "navigation.cancel",
        "obstacle.detect_single_frame",
        "phone_control.start",
        "phone_control.step",
        "phone_control.reset"
    );

    private final FastApiProperties fastApiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public IntentClassificationService(FastApiProperties fastApiProperties) {
        this.fastApiProperties = fastApiProperties;
    }

    public VoiceCommandResponse classify(String text) {
        AgentMessage message = new AgentMessage();
        message.setTask(text);
        return classify(message);
    }

    public VoiceCommandResponse classify(AgentMessage message) {
        return plan(null, message).getRawResponse();
    }

    public PlannerDecision plan(AgentState state, AgentMessage message) {
        if (message == null) {
            return buildDecision(fallbackRulePlan(state, new AgentMessage()));
        }

        String task = message.getTask() != null ? message.getTask().trim() : "";
        if (task.isEmpty() && (state == null || state.getLatestObservation() == null)) {
            return buildDecision(VoiceCommandResponse.of("UNKNOWN", "Empty request."));
        }

        try {
            String url = fastApiProperties.getIntentClassifier().getBaseUrl() + "/plan";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("text", task.isEmpty() ? "continue" : task);
            payload.put("goal", state != null && state.getGoal() != null ? state.getGoal() : task);
            payload.put("user_id", message.getUserId());
            payload.put("session_id", state != null && state.getSessionId() != null
                ? state.getSessionId()
                : (message.getUserId() != null ? message.getUserId() : "server-session"));
            payload.put("screenshot", message.getScreenshot());
            payload.put("screen_info", message.getScreenInfo() != null ? message.getScreenInfo() : "");
            payload.put("location", buildLocationPayload(message));
            payload.put("planner_turn", state != null ? state.getPlannerTurn() : 0);
            payload.put("active_tool", state != null ? state.getActiveToolName() : null);
            payload.put("active_tool_kind", state != null && state.getActiveToolKind() != null
                ? state.getActiveToolKind().name()
                : null);
            payload.put("latest_observation", buildObservationPayload(state != null ? state.getLatestObservation() : null));
            payload.put("tool_history", buildToolHistoryPayload(state != null ? state.getToolHistory() : List.of()));
            payload.put("allowed_tools", SUPPORTED_TOOLS);
            payload.put("use_rule", true);
            payload.put("execute_tools", false);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            VoiceCommandResponse response = restTemplate.postForObject(url, entity, VoiceCommandResponse.class);
            if (response == null) {
                response = fallbackRulePlan(state, message);
            }
            normalizeResponse(response);
            return buildDecision(response);
        } catch (Exception e) {
            logger.warn("Planner request failed, using local fallback: {}", e.getMessage());
            VoiceCommandResponse fallback = fallbackRulePlan(state, message);
            normalizeResponse(fallback);
            return buildDecision(fallback);
        }
    }

    private PlannerDecision buildDecision(VoiceCommandResponse response) {
        PlannerDecision decision = PlannerDecision.fromResponse(response);
        decision.setRawResponse(response);
        return decision;
    }

    private void normalizeResponse(VoiceCommandResponse response) {
        if (response.getFeature() == null && response.getIntent() != null) {
            response.setFeature(response.getIntent());
        }
        if (response.getIntent() == null && response.getFeature() != null) {
            response.setIntent(response.getFeature());
        }
        if (response.getDetail() == null) {
            response.setDetail(response.getReason());
        }
        if (response.getPlannerAction() == null || response.getPlannerAction().isBlank()) {
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                response.setPlannerAction("EXECUTE_TOOL");
            } else if (response.getFinalResponseText() != null && !response.getFinalResponseText().isBlank()) {
                response.setPlannerAction("FINISH");
            } else {
                response.setPlannerAction("ASK_USER");
            }
        }
    }

    private Map<String, Object> buildLocationPayload(AgentMessage message) {
        if (message.getLocation() == null) {
            return Map.of(
                "latitude", 39.90923,
                "longitude", 116.397428,
                "heading", 0.0,
                "accuracy", 10.0
            );
        }

        return Map.of(
            "latitude", message.getLocation().getLatitude(),
            "longitude", message.getLocation().getLongitude(),
            "heading", message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0,
            "accuracy", message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0,
            "timestamp", message.getLocation().getTimestamp() != null ? message.getLocation().getTimestamp() : System.currentTimeMillis()
        );
    }

    private Map<String, Object> buildObservationPayload(Observation observation) {
        if (observation == null) {
            return null;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", observation.getType());
        payload.put("status", observation.getStatus());
        payload.put("delegation_status", observation.getDelegationStatus() != null ? observation.getDelegationStatus().name() : null);
        payload.put("summary", observation.getSummary());
        payload.put("structured_data", observation.getStructuredData());
        payload.put("source_tool", observation.getSourceTool());
        payload.put("timestamp", observation.getTimestamp());
        return payload;
    }

    private List<Map<String, Object>> buildToolHistoryPayload(List<ToolHistoryEntry> toolHistory) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolHistoryEntry entry : toolHistory) {
            Map<String, Object> item = new HashMap<>();
            item.put("turn_index", entry.getTurnIndex());
            item.put("tool_name", entry.getToolName());
            item.put("result_status", entry.getResultStatus());
            item.put("observation_summary", entry.getObservationSummary());
            item.put("arguments_summary", entry.getArgumentsSummary());
            payload.add(item);
        }
        return payload;
    }

    private VoiceCommandResponse fallbackRulePlan(AgentState state, AgentMessage message) {
        Observation latestObservation = state != null ? state.getLatestObservation() : null;
        if (latestObservation != null) {
            return fallbackFromObservation(state, latestObservation);
        }

        String text = message.getTask() != null ? message.getTask().trim().toLowerCase() : "";
        if (text.isEmpty()) {
            return askUserResponse("UNKNOWN", "Please repeat the request.");
        }

        if (containsAny(text, STOP_KEYWORDS)) {
            return controlResponse("STOP", "Stop the current task.", "control.stop");
        }
        if (containsAny(text, PAUSE_KEYWORDS)) {
            return controlResponse("PAUSE", "Pause the current task.", "control.pause");
        }
        if (containsAny(text, RESUME_KEYWORDS)) {
            return controlResponse("RESUME", "Resume the current task.", "control.resume");
        }
        if (containsAny(text, NAVIGATION_KEYWORDS)) {
            return toolResponse(
                "NAVIGATION",
                "Navigation request.",
                "navigation.start",
                "SESSION",
                buildNavigationArguments(state, message)
            );
        }
        if (containsAny(text, OBSTACLE_KEYWORDS)) {
            return toolResponse(
                "OBSTACLE",
                "Obstacle detection request.",
                "obstacle.detect_single_frame",
                "ONE_SHOT",
                buildObstacleArguments(message)
            );
        }

        return toolResponse(
            "PHONE_CONTROL",
            "Phone control request.",
            "phone_control.start",
            "SESSION",
            buildPhoneControlArguments(state, message)
        );
    }

    private VoiceCommandResponse fallbackFromObservation(AgentState state, Observation observation) {
        if (observation.getDelegationStatus() == com.blindassist.server.agent.DelegationStatus.FINISHED) {
            VoiceCommandResponse response = VoiceCommandResponse.of("UNKNOWN", observation.getSummary());
            response.setPlannerAction("FINISH");
            response.setResponseText(observation.getSummary());
            response.setFinalResponseText(observation.getSummary());
            return response;
        }

        if (observation.getDelegationStatus() == com.blindassist.server.agent.DelegationStatus.ASK_USER) {
            return askUserResponse("UNKNOWN", observation.getSummary());
        }

        if ("error".equalsIgnoreCase(observation.getStatus())) {
            return askUserResponse("UNKNOWN", observation.getSummary());
        }

        if (state != null && state.getActiveToolKind() == ToolKind.ONE_SHOT) {
            VoiceCommandResponse response = VoiceCommandResponse.of("UNKNOWN", observation.getSummary());
            response.setPlannerAction("FINISH");
            response.setResponseText(observation.getSummary());
            response.setFinalResponseText(observation.getSummary());
            return response;
        }

        VoiceCommandResponse response = VoiceCommandResponse.of("UNKNOWN", observation.getSummary());
        response.setPlannerAction("WAIT_FOR_OBSERVATION");
        response.setResponseText(observation.getSummary());
        response.setFinalResponseText(null);
        return response;
    }

    private VoiceCommandResponse controlResponse(String feature, String detail, String toolName) {
        VoiceCommandResponse response = VoiceCommandResponse.of(feature, detail);
        response.setPlannerAction("EXECUTE_TOOL");

        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName(toolName);
        toolCall.setToolKind("CONTROL");
        toolCall.setService("runtime");
        toolCall.setDescription(detail);
        toolCall.setTransport("internal");
        toolCall.setEndpoint("/runtime/control");
        toolCall.setExecutionMode("blocking");
        toolCall.setConfidence(0.95);
        toolCall.setArguments(Map.of());

        response.setToolCalls(List.of(toolCall));
        return response;
    }

    private VoiceCommandResponse toolResponse(
        String feature,
        String detail,
        String toolName,
        String toolKind,
        Map<String, Object> arguments
    ) {
        VoiceCommandResponse response = VoiceCommandResponse.of(feature, detail);
        response.setPlannerAction("EXECUTE_TOOL");

        VoiceCommandResponse.ToolCall toolCall = new VoiceCommandResponse.ToolCall();
        toolCall.setName(toolName);
        toolCall.setToolKind(toolKind);
        toolCall.setService(serviceNameForTool(toolName));
        toolCall.setDescription(detail);
        toolCall.setTransport("http");
        toolCall.setEndpoint(endpointForTool(toolName));
        toolCall.setExecutionMode("blocking");
        toolCall.setConfidence(0.9);
        toolCall.setArguments(arguments);

        response.setToolCalls(List.of(toolCall));
        return response;
    }

    private VoiceCommandResponse askUserResponse(String feature, String detail) {
        VoiceCommandResponse response = VoiceCommandResponse.of(feature, detail);
        response.setPlannerAction("ASK_USER");
        response.setToolCalls(List.of());
        response.setResponseText(detail);
        response.setFinalResponseText(null);
        return response;
    }

    private Map<String, Object> buildNavigationArguments(AgentState state, AgentMessage message) {
        String sessionId = state != null && state.getSessionId() != null
            ? state.getSessionId()
            : (message.getUserId() != null ? message.getUserId() : "server-session");
        String goal = state != null && state.getGoal() != null ? state.getGoal() : message.getTask();

        Map<String, Object> args = new HashMap<>();
        args.put("session_id", sessionId);
        args.put("user_task", goal);
        args.put("origin", Map.of(
            "lon", message.getLocation() != null ? message.getLocation().getLongitude() : 116.397428,
            "lat", message.getLocation() != null ? message.getLocation().getLatitude() : 39.90923
        ));
        args.put("sensor_data", Map.of(
            "heading", message.getLocation() != null && message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0,
            "accuracy", message.getLocation() != null && message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0
        ));
        return args;
    }

    private Map<String, Object> buildObstacleArguments(AgentMessage message) {
        Map<String, Object> sensorData = new HashMap<>();
        sensorData.put("query", message.getTask());
        sensorData.put("heading", message.getLocation() != null && message.getLocation().getHeading() != null ? message.getLocation().getHeading() : 0.0);
        sensorData.put("accuracy", message.getLocation() != null && message.getLocation().getAccuracy() != null ? message.getLocation().getAccuracy() : 10.0);

        Map<String, Object> args = new HashMap<>();
        args.put("image", message.getScreenshot() != null ? message.getScreenshot() : "");
        args.put("sensor_data", sensorData);
        return args;
    }

    private Map<String, Object> buildPhoneControlArguments(AgentState state, AgentMessage message) {
        String sessionId = state != null && state.getSessionId() != null
            ? state.getSessionId()
            : (message.getUserId() != null ? message.getUserId() : "server-session");
        String goal = state != null && state.getGoal() != null ? state.getGoal() : message.getTask();

        Map<String, Object> args = new HashMap<>();
        args.put("session_id", sessionId);
        args.put("task", goal);
        args.put("screenshot", message.getScreenshot() != null ? message.getScreenshot() : "");
        args.put("screen_info", message.getScreenInfo() != null ? message.getScreenInfo() : "");
        return args;
    }

    private String serviceNameForTool(String toolName) {
        if (toolName.startsWith("navigation.")) {
            return "navigation";
        }
        if (toolName.startsWith("obstacle.")) {
            return "obstacle";
        }
        if (toolName.startsWith("phone_control.")) {
            return "phone_control";
        }
        return "runtime";
    }

    private String endpointForTool(String toolName) {
        if (toolName.startsWith("navigation.")) {
            return fastApiProperties.getNavigation() != null ? fastApiProperties.getNavigation().getBaseUrl() : "/navigation";
        }
        if (toolName.startsWith("obstacle.")) {
            return fastApiProperties.getObstacle().getBaseUrl();
        }
        if (toolName.startsWith("phone_control.")) {
            return fastApiProperties.getAutoglm().getBaseUrl();
        }
        return "/runtime/control";
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
