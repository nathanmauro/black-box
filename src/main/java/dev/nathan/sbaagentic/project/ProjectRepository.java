package dev.nathan.sbaagentic.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.session.AgentSession;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String SESSION_CANONICAL_KEY_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private static final String STORYLINE_PREDICATE = """
            (
              lower(coalesce(e.event_type, '')) IN ('decision', 'handoff')
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
              OR (lower(coalesce(e.role, '')) = 'assistant' AND trim(coalesce(e.text, '')) <> '')
              OR e.tool_name IS NOT NULL
              OR lower(coalesce(e.event_type, '')) LIKE '%tool%'
              OR lower(coalesce(e.event_type, '')) LIKE '%error%'
              OR lower(coalesce(e.event_type, '')) LIKE '%fail%'
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProjectAliasService aliasService;

    public ProjectRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ProjectAliasService aliasService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.aliasService = aliasService;
    }

    public List<ProjectSummary> summaries() {
        ProjectAliasService.Snapshot aliases = aliasService.snapshot();
        Map<String, MutableSummary> grouped = new LinkedHashMap<>();
        List<RawSessionSummary> sessions = jdbcTemplate.query("""
                SELECT %s AS scope_key,
                       COUNT(*) AS session_count,
                       COALESCE(SUM(event_count), 0) AS event_count,
                       MIN(started_at) AS first_seen_at,
                       MAX(last_seen_at) AS last_seen_at
                  FROM agent_sessions s
                 GROUP BY scope_key
                """.formatted(SESSION_CANONICAL_KEY_SQL), (rs, rowNum) -> new RawSessionSummary(
                        rs.getString("scope_key"),
                        rs.getLong("session_count"),
                        rs.getLong("event_count"),
                        parseInstant(rs.getString("first_seen_at")),
                        parseInstant(rs.getString("last_seen_at"))));
        for (RawSessionSummary raw : sessions) {
            String canonical = aliases.resolve(raw.scopeKey());
            grouped.computeIfAbsent(canonical, MutableSummary::new).include(raw);
        }

        List<RawMeldSummary> melds = jdbcTemplate.query("""
                SELECT project_key AS scope_key,
                       COUNT(*) AS saved_meld_count,
                       MIN(created_at) AS first_seen_at,
                       MAX(created_at) AS last_seen_at
                  FROM session_melds
                 GROUP BY project_key
                """, (rs, rowNum) -> new RawMeldSummary(
                        ProjectKeyCodec.canonicalize(rs.getString("scope_key")),
                        rs.getLong("saved_meld_count"),
                        parseInstant(rs.getString("first_seen_at")),
                        parseInstant(rs.getString("last_seen_at"))));
        for (RawMeldSummary raw : melds) {
            String canonical = aliases.resolve(raw.scopeKey());
            grouped.computeIfAbsent(canonical, MutableSummary::new).include(raw);
        }

        return grouped.values().stream()
                .map(summary -> summary.toSummary(aliases.projectScopesFor(summary.canonicalKey)))
                .sorted(Comparator.comparing(
                        ProjectSummary::lastSeenAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<AgentSession> sessionsForProject(String canonicalKey, int limit) {
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        List<Object> args = new ArrayList<>(scopes);
        args.add(limit);
        return jdbcTemplate.query("""
                SELECT s.id, s.source, s.client_session_id, s.title, s.cwd, s.summary,
                       s.started_at, s.last_seen_at, s.event_count
                  FROM agent_sessions s
                 WHERE %s IN (%s)
                 ORDER BY s.last_seen_at DESC
                 LIMIT ?
                """.formatted(SESSION_CANONICAL_KEY_SQL, placeholders(scopes.size())),
                this::mapSession, args.toArray());
    }

    public List<AgentSession> sessionsForProjectByIds(String canonicalKey, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        String sessionPlaceholders = placeholders(sessionIds.size());
        List<Object> args = new ArrayList<>(scopes);
        args.addAll(sessionIds);
        return jdbcTemplate.query("""
                SELECT s.id, s.source, s.client_session_id, s.title, s.cwd, s.summary,
                       s.started_at, s.last_seen_at, s.event_count
                  FROM agent_sessions s
                 WHERE %s IN (%s)
                   AND s.id IN (%s)
                 ORDER BY s.last_seen_at DESC
                """.formatted(SESSION_CANONICAL_KEY_SQL, placeholders(scopes.size()), sessionPlaceholders),
                this::mapSession, args.toArray());
    }

    public long countTimelineBlocks(String canonicalKey) {
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        List<Object> args = new ArrayList<>(scopes);
        args.addAll(scopes);
        Long count = jdbcTemplate.queryForObject("""
                SELECT (
                    SELECT COUNT(*)
                      FROM agent_events e
                      JOIN agent_sessions s ON e.session_id = s.id
                     WHERE %s IN (%s)
                       AND %s
                ) + (
                    SELECT COUNT(*)
                      FROM session_melds m
                     WHERE m.project_key IN (%s)
                )
                """.formatted(
                        SESSION_CANONICAL_KEY_SQL,
                        placeholders(scopes.size()),
                        STORYLINE_PREDICATE,
                        placeholders(scopes.size())),
                Long.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    public List<ProjectTimelineBlock> timelineBlocks(String canonicalKey, int limit, int offset) {
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        List<Object> args = new ArrayList<>(scopes);
        args.addAll(scopes);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("""
                SELECT *
                  FROM (
                        SELECT e.id,
                               'raw_event' AS source_type,
                               e.session_id,
                               e.source,
                               e.client_session_id,
                               e.turn_id,
                               e.event_type,
                               e.role,
                               e.text,
                               e.tool_name,
                               e.tool_input_json,
                               e.tool_output_json,
                               e.metadata_json,
                               e.observed_at,
                               s.title AS session_title,
                               s.cwd,
                               NULL AS meld_title,
                               NULL AS meld_provider,
                               NULL AS meld_model,
                               NULL AS meld_prompt_version,
                               NULL AS meld_execution_mode,
                               NULL AS meld_saved_from_preview
                          FROM agent_events e
                          JOIN agent_sessions s ON e.session_id = s.id
                         WHERE %s IN (%s)
                           AND %s
                        UNION ALL
                        SELECT m.id,
                               'saved_meld' AS source_type,
                               NULL AS session_id,
                               'meld' AS source,
                               NULL AS client_session_id,
                               NULL AS turn_id,
                               'SavedMeld' AS event_type,
                               'synthesis' AS role,
                               m.body AS text,
                               NULL AS tool_name,
                               NULL AS tool_input_json,
                               NULL AS tool_output_json,
                               m.metadata_json,
                               m.created_at AS observed_at,
                               NULL AS session_title,
                               NULL AS cwd,
                               m.title AS meld_title,
                               m.provider AS meld_provider,
                               m.model AS meld_model,
                               m.prompt_version AS meld_prompt_version,
                               m.execution_mode AS meld_execution_mode,
                               m.saved_from_preview AS meld_saved_from_preview
                          FROM session_melds m
                         WHERE m.project_key IN (%s)
                       ) blocks
                 ORDER BY observed_at ASC
                 LIMIT ? OFFSET ?
                """.formatted(
                        SESSION_CANONICAL_KEY_SQL,
                        placeholders(scopes.size()),
                        STORYLINE_PREDICATE,
                        placeholders(scopes.size())),
                this::mapTimelineBlock,
                args.toArray());
    }

    public List<ProjectTimelineBlock> timelineBlocksForSession(String canonicalKey, String sessionId, int limit) {
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        List<Object> args = new ArrayList<>(scopes);
        args.add(sessionId);
        args.add(limit);
        return jdbcTemplate.query("""
                SELECT e.id, 'raw_event' AS source_type,
                       e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type,
                       e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json,
                       e.metadata_json, e.observed_at, s.title AS session_title, s.cwd,
                       NULL AS meld_title,
                       NULL AS meld_provider,
                       NULL AS meld_model,
                       NULL AS meld_prompt_version,
                       NULL AS meld_execution_mode,
                       NULL AS meld_saved_from_preview
                  FROM agent_events e
                  JOIN agent_sessions s ON e.session_id = s.id
                 WHERE %s IN (%s)
                   AND e.session_id = ?
                   AND %s
                 ORDER BY e.observed_at ASC
                 LIMIT ?
                """.formatted(SESSION_CANONICAL_KEY_SQL, placeholders(scopes.size()), STORYLINE_PREDICATE),
                this::mapTimelineBlock,
                args.toArray());
    }

    public void insertSavedMeld(
            String id,
            String canonicalKey,
            String title,
            String body,
            String provider,
            String model,
            String promptVersion,
            String executionMode,
            boolean savedFromPreview,
            Map<String, Object> metadata,
            Instant createdAt,
            List<AgentSession> sessions) {
        jdbcTemplate.update("""
                INSERT INTO session_melds
                       (id, project_key, title, body, provider, model, prompt_version,
                        execution_mode, saved_from_preview, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                canonicalKey,
                title,
                body,
                provider,
                model,
                promptVersion,
                executionMode,
                savedFromPreview ? 1 : 0,
                toJson(metadata),
                createdAt.toString());
        for (int i = 0; i < sessions.size(); i++) {
            AgentSession session = sessions.get(i);
            jdbcTemplate.update("""
                    INSERT INTO session_meld_inputs
                           (meld_id, session_id, input_order, included_summary, metadata_json)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    id,
                    session.id(),
                    i,
                    session.summary() == null || session.summary().isBlank() ? 0 : 1,
                    null);
        }
    }

    public List<ProjectSavedMeld> savedMeldsForProject(String canonicalKey) {
        List<String> scopes = aliasService.scopesFor(canonicalKey);
        return jdbcTemplate.query("""
                SELECT id, project_key, title, body, provider, model, prompt_version,
                       execution_mode, saved_from_preview, metadata_json, created_at
                  FROM session_melds
                 WHERE project_key IN (%s)
                 ORDER BY created_at DESC
                """.formatted(placeholders(scopes.size())), this::mapSavedMeld, scopes.toArray());
    }

    private record RawSessionSummary(
            String scopeKey,
            long sessionCount,
            long eventCount,
            Instant firstSeenAt,
            Instant lastSeenAt) {
    }

    private record RawMeldSummary(
            String scopeKey,
            long savedMeldCount,
            Instant firstSeenAt,
            Instant lastSeenAt) {
    }

    private static final class MutableSummary {
        private final String canonicalKey;
        private long sessionCount;
        private long eventCount;
        private long savedMeldCount;
        private Instant firstSeenAt;
        private Instant lastSeenAt;

        private MutableSummary(String canonicalKey) {
            this.canonicalKey = canonicalKey;
        }

        private void include(RawSessionSummary raw) {
            sessionCount += raw.sessionCount();
            eventCount += raw.eventCount();
            includeTime(raw.firstSeenAt(), raw.lastSeenAt());
        }

        private void include(RawMeldSummary raw) {
            savedMeldCount += raw.savedMeldCount();
            includeTime(raw.firstSeenAt(), raw.lastSeenAt());
        }

        private void includeTime(Instant first, Instant last) {
            if (first != null && (firstSeenAt == null || first.isBefore(firstSeenAt))) {
                firstSeenAt = first;
            }
            if (last != null && (lastSeenAt == null || last.isAfter(lastSeenAt))) {
                lastSeenAt = last;
            }
        }

        private ProjectSummary toSummary(List<ProjectScope> scopes) {
            return new ProjectSummary(
                    ProjectKeyCodec.encode(canonicalKey),
                    canonicalKey,
                    ProjectKeyCodec.labelFor(canonicalKey),
                    sessionCount,
                    eventCount,
                    savedMeldCount,
                    firstSeenAt,
                    lastSeenAt,
                    scopes);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private AgentSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new AgentSession(
                rs.getString("id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("title"),
                rs.getString("cwd"),
                rs.getString("summary"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("last_seen_at")),
                rs.getLong("event_count"));
    }

    private ProjectTimelineBlock mapTimelineBlock(ResultSet rs, int rowNum) throws SQLException {
        String sourceType = rs.getString("source_type");
        if ("saved_meld".equals(sourceType)) {
            return mapSavedMeldTimelineBlock(rs);
        }
        Map<String, Object> metadata = fromJsonMap(rs.getString("metadata_json"));
        String eventType = rs.getString("event_type");
        String role = rs.getString("role");
        String text = rs.getString("text");
        String toolName = rs.getString("tool_name");
        String blockType = blockType(eventType, role, toolName, metadata);
        return new ProjectTimelineBlock(
                rs.getString("id"),
                sourceType == null || sourceType.isBlank() ? "raw_event" : sourceType,
                blockType,
                headline(blockType, eventType, text, toolName, metadata),
                text,
                eventType,
                role,
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("session_id"),
                rs.getString("session_title"),
                rs.getString("cwd"),
                toolName,
                rs.getString("tool_input_json"),
                rs.getString("tool_output_json"),
                metadata,
                Instant.parse(rs.getString("observed_at")),
                List.of());
    }

    private ProjectTimelineBlock mapSavedMeldTimelineBlock(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        Map<String, Object> metadata = new LinkedHashMap<>(fromJsonMap(rs.getString("metadata_json")));
        metadata.put("provider", rs.getString("meld_provider"));
        metadata.put("model", rs.getString("meld_model"));
        metadata.put("promptVersion", rs.getString("meld_prompt_version"));
        metadata.put("executionMode", rs.getString("meld_execution_mode"));
        metadata.put("savedFromPreview", rs.getInt("meld_saved_from_preview") != 0);
        metadata.put("createdAt", rs.getString("observed_at"));
        return new ProjectTimelineBlock(
                id,
                "saved_meld",
                "synthesis",
                rs.getString("meld_title"),
                rs.getString("text"),
                rs.getString("event_type"),
                rs.getString("role"),
                rs.getString("source"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                metadata,
                Instant.parse(rs.getString("observed_at")),
                sourceSessionsForMeld(id));
    }

    private ProjectSavedMeld mapSavedMeld(ResultSet rs, int rowNum) throws SQLException {
        String canonicalKey = aliasService.resolve(rs.getString("project_key"));
        String id = rs.getString("id");
        return new ProjectSavedMeld(
                id,
                ProjectKeyCodec.encode(canonicalKey),
                canonicalKey,
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("execution_mode"),
                rs.getInt("saved_from_preview") != 0,
                fromJsonMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("created_at")),
                sourceSessionsForMeld(id));
    }

    private List<ProjectMeldSessionRef> sourceSessionsForMeld(String meldId) {
        return jdbcTemplate.query("""
                SELECT s.id, s.source, s.client_session_id, s.title, s.cwd,
                       s.started_at, s.last_seen_at, s.event_count
                  FROM session_meld_inputs i
                  JOIN agent_sessions s ON s.id = i.session_id
                 WHERE i.meld_id = ?
                 ORDER BY i.input_order ASC
                """, this::mapSessionRef, meldId);
    }

    private ProjectMeldSessionRef mapSessionRef(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectMeldSessionRef(
                rs.getString("id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("title"),
                rs.getString("cwd"),
                rs.getLong("event_count"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("last_seen_at")));
    }

    private String blockType(String eventType, String role, String toolName, Map<String, Object> metadata) {
        String type = lower(eventType);
        String kind = lower(metadata.get("kind"));
        if ("decision".equals(kind) || "decision".equals(type)) {
            return "decision";
        }
        if ("handoff".equals(kind) || "handoff".equals(type)) {
            return "handoff";
        }
        if (type.contains("error") || type.contains("fail")) {
            return "error";
        }
        if (toolName != null && !toolName.isBlank() || type.contains("tool")) {
            return "tool";
        }
        if ("assistant".equals(lower(role))) {
            return "assistant";
        }
        return "event";
    }

    private String headline(String blockType, String eventType, String text, String toolName, Map<String, Object> metadata) {
        Object structured = switch (blockType) {
            case "decision" -> metadata.get("decision");
            case "handoff" -> metadata.get("contextSummary");
            default -> null;
        };
        if (structured instanceof String value && !value.isBlank()) {
            return firstLine(value);
        }
        if (text != null && !text.isBlank()) {
            return firstLine(text);
        }
        if (toolName != null && !toolName.isBlank()) {
            return toolName;
        }
        return eventType == null || eventType.isBlank() ? "Event" : eventType;
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException ex) {
            return Map.of("unparsed", json);
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Meld metadata must be JSON-serializable.", ex);
        }
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline) : value;
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase();
    }
}
