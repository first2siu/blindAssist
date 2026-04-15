package com.blindassist.server.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory state for one top-level agent session.
 */
public class AgentState {

    private String sessionId;
    private String userId;
    private String goal;
    private AgentStatus status = AgentStatus.IDLE;
    private int plannerTurn;
    private String activeToolName;
    private ToolKind activeToolKind = ToolKind.UNKNOWN;
    private List<String> latestPlan = new ArrayList<>();
    private Observation latestObservation;
    private List<ToolHistoryEntry> toolHistory = new ArrayList<>();
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public void resetForNewGoal(String userId, String goal) {
        this.userId = userId;
        this.goal = goal;
        this.status = AgentStatus.IDLE;
        this.plannerTurn = 0;
        this.activeToolName = null;
        this.activeToolKind = ToolKind.UNKNOWN;
        this.latestPlan = new ArrayList<>();
        this.latestObservation = null;
        this.toolHistory = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        touch();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
        touch();
    }

    public int getPlannerTurn() {
        return plannerTurn;
    }

    public void setPlannerTurn(int plannerTurn) {
        this.plannerTurn = plannerTurn;
    }

    public String getActiveToolName() {
        return activeToolName;
    }

    public void setActiveToolName(String activeToolName) {
        this.activeToolName = activeToolName;
        touch();
    }

    public ToolKind getActiveToolKind() {
        return activeToolKind;
    }

    public void setActiveToolKind(ToolKind activeToolKind) {
        this.activeToolKind = activeToolKind;
        touch();
    }

    public List<String> getLatestPlan() {
        return latestPlan;
    }

    public void setLatestPlan(List<String> latestPlan) {
        this.latestPlan = latestPlan;
        touch();
    }

    public Observation getLatestObservation() {
        return latestObservation;
    }

    public void setLatestObservation(Observation latestObservation) {
        this.latestObservation = latestObservation;
        touch();
    }

    public List<ToolHistoryEntry> getToolHistory() {
        return toolHistory;
    }

    public void setToolHistory(List<ToolHistoryEntry> toolHistory) {
        this.toolHistory = toolHistory;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
