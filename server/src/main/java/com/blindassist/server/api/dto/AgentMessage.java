package com.blindassist.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;


/**
 * Agent 通信协议实体
 * 包含：类型、任务描述、截图数据、位置信息、以及返回的动作指令
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMessage {
    // 消息类型: "init" (开始任务), "step" (后续步骤), "action" (执行动作), "error", "finish"
    private String type;

    // 用户 ID（用于 TTS 队列识别）
    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("client_session_id")
    private String clientSessionId;

    // 初始化时的任务文本，例如 "帮我点外卖"
    private String task;

    // 图片 Base64 (不带 data:image/png;base64 前缀)
    private String screenshot;

    // 辅助的 UI 树描述或屏幕信息
    @JsonProperty("screen_info")
    private String screenInfo;

    // GPS 位置信息（用于导航和避障）
    @JsonProperty("location")
    private LocationInfo location;

    // --- Python 返回的字段 ---
    private String status;
    private String thinking; // 模型的思考过程
    private Map<String, Object> action; // 具体动作指令 (Tap, Swipe, Type)
    private Boolean finished; // 是否结束

    /**
     * GPS 位置信息
     */
    public static class LocationInfo {
        @JsonProperty("latitude")
        private double latitude;

        @JsonProperty("longitude")
        private double longitude;

        @JsonProperty("altitude")
        private Double altitude;

        @JsonProperty("heading")
        private Float heading; // 手机朝向，0-360度

        @JsonProperty("accuracy")
        private Float accuracy; // 位置精度（米）

        @JsonProperty("timestamp")
        private Long timestamp;

        public LocationInfo() {}

        public LocationInfo(double latitude, double longitude, float heading) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.heading = heading;
            this.timestamp = System.currentTimeMillis();
        }

        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }

        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }

        public Double getAltitude() { return altitude; }
        public void setAltitude(Double altitude) { this.altitude = altitude; }

        public Float getHeading() { return heading; }
        public void setHeading(Float heading) { this.heading = heading; }

        public Float getAccuracy() { return accuracy; }
        public void setAccuracy(Float accuracy) { this.accuracy = accuracy; }

        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    // No-args constructor for deserialization
    public AgentMessage() {}

    public AgentMessage(String type, String task, String screenshot, String screenInfo) {
        this.type = type;
        this.task = task;
        this.screenshot = screenshot;
        this.screenInfo = screenInfo;
    }

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getClientSessionId() { return clientSessionId; }
    public void setClientSessionId(String clientSessionId) { this.clientSessionId = clientSessionId; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getScreenshot() { return screenshot; }
    public void setScreenshot(String screenshot) { this.screenshot = screenshot; }

    public String getScreenInfo() { return screenInfo; }
    public void setScreenInfo(String screenInfo) { this.screenInfo = screenInfo; }

    public LocationInfo getLocation() { return location; }
    public void setLocation(LocationInfo location) { this.location = location; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public Map<String, Object> getAction() { return action; }
    public void setAction(Map<String, Object> action) { this.action = action; }

    public Boolean getFinished() { return finished; }
    public void setFinished(Boolean finished) { this.finished = finished; }
}
