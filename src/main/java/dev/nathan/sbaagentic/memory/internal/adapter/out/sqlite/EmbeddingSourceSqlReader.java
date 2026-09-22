package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.memory.internal.application.port.EmbeddingSourceReader;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingSourceSqlReader implements EmbeddingSourceReader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmbeddingSourceSqlReader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<EmbeddingSource> nextBatch(String afterTargetKind, String afterTargetId, int limit) {
        String afterKind = afterTargetKind == null ? "" : afterTargetKind;
        String afterId = afterTargetId == null ? "" : afterTargetId;
        int safeLimit = Math.max(1, limit);

        return jdbcTemplate.query(
                """
                SELECT target_kind, target_id, event_type, text, metadata_json
                  FROM (
                        SELECT 'event' AS target_kind, e.id AS target_id, e.event_type, e.text, e.metadata_json
                          FROM agent_events e
                         WHERE lower(replace(replace(replace(e.event_type, '_', ''), '-', ''), ' ', ''))
                               IN ('decision', 'handoff', 'observation')
                        UNION ALL
                        SELECT 'session_summary' AS target_kind, s.id AS target_id, NULL AS event_type,
                               s.summary AS text, NULL AS metadata_json
                          FROM agent_sessions s
                         WHERE trim(coalesce(s.summary, '')) <> ''
                       )
                 WHERE target_kind > ?
                    OR (target_kind = ? AND target_id > ?)
                 ORDER BY target_kind, target_id
                 LIMIT ?
                """,
                (rs, rowNum) -> new EmbeddingSource(
                        rs.getString("target_kind"),
                        rs.getString("target_id"),
                        rs.getString("event_type"),
                        rs.getString("text"),
                        fromJsonMap(rs.getString("metadata_json"))),
                afterKind,
                afterKind,
                afterId,
                safeLimit);
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {

            return Map.of();
        }
        try {

            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {

            return Map.of();
        }
    }
}
