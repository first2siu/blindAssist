package com.blindassist.server.service;

import com.blindassist.server.config.FastApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for the AutoGLM phone-control tool endpoints.
 */
@Service
public class PhoneControlToolService {

    private static final Logger logger = LoggerFactory.getLogger(PhoneControlToolService.class);

    private final FastApiProperties fastApiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public PhoneControlToolService(FastApiProperties fastApiProperties) {
        this.fastApiProperties = fastApiProperties;
    }

    public Map<String, Object> start(String sessionId, String task, String screenshot, String screenInfo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("task", task);
        payload.put("screenshot", screenshot);
        payload.put("screen_info", screenInfo);
        return post("/tools/phone/start", payload, "phone_control.start");
    }

    public Map<String, Object> step(String sessionId, String screenshot, String screenInfo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("screenshot", screenshot);
        payload.put("screen_info", screenInfo);
        return post("/tools/phone/step", payload, "phone_control.step");
    }

    public Map<String, Object> reset(String sessionId) {
        return post("/tools/phone/reset", Map.of("session_id", sessionId), "phone_control.reset");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> payload, String sourceTool) {
        String baseUrl = fastApiProperties.getAutoglm() != null ? fastApiProperties.getAutoglm().getBaseUrl() : null;
        if (baseUrl == null || baseUrl.isBlank()) {
            return errorEnvelope(sourceTool, "Phone-control service base URL is not configured.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            Object response = restTemplate.postForObject(baseUrl + path, entity, Object.class);
            if (response instanceof Map<?, ?> result) {
                return (Map<String, Object>) result;
            }
            return errorEnvelope(sourceTool, "Phone-control service returned an unexpected payload.");
        } catch (Exception e) {
            logger.warn("Phone-control tool call {} failed: {}", sourceTool, e.getMessage());
            return errorEnvelope(sourceTool, e.getMessage());
        }
    }

    private Map<String, Object> errorEnvelope(String sourceTool, String summary) {
        return Map.of(
            "status", "error",
            "observation_type", "phone_control",
            "delegation_status", "ASK_USER",
            "summary", summary,
            "structured_data", Map.of(),
            "source_tool", sourceTool,
            "timestamp", System.currentTimeMillis()
        );
    }
}
