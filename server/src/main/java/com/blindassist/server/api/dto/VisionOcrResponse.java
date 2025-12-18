package com.blindassist.server.api.dto;

/**
 * 文本/图像识别（OCR）响应 DTO
 */
public class VisionOcrResponse {

    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}


