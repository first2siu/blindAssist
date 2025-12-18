package com.blindassist.server.service;

import com.blindassist.server.api.dto.VoiceCommandResponse;
import org.springframework.stereotype.Service;

/**
 * 语音意图分类服务：
 * - 目前用简单关键词规则实现
 * - 后期可替换为大模型调用
 */
@Service
public class IntentClassificationService {

    public VoiceCommandResponse classify(String text) {
        if (text == null || text.isBlank()) {
            return VoiceCommandResponse.of("UNKNOWN", "空文本");
        }
        String lower = text.toLowerCase();
        if (lower.contains("导航") || lower.contains("route") || lower.contains("去")) {
            return VoiceCommandResponse.of("NAVIGATION", "导航请求");
        }
        if (lower.contains("避障") || lower.contains("小心") || lower.contains("障碍")) {
            return VoiceCommandResponse.of("OBSTACLE_AVOIDANCE", "避障模式");
        }
        if (lower.contains("看") && (lower.contains("文字") || lower.contains("说明书") || lower.contains("药品"))) {
            return VoiceCommandResponse.of("OCR", "文字识别");
        }
        if (lower.contains("前面") || lower.contains("周围") || lower.contains("环境") || lower.contains("场景")) {
            return VoiceCommandResponse.of("SCENE_DESCRIPTION", "场景描述");
        }
        // 默认问答
        return VoiceCommandResponse.of("QA_VOICE", "语音问答");
    }
}


