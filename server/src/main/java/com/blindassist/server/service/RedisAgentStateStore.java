package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.TaskTraceRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed implementation for durable agent checkpoints and traces.
 */
@Service
public class RedisAgentStateStore implements AgentStateStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisAgentStateStore.class);
    private static final String STATE_KEY_PREFIX = "agent:state:";
    private static final String TRACE_KEY_PREFIX = "agent:trace:";
    private static final Duration STATE_TTL = Duration.ofHours(24);
    private static final Duration TRACE_TTL = Duration.ofDays(7);
    private static final long TRACE_LIMIT = 500L;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAgentStateStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveState(AgentState state) {
        if (state == null || state.getSessionId() == null || state.getSessionId().isBlank()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(stateKey(state.getSessionId()), objectMapper.writeValueAsString(state), STATE_TTL);
        } catch (Exception e) {
            logger.warn("Failed to persist agent state for session {}: {}", state.getSessionId(), e.getMessage());
        }
    }

    @Override
    public Optional<AgentState> loadState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        try {
            String raw = redisTemplate.opsForValue().get(stateKey(sessionId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            AgentState state = objectMapper.readValue(raw, AgentState.class);
            return Optional.of(state);
        } catch (Exception e) {
            logger.warn("Failed to load agent state for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void deleteState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            redisTemplate.delete(stateKey(sessionId));
        } catch (Exception e) {
            logger.warn("Failed to delete agent state for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void appendTrace(TaskTraceRecord traceRecord) {
        if (traceRecord == null || traceRecord.getSessionId() == null || traceRecord.getSessionId().isBlank()) {
            return;
        }

        try {
            String key = traceKey(traceRecord.getSessionId());
            String value = objectMapper.writeValueAsString(traceRecord);
            redisTemplate.opsForList().rightPush(key, value);
            redisTemplate.opsForList().trim(key, -TRACE_LIMIT, -1);
            redisTemplate.expire(key, TRACE_TTL);
        } catch (Exception e) {
            logger.warn("Failed to append agent trace for session {}: {}", traceRecord.getSessionId(), e.getMessage());
        }
    }

    @Override
    public List<TaskTraceRecord> loadTraces(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }

        int normalizedLimit = Math.max(1, limit);
        try {
            long size = redisTemplate.opsForList().size(traceKey(sessionId)) != null
                ? redisTemplate.opsForList().size(traceKey(sessionId))
                : 0L;
            if (size == 0L) {
                return Collections.emptyList();
            }

            long start = Math.max(0L, size - normalizedLimit);
            List<String> rawValues = redisTemplate.opsForList().range(traceKey(sessionId), start, size - 1);
            if (rawValues == null || rawValues.isEmpty()) {
                return Collections.emptyList();
            }

            List<TaskTraceRecord> traces = new ArrayList<>();
            for (String rawValue : rawValues) {
                traces.add(objectMapper.readValue(rawValue, new TypeReference<TaskTraceRecord>() {}));
            }
            return traces;
        } catch (Exception e) {
            logger.warn("Failed to load agent traces for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String stateKey(String sessionId) {
        return STATE_KEY_PREFIX + sessionId;
    }

    private String traceKey(String sessionId) {
        return TRACE_KEY_PREFIX + sessionId;
    }
}
