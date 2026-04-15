package com.blindassist.server.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Normalized observation for the agent loop.
 */
public class Observation {

    private String type;
    private String status;
    private DelegationStatus delegationStatus = DelegationStatus.CONTINUE;
    private String summary;
    private Map<String, Object> structuredData = new HashMap<>();
    private String sourceTool;
    private Map<String, Object> clientMessage;
    private String ttsMessage;
    private long timestamp = System.currentTimeMillis();

    public static Observation of(String type, String status, String summary, String sourceTool) {
        Observation observation = new Observation();
        observation.setType(type);
        observation.setStatus(status);
        observation.setDelegationStatus(DelegationStatus.CONTINUE);
        observation.setSummary(summary);
        observation.setSourceTool(sourceTool);
        return observation;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public DelegationStatus getDelegationStatus() {
        return delegationStatus;
    }

    public void setDelegationStatus(DelegationStatus delegationStatus) {
        this.delegationStatus = delegationStatus;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getStructuredData() {
        return structuredData;
    }

    public void setStructuredData(Map<String, Object> structuredData) {
        this.structuredData = structuredData;
    }

    public String getSourceTool() {
        return sourceTool;
    }

    public void setSourceTool(String sourceTool) {
        this.sourceTool = sourceTool;
    }

    public Map<String, Object> getClientMessage() {
        return clientMessage;
    }

    public void setClientMessage(Map<String, Object> clientMessage) {
        this.clientMessage = clientMessage;
    }

    public String getTtsMessage() {
        return ttsMessage;
    }

    public void setTtsMessage(String ttsMessage) {
        this.ttsMessage = ttsMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
