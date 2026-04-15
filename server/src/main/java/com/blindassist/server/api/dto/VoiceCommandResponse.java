package com.blindassist.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner response returned by the FastAPI planner service.
 * <p>
 * This keeps the legacy {@code feature/detail} fields for compatibility,
 * while adding structured plan and tool-call data for the new planner flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceCommandResponse {

    private String intent;
    private String feature;
    private String reason;
    private String detail;
    private Double confidence;
    private List<String> plan = new ArrayList<>();

    @JsonProperty("requires_execution")
    private Boolean requiresExecution;

    @JsonProperty("response_text")
    private String responseText;

    @JsonProperty("final_response_text")
    private String finalResponseText;

    @JsonProperty("planner_action")
    private String plannerAction;

    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls = new ArrayList<>();

    @JsonProperty("tool_results")
    private List<ToolResult> toolResults = new ArrayList<>();

    public static VoiceCommandResponse of(String feature, String detail) {
        VoiceCommandResponse response = new VoiceCommandResponse();
        response.setIntent(feature);
        response.setFeature(feature);
        response.setReason(detail);
        response.setDetail(detail);
        response.setConfidence(1.0);
        response.setRequiresExecution(false);
        response.setPlannerAction("FINISH");
        response.setResponseText(detail);
        response.setFinalResponseText(detail);
        return response;
    }

    public ToolCall getPrimaryToolCall() {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        return toolCalls.get(0);
    }

    public String getPrimaryToolName() {
        ToolCall primary = getPrimaryToolCall();
        return primary != null ? primary.getName() : null;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public List<String> getPlan() {
        return plan;
    }

    public void setPlan(List<String> plan) {
        this.plan = plan;
    }

    public Boolean getRequiresExecution() {
        return requiresExecution;
    }

    public void setRequiresExecution(Boolean requiresExecution) {
        this.requiresExecution = requiresExecution;
    }

    public String getResponseText() {
        return responseText;
    }

    public void setResponseText(String responseText) {
        this.responseText = responseText;
    }

    public String getFinalResponseText() {
        return finalResponseText;
    }

    public void setFinalResponseText(String finalResponseText) {
        this.finalResponseText = finalResponseText;
    }

    public String getPlannerAction() {
        return plannerAction;
    }

    public void setPlannerAction(String plannerAction) {
        this.plannerAction = plannerAction;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<ToolResult> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolResult> toolResults) {
        this.toolResults = toolResults;
    }

    public static class ToolCall {
        private String name;
        private String service;
        private String description;
        private String transport;
        private String endpoint;

        @JsonProperty("execution_mode")
        private String executionMode;

        @JsonProperty("tool_kind")
        private String toolKind;

        private Double confidence;
        private Map<String, Object> arguments = new HashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(String executionMode) {
            this.executionMode = executionMode;
        }

        public String getToolKind() {
            return toolKind;
        }

        public void setToolKind(String toolKind) {
            this.toolKind = toolKind;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public void setArguments(Map<String, Object> arguments) {
            this.arguments = arguments;
        }
    }

    public static class ToolResult {
        private String name;
        private String status;
        private Map<String, Object> output;
        private String error;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Map<String, Object> getOutput() {
            return output;
        }

        public void setOutput(Map<String, Object> output) {
            this.output = output;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
