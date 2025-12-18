package com.example.test_android_dev;

public enum FeatureType {
    NAVIGATION("路径导航"),
    OBSTACLE_AVOIDANCE("实时避障"),
    QA_VOICE("语音问答"),
    OCR("文字识别"),
    SCENE_DESCRIPTION("场景描述"),
    UNKNOWN("未知功能");

    private final String description;

    FeatureType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}