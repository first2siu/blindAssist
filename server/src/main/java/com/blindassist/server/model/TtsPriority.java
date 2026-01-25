package com.blindassist.server.model;

/**
 * TTS消息优先级枚举
 */
public enum TtsPriority {
    CRITICAL(3, "紧急避障"),
    HIGH(2, "一般避障"),
    NORMAL(1, "导航指令"),
    LOW(0, "普通提示");

    private final int value;
    private final String description;

    TtsPriority(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }
}
