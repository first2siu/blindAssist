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
 * HTTP client for the Navigation FastAPI tool endpoints.
 */
@Service
public class NavigationToolService {

    private static final Logger logger = LoggerFactory.getLogger(NavigationToolService.class);

    private final FastApiProperties fastApiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public NavigationToolService(FastApiProperties fastApiProperties) {
        this.fastApiProperties = fastApiProperties;
    }

    public Map<String, Object> start(String sessionId, String userTask, Map<String, Object> origin, Map<String, Object> sensorData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("user_task", userTask);
        payload.put("origin", origin);
        payload.put("sensor_data", sensorData);
        return post("/tools/navigation/start", payload, "navigation.start");
    }

    public Map<String, Object> update(String sessionId, Map<String, Object> origin, Map<String, Object> sensorData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("origin", origin);
        payload.put("sensor_data", sensorData);
        return post("/tools/navigation/update", payload, "navigation.update");
    }

    public Map<String, Object> pause(String sessionId) {
        return post("/tools/navigation/pause", Map.of("session_id", sessionId), "control.pause");
    }

    public Map<String, Object> resume(String sessionId, Map<String, Object> origin, Map<String, Object> sensorData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", sessionId);
        payload.put("origin", origin);
        payload.put("sensor_data", sensorData);
        return post("/tools/navigation/resume", payload, "control.resume");
    }

    public Map<String, Object> cancel(String sessionId) {
        return post("/tools/navigation/cancel", Map.of("session_id", sessionId), "control.stop");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> payload, String sourceTool) {
        String baseUrl = fastApiProperties.getNavigation() != null ? fastApiProperties.getNavigation().getBaseUrl() : null;
        if (baseUrl == null || baseUrl.isBlank()) {
            return errorEnvelope(sourceTool, "Navigation service base URL is not configured.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            Object response = restTemplate.postForObject(baseUrl + path, entity, Object.class);
            if (response instanceof Map<?, ?> result) {
                return (Map<String, Object>) result;
            }
            return errorEnvelope(sourceTool, "Navigation service returned an unexpected payload.");
        } catch (Exception e) {
            logger.warn("Navigation tool call {} failed: {}", sourceTool, e.getMessage());
            return errorEnvelope(sourceTool, e.getMessage());
        }
    }

    private Map<String, Object> errorEnvelope(String sourceTool, String summary) {
        return Map.of(
            "status", "error",
            "observation_type", "navigation",
            "delegation_status", "ASK_USER",
            "summary", summary,
            "structured_data", Map.of(),
            "source_tool", sourceTool,
            "timestamp", System.currentTimeMillis()
        );
    }
}
