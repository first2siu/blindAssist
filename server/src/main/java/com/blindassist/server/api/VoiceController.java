package com.blindassist.server.api;

import com.blindassist.server.api.dto.VoiceCommandRequest;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.service.IntentClassificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 语音指令入口：
 * - 接收客户端已识别好的文本
 * - 调用 IntentClassificationService 完成功能分类
 * - 后续可在此处引入大模型，增强理解能力
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Logger logger = LoggerFactory.getLogger(VoiceController.class);
    private final IntentClassificationService intentService;

    public VoiceController(IntentClassificationService intentService) {
        this.intentService = intentService;
    }

    @PostMapping("/command")
    public VoiceCommandResponse classify(@RequestBody VoiceCommandRequest req, HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        logger.info("========== 收到语音指令请求 ==========");
        logger.info("客户端IP: {}", clientIp);
        logger.info("请求URI: {}", httpRequest.getRequestURI());
        logger.info("请求方法: {}", httpRequest.getMethod());
        logger.info("请求内容类型: {}", httpRequest.getContentType());
        logger.info("请求文本: \"{}\"", req.getText());
        logger.info("======================================");

        VoiceCommandResponse response = intentService.classify(req.getText());

        logger.info("========== 意图分类结果 ==========");
        logger.info("意图类型: {}", response.getFeature());
        logger.info("详细描述: {}", response.getDetail());
        logger.info("====================================");

        return response;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
