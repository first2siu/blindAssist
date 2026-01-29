package com.blindassist.server.service;

import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.config.FastApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 意图分类服务
 * 先使用规则快速判断，无法确定时调用LLM
 */
@Service
public class IntentClassificationService {

    private static final Logger logger = LoggerFactory.getLogger(IntentClassificationService.class);

    // 停止/取消相关关键词
    private static final List<String> STOP_KEYWORDS = List.of(
        "停止", "取消", "结束", "退出", "不做了", "不要了", "算了", "关闭", "停下",
        "stop", "cancel", "end", "exit", "quit", "abort"
    );

    // 暂停相关关键词
    private static final List<String> PAUSE_KEYWORDS = List.of(
        "暂停", "停一下", "等一下", "等等", "稍等", "先别动", "休息一下",
        "pause", "wait", "hold on"
    );

    // 恢复相关关键词
    private static final List<String> RESUME_KEYWORDS = List.of(
        "继续", "恢复", "接着走", "继续导航", "好了", "开始了", "走吧",
        "resume", "continue", "go on"
    );

    // 导航相关关键词
    private static final List<String> NAVIGATION_KEYWORDS = List.of(
        "去", "到", "导航", "怎么走", "带我去", "想去", "去往", "前往",
        "路线", "怎么到", "如何到", "找", "最近的"
    );

    // 避障相关关键词
    private static final List<String> OBSTACLE_KEYWORDS = List.of(
        "前面", "周围", "障碍", "小心", "注意", "环境", "场景",
        "有什么", "是什么", "能不能看见"
    );

    private final FastApiProperties fastApiProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public IntentClassificationService(FastApiProperties fastApiProperties) {
        this.fastApiProperties = fastApiProperties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 意图分类主入口
     */
    public VoiceCommandResponse classify(String text) {
        if (text == null || text.isBlank()) {
            return VoiceCommandResponse.of("UNKNOWN", "空文本");
        }

        String lower = text.toLowerCase();

        // 1. 规则判断（快速路径）
        IntentResult ruleResult = classifyByRule(lower);
        if (ruleResult.confidence > 0.8) {
            logger.info("规则分类: \"{}\" -> {} ({})", text, ruleResult.intent, ruleResult.description);
            return VoiceCommandResponse.of(ruleResult.intent, ruleResult.description);
        }

        // 2. LLM分类（兜底路径）
        return classifyByLLM(text);
    }

    /**
     * 基于规则的意图分类
     */
    private IntentResult classifyByRule(String text) {
        // 1. 优先检查停止/取消命令（最高优先级）
        for (String keyword : STOP_KEYWORDS) {
            if (text.contains(keyword)) {
                return new IntentResult("STOP", "停止当前任务", 1.0);
            }
        }

        // 2. 检查暂停命令（高优先级）
        for (String keyword : PAUSE_KEYWORDS) {
            if (text.contains(keyword)) {
                return new IntentResult("PAUSE", "暂停当前任务", 0.95);
            }
        }

        // 3. 检查恢复命令（高优先级）
        for (String keyword : RESUME_KEYWORDS) {
            if (text.contains(keyword)) {
                return new IntentResult("RESUME", "恢复当前任务", 0.95);
            }
        }

        // 4. 检查导航意图
        for (String keyword : NAVIGATION_KEYWORDS) {
            if (text.contains(keyword)) {
                // 排除"打开导航设置"等应用内操作
                if (text.contains("打开") || text.contains("设置") || text.contains("应用")) {
                    return new IntentResult("PHONE_CONTROL", "手机操控", 0.9);
                }
                return new IntentResult("NAVIGATION", "导航请求", 0.95);
            }
        }

        // 5. 检查避障意图
        for (String keyword : OBSTACLE_KEYWORDS) {
            if (text.contains(keyword)) {
                return new IntentResult("OBSTACLE", "环境感知", 0.9);
            }
        }

        // 6. 默认为手机操控
        return new IntentResult("PHONE_CONTROL", "手机操控", 0.7);
    }

    /**
     * 基于LLM的意图分类
     */
    private VoiceCommandResponse classifyByLLM(String text) {
        try {
            String url = fastApiProperties.getIntentClassifier().getBaseUrl() + "/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构造请求
            Map<String, Object> request = Map.of(
                "model", fastApiProperties.getIntentClassifier().getModel(),
                "messages", List.of(
                    Map.of(
                        "role", "user",
                        "content", buildIntentPrompt(text)
                    )
                ),
                "temperature", 0.1,
                "max_tokens", 100
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            String response = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            // 解析LLM返回的JSON
            JsonNode result = objectMapper.readTree(content);
            String intent = result.path("intent").asText("PHONE_CONTROL");
            String description = result.path("reason").asText("");

            logger.info("Intent classified by LLM: {} -> {}", text, intent);
            return VoiceCommandResponse.of(intent, description);

        } catch (Exception e) {
            logger.warn("LLM intent classification failed, using fallback: {}", e.getMessage());
            return VoiceCommandResponse.of("PHONE_CONTROL", "手机操控（降级）");
        }
    }

    /**
     * 构造意图分类的Prompt
     */
    private String buildIntentPrompt(String text) {
        return String.format("""
            你是一个意图分类助手。判断用户的语音指令属于哪一类意图。

            分类：
            1. NAVIGATION: 用户想要去某个地方、询问路线、寻找附近地点
            2. PHONE_CONTROL: 用户想要操控手机（打开应用、发送消息、设置等）
            3. OBSTACLE: 用户询问周围环境、障碍物相关

            规则：
            - 包含"去/到/导航/怎么走/带我去/找" → NAVIGATION
            - 包含"打开/设置/应用" → PHONE_CONTROL
            - 包含"前面/周围/障碍物/有什么" → OBSTACLE
            - 其他 → PHONE_CONTROL

            用户指令：%s

            返回格式（JSON）：
            {"intent": "NAVIGATION/PHONE_CONTROL/OBSTACLE", "reason": "原因"}
            """, text);
    }

    private static class IntentResult {
        String intent;
        String description;
        double confidence;

        IntentResult(String intent, String description, double confidence) {
            this.intent = intent;
            this.description = description;
            this.confidence = confidence;
        }
    }
}
