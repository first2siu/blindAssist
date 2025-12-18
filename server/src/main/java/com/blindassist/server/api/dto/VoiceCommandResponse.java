package com.blindassist.server.api.dto;

/**
 * 语音指令分类结果：
 * feature: NAVIGATION / OBSTACLE_AVOIDANCE / QA_VOICE / OCR / SCENE_DESCRIPTION / UNKNOWN
 * detail: 进一步说明，如目的地描述等
 */
public class VoiceCommandResponse {

    private String feature;
    private String detail;

    public static VoiceCommandResponse of(String feature, String detail) {
        VoiceCommandResponse r = new VoiceCommandResponse();
        r.setFeature(feature);
        r.setDetail(detail);
        return r;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}


