package com.blindassist.server.service;

import com.blindassist.server.api.dto.NavigationRouteRequest;
import com.blindassist.server.api.dto.NavigationRouteResponse;
import com.blindassist.server.config.AmapProperties;
import com.blindassist.server.config.FastApiProperties;
import com.blindassist.server.model.SensorData;
import com.blindassist.server.tts.TtsMessageQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导航服务
 * 负责路径规划和导航指令的"盲人友好化"处理
 */
@Service
public class NavigationService {

    private static final Logger logger = LoggerFactory.getLogger(NavigationService.class);

    private final FastApiProperties fastApiProperties;
    private final AmapProperties amapProperties;
    private final TtsMessageQueue ttsQueue;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NavigationService(FastApiProperties fastApiProperties,
                            AmapProperties amapProperties,
                            TtsMessageQueue ttsQueue) {
        this.fastApiProperties = fastApiProperties;
        this.amapProperties = amapProperties;
        this.ttsQueue = ttsQueue;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 规划导航路线（HTTP API接口）
     * 调用高德地图API获取步行路线，并转换为语音步骤
     */
    public NavigationRouteResponse planRoute(NavigationRouteRequest req) {
        NavigationRouteResponse response = new NavigationRouteResponse();
        List<String> voiceSteps = new ArrayList<>();

        try {
            // 调用高德地图步行路径规划API
            String url = "https://restapi.amap.com/v3/direction/walking";
            String apiKey = amapProperties.getApiKey();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestUrl = String.format("%s?key=%s&origin=%s,%s&destination=%s,%s",
                url,
                apiKey,
                req.getStartLng(), req.getStartLat(),
                req.getEndLng(), req.getEndLat()
            );

            String responseBody = restTemplate.getForObject(requestUrl, String.class);
            JsonNode root = objectMapper.readTree(responseBody);

            // 解析路径步骤
            JsonNode route = root.path("route");
            JsonNode paths = route.path("paths");
            if (paths.isArray() && paths.size() > 0) {
                JsonNode steps = paths.get(0).path("steps");
                if (steps.isArray()) {
                    for (JsonNode step : steps) {
                        String instruction = step.path("instruction").asText();
                        // 转换为语音友好的格式
                        voiceSteps.add(convertToVoiceInstruction(instruction));
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to plan route", e);
            voiceSteps.add("路线规划失败，请稍后重试");
        }

        response.setVoiceSteps(voiceSteps);
        return response;
    }

    /**
     * 将导航指令转换为语音播报格式
     */
    private String convertToVoiceInstruction(String instruction) {
        // 简化指令，更适合语音播报
        String result = instruction
            .replace("沿", "")
            .replace("向前", "直行")
            .replace("向左", "左转")
            .replace("向右", "右转");

        // 将米转换为步数（1步≈0.65米）
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)米");
        java.util.regex.Matcher matcher = pattern.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int meters = Integer.parseInt(matcher.group(1));
            int steps = (int) Math.round(meters / 0.65);
            matcher.appendReplacement(sb, steps + "步");
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 规划导航路线
     * @param userId 用户ID
     * @param destination 目的地描述
     * @param sensorData 用户当前传感器数据
     */
    public void startNavigation(String userId, String destination, SensorData sensorData) {
        logger.info("Starting navigation for user {} to: {}", userId, destination);

        try {
            // 调用AutoGLM导航接口
            String response = callAutoGLMNavigation(destination, sensorData);
            processNavigationResponse(userId, response);

        } catch (Exception e) {
            logger.error("Navigation failed for user {}", userId, e);
            // 发送错误提示
            ttsQueue.enqueue(userId, "导航启动失败，请重试",
                           com.blindassist.server.model.TtsPriority.LOW, "navigation");
        }
    }

    /**
     * 调用AutoGLM导航接口
     */
    private String callAutoGLMNavigation(String destination, SensorData sensorData) throws Exception {
        String url = fastApiProperties.getAutoglm().getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 构造导航请求
        Map<String, Object> request = Map.of(
            "model", fastApiProperties.getAutoglm().getModel(),
            "messages", List.of(
                Map.of(
                    "role", "user",
                    "content", buildNavigationPrompt(destination, sensorData)
                )
            ),
            "temperature", 0.3,
            "max_tokens", 500
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForObject(url, entity, String.class);
    }

    /**
     * 构造导航Prompt
     */
    private String buildNavigationPrompt(String destination, SensorData sensorData) {
        return String.format("""
            你是一个为视障人士服务的导航助手。请帮助用户规划到目的地的路线。

            用户请求：%s

            用户当前状态：
            - 位置：%.6f, %.6f
            - 朝向：%.1f度（%s）

            请按以下步骤处理：
            1. 调用高德地图API获取从当前位置到目的地的步行路线
            2. 提取第一条导航指令
            3. 将指令转化为"盲人友好"的形式：
               - 绝对方向（东南西北）根据用户朝向转换为相对方向（左前右后）
               - 距离（米）转换为步数（1步≈0.65米）
               - 使用简洁明了的语言

            返回格式（JSON）：
            {
              "instruction": "转换后的导航指令",
              "distance": "剩余距离（米）",
              "urgency": "normal/important",
              "next_step": "下一步的简短提示"
            }
            """,
            destination,
            sensorData.getLatitude(),
            sensorData.getLongitude(),
            sensorData.getHeading(),
            sensorData.getHeadingText()
        );
    }

    /**
     * 处理导航响应
     */
    private void processNavigationResponse(String userId, String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").get(0).path("message").path("content").asText();

            // 解析LLM返回的JSON
            JsonNode result = objectMapper.readTree(content);

            String instruction = result.path("instruction").asText();
            String urgency = result.path("urgency").asText("normal");

            // 根据urgency确定优先级
            com.blindassist.server.model.TtsPriority priority =
                "important".equals(urgency) || "critical".equals(urgency) ?
                com.blindassist.server.model.TtsPriority.HIGH :
                com.blindassist.server.model.TtsPriority.NORMAL;

            // 将导航指令加入TTS队列
            ttsQueue.enqueue(userId, instruction, priority, "navigation");

            logger.info("Navigation instruction queued for user {}: {}", userId, instruction);

        } catch (Exception e) {
            logger.error("Failed to process navigation response", e);
        }
    }

    /**
     * 停止导航
     */
    public void stopNavigation(String userId) {
        logger.info("Stopping navigation for user {}", userId);
        ttsQueue.clear(userId);
    }

    /**
     * 更新导航指令（基于新的传感器数据）
     */
    public void updateNavigation(String userId, SensorData sensorData) {
        logger.debug("Updating navigation for user {} at position: {}, {}",
                    userId, sensorData.getLatitude(), sensorData.getLongitude());
    }
}
