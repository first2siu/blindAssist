package com.blindassist.server.agent;

/**
 * Planner action returned by the FastAPI planner.
 */
public enum PlannerAction {
    EXECUTE_TOOL,
    WAIT_FOR_OBSERVATION,
    ASK_USER,
    FINISH;

    public static PlannerAction fromValue(String value) {
        if (value == null || value.isBlank()) {
            return EXECUTE_TOOL;
        }
        try {
            return PlannerAction.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return EXECUTE_TOOL;
        }
    }
}
