package com.blindassist.server.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;


/**
 * Agent 通信协议实体
 * 包含：类型、任务描述、截图数据、以及返回的动作指令
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMessage {
    // 消息类型: "init" (开始任务), "step" (后续步骤), "action" (执行动作), "error", "finish"
    private String type;

    // 初始化时的任务文本，例如 "帮我点外卖"
    private String task;

    // 图片 Base64 (不带 data:image/png;base64 前缀)
    private String screenshot;

    // 辅助的 UI 树描述或屏幕信息
    @JsonProperty("screen_info")
    private String screenInfo;

    // --- Python 返回的字段 ---
    private String status;
    private String thinking; // 模型的思考过程
    private Map<String, Object> action; // 具体动作指令 (Tap, Swipe, Type)
    private Boolean finished; // 是否结束

    // No-args constructor for deserialization
    public AgentMessage() {}

    public AgentMessage(String type, String task, String screenshot, String screenInfo) {
        this.type = type;
        this.task = task;
        this.screenshot = screenshot;
        this.screenInfo = screenInfo;
    }
}
