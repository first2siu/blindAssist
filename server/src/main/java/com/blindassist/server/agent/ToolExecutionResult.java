package com.blindassist.server.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Result returned by the tool router.
 */
public class ToolExecutionResult {

    private String status;
    private Observation observation;
    private Map<String, Object> rawPayload = new HashMap<>();
    private boolean shouldReplan;
    private Map<String, Object> clientMessage;

    public static ToolExecutionResult dispatched(String status) {
        ToolExecutionResult result = new ToolExecutionResult();
        result.setStatus(status);
        result.setShouldReplan(false);
        return result;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Observation getObservation() {
        return observation;
    }

    public void setObservation(Observation observation) {
        this.observation = observation;
    }

    public Map<String, Object> getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(Map<String, Object> rawPayload) {
        this.rawPayload = rawPayload;
    }

    public boolean isShouldReplan() {
        return shouldReplan;
    }

    public void setShouldReplan(boolean shouldReplan) {
        this.shouldReplan = shouldReplan;
    }

    public Map<String, Object> getClientMessage() {
        return clientMessage;
    }

    public void setClientMessage(Map<String, Object> clientMessage) {
        this.clientMessage = clientMessage;
    }

    public DelegationStatus getDelegationStatus() {
        if (observation == null || observation.getDelegationStatus() == null) {
            return DelegationStatus.UNKNOWN;
        }
        return observation.getDelegationStatus();
    }
}
