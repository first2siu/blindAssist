package com.blindassist.server.api.dto;

/**
 * 语音问答响应 DTO
 */
public class QaResponse {

    private String answer;
    private String sessionId;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}


