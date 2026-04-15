package com.blindassist.server.agent;

/**
 * Trace event types persisted for debugging and evaluation.
 */
public enum TraceEventType {
    SESSION_INIT,
    PLANNER_REQUEST,
    PLANNER_RESPONSE,
    TOOL_CALL,
    TOOL_RESULT,
    OBSERVATION_RECORDED,
    FINAL_RESPONSE,
    SESSION_CLOSED,
    SESSION_FAILED
}
