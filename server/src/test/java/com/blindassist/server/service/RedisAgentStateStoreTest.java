package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.TaskTraceRecord;
import com.blindassist.server.agent.TraceEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAgentStateStoreTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    private RedisAgentStateStore store;

    @BeforeEach
    void setUp() {
        store = new RedisAgentStateStore(redisTemplate, new ObjectMapper());
    }

    @Test
    void saveAndLoadStateRoundTripsThroughJson() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AtomicReference<String> storedJson = new AtomicReference<>();
        doAnswer(invocation -> {
            storedJson.set(invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(eq("agent:state:session-1"), anyString(), any(Duration.class));
        when(valueOperations.get("agent:state:session-1")).thenAnswer(invocation -> storedJson.get());

        AgentState state = new AgentState();
        state.setSessionId("session-1");
        state.setUserId("user-1");
        state.setGoal("navigate");

        store.saveState(state);
        Optional<AgentState> restored = store.loadState("session-1");

        assertTrue(restored.isPresent());
        assertEquals("user-1", restored.get().getUserId());
        assertEquals("navigate", restored.get().getGoal());
    }

    @Test
    void appendTraceTrimsAndLoadsLatestEntries() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        List<String> storedTraceJson = new ArrayList<>();
        when(listOperations.rightPush(eq("agent:trace:session-1"), anyString())).thenAnswer(invocation -> {
            storedTraceJson.add(invocation.getArgument(1));
            return Long.valueOf(storedTraceJson.size());
        });
        doAnswer(invocation -> null).when(listOperations).trim(eq("agent:trace:session-1"), anyLong(), anyLong());
        when(listOperations.size("agent:trace:session-1")).thenAnswer(invocation -> Long.valueOf(storedTraceJson.size()));
        when(listOperations.range(eq("agent:trace:session-1"), anyLong(), anyLong())).thenAnswer(invocation -> storedTraceJson);

        TaskTraceRecord traceRecord = new TaskTraceRecord();
        traceRecord.setSessionId("session-1");
        traceRecord.setEventType(TraceEventType.SESSION_INIT);
        traceRecord.setSummary("started");

        store.appendTrace(traceRecord);
        List<TaskTraceRecord> traces = store.loadTraces("session-1", 10);

        assertEquals(1, traces.size());
        assertEquals(TraceEventType.SESSION_INIT, traces.get(0).getEventType());
        verify(listOperations).trim("agent:trace:session-1", -500L, -1L);
    }
}
