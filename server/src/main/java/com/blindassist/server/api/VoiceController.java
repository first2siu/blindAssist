package com.blindassist.server.api;

import com.blindassist.server.api.dto.VoiceCommandRequest;
import com.blindassist.server.api.dto.VoiceCommandResponse;
import com.blindassist.server.service.IntentClassificationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语音指令入口：
 * - 接收客户端已识别好的文本
 * - 调用 IntentClassificationService 完成功能分类
 * - 后续可在此处引入大模型，增强理解能力
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final IntentClassificationService intentService;

    public VoiceController(IntentClassificationService intentService) {
        this.intentService = intentService;
    }

    @PostMapping("/command")
    public VoiceCommandResponse classify(@RequestBody VoiceCommandRequest req) {
        return intentService.classify(req.getText());
    }
}


