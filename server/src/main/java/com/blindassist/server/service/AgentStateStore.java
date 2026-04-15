package com.blindassist.server.service;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.TaskTraceRecord;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for agent checkpoints and traces.
 */
public interface AgentStateStore {

    void saveState(AgentState state);

    Optional<AgentState> loadState(String sessionId);

    void deleteState(String sessionId);

    void appendTrace(TaskTraceRecord traceRecord);

    List<TaskTraceRecord> loadTraces(String sessionId, int limit);
}
