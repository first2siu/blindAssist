package com.blindassist.server.agent;

import com.blindassist.server.api.dto.VoiceCommandResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal planner decision used by the Java runtime.
 */
public class PlannerDecision {

    private String intent;
    private String detail;
    private PlannerAction plannerAction;
    private String responseText;
    private String finalResponseText;
    private List<String> plan = new ArrayList<>();
    private List<VoiceCommandResponse.ToolCall> toolCalls = new ArrayList<>();
    private VoiceCommandResponse rawResponse;

    public static PlannerDecision fromResponse(VoiceCommandResponse response) {
        PlannerDecision decision = new PlannerDecision();
        decision.setIntent(response.getFeature() != null ? response.getFeature() : response.getIntent());
        decision.setDetail(response.getDetail() != null ? response.getDetail() : response.getReason());
        decision.setPlannerAction(PlannerAction.fromValue(response.getPlannerAction()));
        decision.setResponseText(response.getResponseText());
        decision.setFinalResponseText(response.getFinalResponseText());
        decision.setPlan(response.getPlan() != null ? response.getPlan() : new ArrayList<>());
        decision.setToolCalls(response.getToolCalls() != null ? response.getToolCalls() : new ArrayList<>());
        return decision;
    }

    public VoiceCommandResponse.ToolCall getPrimaryToolCall() {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        return toolCalls.get(0);
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public PlannerAction getPlannerAction() {
        return plannerAction;
    }

    public void setPlannerAction(PlannerAction plannerAction) {
        this.plannerAction = plannerAction;
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

    public List<String> getPlan() {
        return plan;
    }

    public void setPlan(List<String> plan) {
        this.plan = plan;
    }

    public List<VoiceCommandResponse.ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<VoiceCommandResponse.ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public VoiceCommandResponse getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(VoiceCommandResponse rawResponse) {
        this.rawResponse = rawResponse;
    }
}
