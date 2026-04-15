package com.blindassist.server.agent;

/**
 * High-level runtime state for an agent session.
 */
public enum AgentStatus {
    IDLE,
    PLANNING,
    EXECUTING_TOOL,
    WAITING_OBSERVATION,
    PAUSED,
    FINISHED,
    FAILED
}
