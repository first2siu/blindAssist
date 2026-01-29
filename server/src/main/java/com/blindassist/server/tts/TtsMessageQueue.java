package com.blindassist.server.tts;

import com.blindassist.server.model.TtsMessage;
import com.blindassist.server.model.TtsPriority;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TTS消息队列管理服务
 * 使用Redis Sorted Set实现优先级队列
 *
 * 支持功能:
 * - 优先级队列 (CRITICAL > HIGH > NORMAL > LOW)
 * - 被打断消息恢复
 * - 消息持久化
 */
@Service
public class TtsMessageQueue {

    private static final Logger logger = LoggerFactory.getLogger(TtsMessageQueue.class);

    private static final String QUEUE_KEY_PREFIX = "tts:queue:";
    private static final String INTERRUPTED_KEY_PREFIX = "tts:interrupted:";
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public TtsMessageQueue(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 入队：将TTS消息添加到指定用户的队列
     */
    public void enqueue(String userId, TtsMessage message) {
        try {
            String key = getQueueKey(userId);
            String json = objectMapper.writeValueAsString(message);
            long score = message.getScore();

            redisTemplate.opsForZSet().add(key, json, score);
            logger.debug("Enqueued TTS message for user {}: priority={}, score={}",
                        userId, message.getPriority(), score);
        } catch (Exception e) {
            logger.error("Failed to enqueue TTS message for user {}", userId, e);
        }
    }

    /**
     * 入队：便捷方法
     */
    public void enqueue(String userId, String content, TtsPriority priority, String source) {
        TtsMessage message = new TtsMessage(userId, content, priority, source);
        enqueue(userId, message);
    }

    /**
     * 入队：便捷方法（带metadata）
     */
    public void enqueue(String userId, String content, TtsPriority priority, String source, Object metadata) {
        TtsMessage message = new TtsMessage(userId, content, priority, source);
        message.setMetadata(metadata);
        enqueue(userId, message);
    }

    /**
     * 出队：批量获取并移除队列中的消息
     * 按优先级从高到低排序
     */
    public List<TtsMessage> dequeue(String userId, int limit) {
        List<TtsMessage> messages = new ArrayList<>();
        try {
            String key = getQueueKey(userId);
            ZSetOperations<String, String> zsetOps = redisTemplate.opsForZSet();

            // 先获取score最高的limit条记录（不移除）
            Set<String> jsonMessages = zsetOps.reverseRange(key, 0, limit - 1);

            if (jsonMessages != null && !jsonMessages.isEmpty()) {
                // 解析消息
                for (String json : jsonMessages) {
                    try {
                        TtsMessage message = objectMapper.readValue(json, TtsMessage.class);
                        messages.add(message);
                    } catch (Exception e) {
                        logger.warn("Failed to parse TTS message: {}", json, e);
                    }
                }

                // 移除已获取的消息
                zsetOps.removeRange(key, 0, limit - 1);
            }

            if (!messages.isEmpty()) {
                logger.debug("Dequeued {} TTS messages for user {}", messages.size(), userId);
            }
        } catch (Exception e) {
            logger.error("Failed to dequeue TTS messages for user {}", userId, e);
        }
        return messages;
    }

    /**
     * 出队：使用默认批次大小
     */
    public List<TtsMessage> dequeue(String userId) {
        return dequeue(userId, DEFAULT_BATCH_SIZE);
    }

    /**
     * 查看队列状态（不移除）
     */
    public List<TtsMessage> peek(String userId, int limit) {
        List<TtsMessage> messages = new ArrayList<>();
        try {
            String key = getQueueKey(userId);
            Set<String> jsonMessages = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);

            if (jsonMessages != null) {
                for (String json : jsonMessages) {
                    try {
                        TtsMessage message = objectMapper.readValue(json, TtsMessage.class);
                        messages.add(message);
                    } catch (Exception e) {
                        logger.warn("Failed to parse TTS message: {}", json, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to peek TTS messages for user {}", userId, e);
        }
        return messages;
    }

    /**
     * 保存被打断的消息（用于恢复）
     *
     * 当高优先级消息（如避障警告）打断当前播报时，
     * 将被打断的消息保存，以便稍后恢复
     */
    public void saveInterrupted(String userId, TtsMessage message) {
        try {
            String key = getInterruptedKey(userId);
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForValue().set(key, json);
            logger.debug("Saved interrupted message for user {}: {}", userId, message.getContent());
        } catch (Exception e) {
            logger.error("Failed to save interrupted message for user {}", userId, e);
        }
    }

    /**
     * 获取被打断的消息
     */
    public TtsMessage getInterrupted(String userId) {
        try {
            String key = getInterruptedKey(userId);
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                TtsMessage message = objectMapper.readValue(json, TtsMessage.class);
                // 获取后删除
                redisTemplate.delete(key);
                return message;
            }
        } catch (Exception e) {
            logger.error("Failed to get interrupted message for user {}", userId, e);
        }
        return null;
    }

    /**
     * 检查是否有被打断的消息
     */
    public boolean hasInterrupted(String userId) {
        try {
            String key = getInterruptedKey(userId);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Failed to check interrupted message for user {}", userId, e);
            return false;
        }
    }

    /**
     * 将被打断的消息重新加入队列（优先级提升，保证下次能被获取）
     */
    public void restoreInterrupted(String userId) {
        TtsMessage interrupted = getInterrupted(userId);
        if (interrupted != null) {
            // 提升优先级，确保恢复的消息优先播放
            TtsPriority newPriority = interrupted.getPriority() == TtsPriority.CRITICAL
                ? TtsPriority.CRITICAL
                : TtsPriority.HIGH;
            interrupted.setPriority(newPriority);
            enqueue(userId, interrupted);
            logger.info("Restored interrupted message for user {}: {}", userId, interrupted.getContent());
        }
    }

    /**
     * 清空被打断的消息
     */
    public void clearInterrupted(String userId) {
        try {
            String key = getInterruptedKey(userId);
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.error("Failed to clear interrupted message for user {}", userId, e);
        }
    }

    /**
     * 获取队列大小
     */
    public long size(String userId) {
        try {
            Long size = redisTemplate.opsForZSet().size(getQueueKey(userId));
            return size != null ? size : 0;
        } catch (Exception e) {
            logger.error("Failed to get queue size for user {}", userId, e);
            return 0;
        }
    }

    /**
     * 清空队列
     */
    public void clear(String userId) {
        try {
            redisTemplate.delete(getQueueKey(userId));
            logger.debug("Cleared TTS queue for user {}", userId);
        } catch (Exception e) {
            logger.error("Failed to clear TTS queue for user {}", userId, e);
        }
    }

    /**
     * 清空所有用户的队列
     */
    public void clearAll() {
        try {
            Set<String> keys = redisTemplate.keys(getQueueKey("*"));
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.info("Cleared {} TTS queues", keys.size());
            }
        } catch (Exception e) {
            logger.error("Failed to clear all TTS queues", e);
        }
    }

    /**
     * 获取队列的Redis key
     */
    private String getQueueKey(String userId) {
        return QUEUE_KEY_PREFIX + userId;
    }

    /**
     * 获取被打断消息的Redis key
     */
    private String getInterruptedKey(String userId) {
        return INTERRUPTED_KEY_PREFIX + userId;
    }
}
