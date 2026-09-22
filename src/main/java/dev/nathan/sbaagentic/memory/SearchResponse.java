package dev.nathan.sbaagentic.memory;

import dev.nathan.sbaagentic.recording.AgentEvent;
import java.util.List;
import java.util.Map;

public record SearchResponse(
        String query, List<AgentEvent> local, List<Map<String, Object>> elastic, ElasticHealth elasticHealth) {}
