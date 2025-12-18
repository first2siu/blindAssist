package com.blindassist.server.api;

import com.blindassist.server.api.dto.QaRequest;
import com.blindassist.server.api.dto.QaResponse;
import com.blindassist.server.service.QaService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语音问答接口：
 * - 前端只需发送文本问题，服务端负责调用大模型或搜索
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/ask")
    public QaResponse ask(@RequestBody QaRequest req) {
        return qaService.answer(req);
    }
}


