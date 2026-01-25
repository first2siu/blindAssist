package com.blindassist.server.api;

import com.blindassist.server.model.TtsMessage;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS消息API
 * Android端通过此接口拉取待播报的消息
 */
@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsMessageQueue ttsQueue;

    public TtsController(TtsMessageQueue ttsQueue) {
        this.ttsQueue = ttsQueue;
    }

    /**
     * 拉取TTS消息
     * Android端定期轮询此接口
     */
    @PostMapping("/pull")
    public ResponseEntity<Map<String, Object>> pullMessages(@RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        Integer limit = request.get("limit") != null ?
            Integer.parseInt(request.get("limit")) : 10;

        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("user_id is required"));
        }

        List<TtsMessage> messages = ttsQueue.dequeue(userId, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", messages.size());
        response.put("messages", messages);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取队列状态（不移除消息）
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String userId) {
        long size = ttsQueue.size(userId);
        List<TtsMessage> pending = ttsQueue.peek(userId, 5);

        Map<String, Object> response = new HashMap<>();
        response.put("user_id", userId);
        response.put("queue_size", size);
        response.put("pending_messages", pending);

        return ResponseEntity.ok(response);
    }

    /**
     * 清空队列
     */
    @PostMapping("/clear/{userId}")
    public ResponseEntity<Map<String, Object>> clearQueue(@PathVariable String userId) {
        ttsQueue.clear(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Queue cleared for user " + userId);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", message);
        return response;
    }
}
