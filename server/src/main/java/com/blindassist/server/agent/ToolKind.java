package com.blindassist.server.agent;

/**
 * Runtime category for tools.
 */
public enum ToolKind {
    ONE_SHOT,
    SESSION,
    CONTROL,
    UNKNOWN;

    public static ToolKind fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return ToolKind.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
