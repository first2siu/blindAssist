package com.blindassist.server.api;

import com.blindassist.server.model.TtsMessage;
import com.blindassist.server.model.TtsPriority;
import com.blindassist.server.tts.TtsMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS消息API
 * Android端通过此接口拉取待播报的消息
 *
 * 支持功能:
 * - 拉取消息队列
 * - 保存/恢复被打断的消息
 * - 队列状态查询
 */
@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private static final Logger logger = LoggerFactory.getLogger(TtsController.class);

    private final TtsMessageQueue ttsQueue;

    public TtsController(TtsMessageQueue ttsQueue) {
        this.ttsQueue = ttsQueue;
    }

    /**
     * 拉取TTS消息
     * Android端定期轮询此接口
     *
     * 请求格式:
     * {
     *   "user_id": "user123",
     *   "limit": 5
     * }
     *
     * 响应格式:
     * {
     *   "success": true,
     *   "count": 2,
     *   "messages": [...],
     *   "has_interrupted": false  // 是否有被打断的消息待恢复
     * }
     */
    @PostMapping("/pull")
    public ResponseEntity<Map<String, Object>> pullMessages(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        Integer limit = request.get("limit") != null ?
            ((Number) request.get("limit")).intValue() : 10;

        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("user_id is required"));
        }

        List<TtsMessage> messages = ttsQueue.dequeue(userId, limit);
        boolean hasInterrupted = ttsQueue.hasInterrupted(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", messages.size());
        response.put("messages", messages);
        response.put("has_interrupted", hasInterrupted);

        logger.debug("Pulled {} messages for user {}, has_interrupted={}",
                    messages.size(), userId, hasInterrupted);

        return ResponseEntity.ok(response);
    }

    /**
     * 保存被打断的消息
     * 当Android端需要打断当前播报时，先保存当前消息
     *
     * 请求格式:
     * {
     *   "user_id": "user123",
     *   "message": "向前走300步，然后左转",
     *   "priority": 1,  // 可选，默认为 NORMAL
     *   "source": "navigation"
     * }
     */
    @PostMapping("/interrupt")
    public ResponseEntity<Map<String, Object>> saveInterrupted(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        String message = (String) request.get("message");
        String source = request.get("source") != null ? (String) request.get("source") : "unknown";
        Integer priorityValue = request.get("priority") != null ?
            ((Number) request.get("priority")).intValue() : TtsPriority.NORMAL.getValue();

        if (userId == null || message == null) {
            return ResponseEntity.badRequest().body(errorResponse("user_id and message are required"));
        }

        TtsPriority priority = TtsPriority.fromValue(priorityValue);
        TtsMessage ttsMessage = new TtsMessage(userId, message, priority, source);

        ttsQueue.saveInterrupted(userId, ttsMessage);

        logger.info("Saved interrupted message for user {}: {}", userId, message);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Interrupted message saved");

        return ResponseEntity.ok(response);
    }

    /**
     * 恢复被打断的消息
     * 当避障警告播报完成后，调用此接口恢复被打断的导航指令
     *
     * 请求格式:
     * {
     *   "user_id": "user123"
     * }
     *
     * 响应格式:
     * {
     *   "success": true,
     *   "restored": true,
     *   "message": "向前走300步，然后左转"  // 恢复的消息内容
     * }
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resumeInterrupted(@RequestBody Map<String, String> request) {
        String userId = request.get("user_id");

        if (userId == null || userId.isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("user_id is required"));
        }

        // 先将被打断的消息重新加入队列
        ttsQueue.restoreInterrupted(userId);

        // 获取恢复的消息内容
        TtsMessage interrupted = ttsQueue.getInterrupted(userId);
        // 注意：getInterrupted 会删除 Redis 中的记录，但刚才 restoreInterrupted 已经重新加入了队列
        // 这里我们重新从队列 peek 获取
        List<TtsMessage> peeked = ttsQueue.peek(userId, 1);
        String restoredMessage = null;
        if (!peeked.isEmpty() && "navigation".equals(peeked.get(0).getSource())) {
            restoredMessage = peeked.get(0).getContent();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("restored", restoredMessage != null);
        response.put("message", restoredMessage);

        logger.info("Resumed interrupted message for user {}: {}", userId, restoredMessage);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取队列状态（不移除消息）
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String userId) {
        long size = ttsQueue.size(userId);
        List<TtsMessage> pending = ttsQueue.peek(userId, 5);
        boolean hasInterrupted = ttsQueue.hasInterrupted(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("user_id", userId);
        response.put("queue_size", size);
        response.put("pending_messages", pending);
        response.put("has_interrupted", hasInterrupted);

        return ResponseEntity.ok(response);
    }

    /**
     * 清空队列
     */
    @PostMapping("/clear/{userId}")
    public ResponseEntity<Map<String, Object>> clearQueue(@PathVariable String userId) {
        ttsQueue.clear(userId);
        ttsQueue.clearInterrupted(userId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Queue cleared for user " + userId);

        return ResponseEntity.ok(response);
    }

    /**
     * 添加TTS消息到队列
     * 用于其他服务（如导航）向队列添加消息
     */
    @PostMapping("/enqueue")
    public ResponseEntity<Map<String, Object>> enqueue(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        String content = (String) request.get("content");
        String source = request.get("source") != null ? (String) request.get("source") : "system";
        String priorityStr = (String) request.get("priority");

        logger.info("[TTS] Enqueue request: userId={}, content={}, priority={}, source={}",
                    userId, content, priorityStr, source);

        if (userId == null || content == null) {
            logger.warn("[TTS] Invalid enqueue request: userId={}, content={}", userId, content);
            return ResponseEntity.badRequest().body(errorResponse("user_id and content are required"));
        }

        TtsPriority priority = TtsPriority.NORMAL;
        if (priorityStr != null) {
            try {
                priority = TtsPriority.valueOf(priorityStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("[TTS] Invalid priority: {}, using NORMAL", priorityStr);
                priority = TtsPriority.NORMAL;
            }
        }

        ttsQueue.enqueue(userId, content, priority, source);

        logger.info("[TTS] Message enqueued successfully: userId={}, queueSize={}",
                    userId, ttsQueue.size(userId));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Message enqueued");

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", message);
        return response;
    }
}
