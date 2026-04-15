package com.blindassist.server.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted trace record for one agent-loop event.
 */
public class TaskTraceRecord {

    private String traceId = UUID.randomUUID().toString();
    private String sessionId;
    private String userId;
    private int turnIndex;
    private TraceEventType eventType;
    private String status;
    private String toolName;
    private String summary;
    private Map<String, Object> payload = new HashMap<>();
    private long createdAt = System.currentTimeMillis();

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public TraceEventType getEventType() {
        return eventType;
    }

    public void setEventType(TraceEventType eventType) {
        this.eventType = eventType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
