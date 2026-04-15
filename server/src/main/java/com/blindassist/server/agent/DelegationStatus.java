package com.blindassist.server.agent;

/**
 * Handoff state returned by specialist tools to the top-level loop.
 */
public enum DelegationStatus {
    CONTINUE,
    FINISHED,
    ASK_USER,
    NEED_GLOBAL_REPLAN,
    UNKNOWN;

    public static DelegationStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return DelegationStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
