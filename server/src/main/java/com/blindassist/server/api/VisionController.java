package com.blindassist.server.api;

import com.blindassist.server.api.dto.VisionOcrResponse;
import com.blindassist.server.api.dto.VisionSceneResponse;
import com.blindassist.server.service.VisionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图像相关接口：
 * - OCR：读取说明书、药品包装等文字
 * - 场景描述：综合描述用户面前的环境
 *
 * 这里以二进制图像数据为例，实际可使用 multipart/form-data 传文件。
 */
@RestController
@RequestMapping("/api/vision")
public class VisionController {

    private final VisionService visionService;

    public VisionController(VisionService visionService) {
        this.visionService = visionService;
    }

    @PostMapping(value = "/ocr", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public VisionOcrResponse ocr(@RequestBody byte[] imageBytes) {
        return visionService.ocr(imageBytes);
    }

    @PostMapping(value = "/scene", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public VisionSceneResponse scene(@RequestBody byte[] imageBytes) {
        return visionService.describeScene(imageBytes);
    }
}


