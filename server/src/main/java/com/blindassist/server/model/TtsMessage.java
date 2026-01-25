package com.blindassist.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TTS消息实体
 */
public class TtsMessage {

    @JsonProperty("id")
    private String id;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("priority")
    private TtsPriority priority;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("source")
    private String source; // "navigation" | "obstacle" | "system"

    @JsonProperty("metadata")
    private Object metadata;

    public TtsMessage() {
    }

    public TtsMessage(String userId, String content, TtsPriority priority, String source) {
        this.id = java.util.UUID.randomUUID().toString();
        this.userId = userId;
        this.content = content;
        this.priority = priority;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 计算Redis Sorted Set的score
     * 优先级越高，score越大；相同优先级按时间排序
     */
    public long getScore() {
        return (long) priority.getValue() * 1_000_000_000L + timestamp;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public TtsPriority getPriority() { return priority; }
    public void setPriority(TtsPriority priority) { this.priority = priority; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Object getMetadata() { return metadata; }
    public void setMetadata(Object metadata) { this.metadata = metadata; }
}
