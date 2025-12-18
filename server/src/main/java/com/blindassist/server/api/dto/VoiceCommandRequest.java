package com.blindassist.server.api.dto;

/**
 * 语音指令文本请求 DTO
 */
public class VoiceCommandRequest {

    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}


