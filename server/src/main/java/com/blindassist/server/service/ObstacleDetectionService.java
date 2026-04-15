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
 * Single-frame obstacle detection client for the FastAPI obstacle service.
 */
@Service
public class ObstacleDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(ObstacleDetectionService.class);

    private final FastApiProperties fastApiProperties;
    private final RestTemplate restTemplate = new RestTemplate();

    public ObstacleDetectionService(FastApiProperties fastApiProperties) {
        this.fastApiProperties = fastApiProperties;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> detectSingleFrame(String base64Image, Map<String, Object> sensorData) {
        if (base64Image == null || base64Image.isBlank()) {
            return Map.of(
                "safe", true,
                "instruction", "未提供图像，无法进行环境感知。"
            );
        }

        try {
            String url = fastApiProperties.getObstacle().getBaseUrl() + "/tools/obstacle/detect";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = new HashMap<>();
            payload.put("image", base64Image);
            payload.put("sensor_data", sensorData != null ? sensorData : Map.of());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            Object response = restTemplate.postForObject(url, entity, Object.class);

            if (response instanceof Map<?, ?> result) {
                return (Map<String, Object>) result;
            }
        } catch (Exception e) {
            logger.warn("Obstacle detection call failed: {}", e.getMessage(), e);
        }

        return Map.of(
            "safe", true,
            "instruction", "暂时无法分析当前画面，请谨慎前行。"
        );
    }
}
