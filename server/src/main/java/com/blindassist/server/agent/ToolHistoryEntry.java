package com.blindassist.server.agent;

/**
 * Minimal in-memory trace entry for a tool execution.
 */
public class ToolHistoryEntry {

    private int turnIndex;
    private String toolName;
    private String resultStatus;
    private String observationSummary;
    private String argumentsSummary;
    private long timestamp = System.currentTimeMillis();

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(String resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getObservationSummary() {
        return observationSummary;
    }

    public void setObservationSummary(String observationSummary) {
        this.observationSummary = observationSummary;
    }

    public String getArgumentsSummary() {
        return argumentsSummary;
    }

    public void setArgumentsSummary(String argumentsSummary) {
        this.argumentsSummary = argumentsSummary;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
