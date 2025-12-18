package com.blindassist.server.api.dto;

import java.util.List;

/**
 * 导航路线响应 DTO：
 * 简化为一组可直接语音播报的步骤文本
 */
public class NavigationRouteResponse {

    private List<String> voiceSteps;

    public List<String> getVoiceSteps() {
        return voiceSteps;
    }

    public void setVoiceSteps(List<String> voiceSteps) {
        this.voiceSteps = voiceSteps;
    }
}


