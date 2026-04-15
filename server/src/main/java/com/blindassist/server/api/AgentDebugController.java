package com.blindassist.server.api;

import com.blindassist.server.agent.AgentState;
import com.blindassist.server.agent.TaskTraceRecord;
import com.blindassist.server.service.AgentStateStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Read-only debug endpoints for durable agent state and trace inspection.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentDebugController {

    private final AgentStateStore agentStateStore;

    public AgentDebugController(AgentStateStore agentStateStore) {
        this.agentStateStore = agentStateStore;
    }

    @GetMapping("/sessions/{clientSessionId}")
    public AgentState getSession(@PathVariable String clientSessionId) {
        return agentStateStore.loadState(clientSessionId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Agent session not found."));
    }

    @GetMapping("/traces/{clientSessionId}")
    public Map<String, Object> getTraces(
        @PathVariable String clientSessionId,
        @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        List<TaskTraceRecord> traces = agentStateStore.loadTraces(clientSessionId, limit);
        if (traces.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Agent traces not found.");
        }

        return Map.of(
            "session_id", clientSessionId,
            "limit", limit,
            "count", traces.size(),
            "traces", traces
        );
    }
}
