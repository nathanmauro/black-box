package dev.nathan.sbaagentic.memory;

import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.AgentEvent;

public record SearchResponse(
        String query,
        List<AgentEvent> local,
        List<Map<String, Object>> elastic,
        ElasticHealth elasticHealth) {
}
