package com.blindassist.server.service;

import com.blindassist.server.api.dto.VisionOcrResponse;
import com.blindassist.server.api.dto.VisionSceneResponse;
import org.springframework.stereotype.Service;

/**
 * 视觉相关服务（OCR & 场景描述）：
 * - 目前不真正解析图像，仅给出示例文本
 * - 实际可在此接入 OCR 引擎和多模态大模型
 */
@Service
public class VisionService {

    public VisionOcrResponse ocr(byte[] imageBytes) {
        VisionOcrResponse resp = new VisionOcrResponse();
        if (imageBytes == null || imageBytes.length == 0) {
            resp.setText("没有收到清晰的图像，请稍微调整手机位置后再试一次。");
        } else {
            resp.setText("示例：检测到包装盒上有“用法用量：每日三次，每次一片”的文字。");
        }
        return resp;
    }

    public VisionSceneResponse describeScene(byte[] imageBytes) {
        VisionSceneResponse resp = new VisionSceneResponse();
        if (imageBytes == null || imageBytes.length == 0) {
            resp.setDescription("没有收到清晰的图像，我暂时看不清您面前的环境。");
        } else {
            resp.setDescription("示例场景描述：您面前是一条人行道，左侧有几棵树，右侧是一排商店，前方大约十米处有一个路口。");
        }
        return resp;
    }
}


