package com.blindassist.server.api.dto;

/**
 * 语音问答请求 DTO（此处以文本形式接收）
 */
public class QaRequest {

    private String question;
    private String sessionId;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}


