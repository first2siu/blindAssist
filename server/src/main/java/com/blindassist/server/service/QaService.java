package com.blindassist.server.service;

import com.blindassist.server.api.dto.QaRequest;
import com.blindassist.server.api.dto.QaResponse;
import org.springframework.stereotype.Service;

/**
 * 问答服务：
 * - 当前实现为占位逻辑：简单回声 + 提示
 * - 实际可在此调用大模型 API 或本地推理服务
 */
@Service
public class QaService {

    public QaResponse answer(QaRequest req) {
        QaResponse resp = new QaResponse();
        resp.setSessionId(req.getSessionId());
        String q = req.getQuestion();
        if (q == null || q.isBlank()) {
            resp.setAnswer("我没有听清您的问题，可以再说一遍吗？");
        } else {
            resp.setAnswer("您刚才问的是：“" + q + "”。当前为示例回答，后续可以接入大模型给出更详细的解释。");
        }
        return resp;
    }
}


