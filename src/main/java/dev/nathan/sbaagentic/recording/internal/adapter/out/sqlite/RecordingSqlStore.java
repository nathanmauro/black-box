package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.DashboardStats;
import dev.nathan.sbaagentic.recording.EventFacetCounts;
import dev.nathan.sbaagentic.recording.EventFeedItem;
import dev.nathan.sbaagentic.recording.EventFeedResponse;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventTypes;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.StorageStats;
import dev.nathan.sbaagentic.recording.TitleRank;
import dev.nathan.sbaagentic.recording.internal.application.port.RecordingStore;
import dev.nathan.sbaagentic.query.EventQuery;
import dev.nathan.sbaagentic.query.EventQuery.Field;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RecordingSqlStore implements RecordingStore, RecordingCatalog {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    // Arms are ordered cheapest-first on purpose: SQLite short-circuits OR left-to-right, and in
    // an agent_events row the huge tool JSON columns sit between tool_name and metadata_json, so
    // an arm touching metadata_json walks the row's overflow chain. On the live corpus ~91% of
    // rows match `tool_name IS NOT NULL` and never reach the expensive arms — measured 10x faster
    // for full-corpus scans (facet counts) with identical semantics.
    private static final String MEANINGFUL_EVENT_PREDICATE = """
            (
              e.tool_name IS NOT NULL
              OR lower(coalesce(e.event_type, '')) IN ('decision', 'handoff')
              OR lower(coalesce(e.event_type, '')) LIKE '%tool%'
              OR lower(coalesce(e.event_type, '')) LIKE '%error%'
              OR lower(coalesce(e.event_type, '')) LIKE '%fail%'
              OR (lower(coalesce(e.role, '')) = 'assistant' AND trim(coalesce(e.text, '')) <> '')
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
            )
            """;

    private static final String SESSION_CANONICAL_CWD_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventFtsIndex ftsIndex;
    private final boolean postgres;

    public RecordingSqlStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            EventFtsIndex ftsIndex) {
        this(jdbcTemplate, objectMapper, clock, ftsIndex, "sqlite");
    }

    @Autowired
    public RecordingSqlStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock,
            EventFtsIndex ftsIndex, @Value("${sba.storage.backend:sqlite}") String backend) {
        this.postgres = "postgres".equals(backend);
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ftsIndex = ftsIndex;
    }

    /**
     * Idempotent migration for databases created before title ranking existed.
     * {@code schema.sql} runs on every startup, and SQLite has no
     * {@code ADD COLUMN IF NOT EXISTS}, so the column add lives here. Fresh
     * databases already get {@code title_rank} from the CREATE statement and
     * skip this entirely.
     */
    @PostConstruct
    public void ensureSchema() {
        if (postgres) return;
        // WAL lets concurrent agents (a Claude hook and a Codex hook firing at once) read while one
        // writes, instead of serializing behind a global lock. It is a persistent property of the
        // database file, so setting it once per boot is enough; busy_timeout (set per connection in
        // application.yml) keeps the rare writer-vs-writer contention from surfacing as an error.
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(agent_sessions)");
        if (columns.isEmpty()) {
            return;
        }
        boolean hasTitleRank = columns.stream()
                .anyMatch(column -> "title_rank".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasTitleRank) {
            jdbcTemplate.execute("ALTER TABLE agent_sessions ADD COLUMN title_rank INTEGER NOT NULL DEFAULT " + TitleRank.FALLBACK);
            // Existing titles predate ranking; protect them so only an AI retitle replaces them.
            jdbcTemplate.update("UPDATE agent_sessions SET title_rank = ?", TitleRank.LEGACY);
        }
        boolean hasSpawnedBy = columns.stream()
                .anyMatch(column -> "spawned_by".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasSpawnedBy) {
            jdbcTemplate.execute("ALTER TABLE agent_sessions ADD COLUMN spawned_by TEXT");
        }
    }

    /**
     * Persists an event together with its session as one atomic unit. The session upsert, the event
     * insert, and the event-count bump either all land or none do, so a crash mid-ingest can never
     * leave a session whose count disagrees with its events.
     */
    @Transactional
    @Override
    public RecordingStore.Persisted persistEvent(
            EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        AgentSession session = findOrCreateSession(request, observedAt, title, titleRank);
        AgentEvent event = saveEvent(request, session, observedAt);
        AgentSession updated = findSessionById(session.id()).orElse(session);
        return new RecordingStore.Persisted(updated, event);
    }

    public AgentSession findOrCreateSession(EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        // One atomic upsert: insert a fresh session, or — when (source, client_session_id) already
        // exists — bump its activity and upgrade the title only if this event carries a strictly
        // higher-ranked one. ON CONFLICT makes find-or-create race-free: two concurrent first events
        // for the same session can't double-insert or trip the UNIQUE constraint. started_at and
        // event_count are set only on insert, so an existing session keeps its origin and count.
        // spawned_by is stamped on insert and COALESCE-preserved on conflict: once a session knows
        // its parent, later events (which carry no parent ref) can never clear or overwrite it.
        String id = UUID.randomUUID().toString();
        String spawnedBy = spawnedByFrom(request);
        jdbcTemplate.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, spawned_by, started_at, last_seen_at, event_count
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (source, client_session_id) DO UPDATE SET
                    last_seen_at = excluded.last_seen_at,
                    cwd = COALESCE(excluded.cwd, agent_sessions.cwd),
                    spawned_by = COALESCE(agent_sessions.spawned_by, excluded.spawned_by),
                    title = CASE WHEN excluded.title_rank > agent_sessions.title_rank
                                 THEN excluded.title ELSE agent_sessions.title END,
                    title_rank = CASE WHEN excluded.title_rank > agent_sessions.title_rank
                                      THEN excluded.title_rank ELSE agent_sessions.title_rank END
                """,
                id,
                request.source(),
                request.clientSessionId(),
                title,
                titleRank,
                blankToNull(request.cwd()),
                spawnedBy,
                observedAt.toString(),
                observedAt.toString());
        return findSession(request.source(), request.clientSessionId()).orElseThrow();
    }

    /**
     * Lineage stamp: only SubagentStart/SubagentStop events carry a parent reference in metadata.
     * Event-type matching routes through the shared {@link EventTypes#normalize(String)} so this
     * agrees with {@code EventIngestService} and {@code SubagentLinkListener}.
     */
    private static String spawnedByFrom(EventIngestRequest request) {
        String type = EventTypes.normalize(request.eventType());
        if (!"subagentstart".equals(type) && !"subagentstop".equals(type)) {
            return null;
        }
        Object parent = request.metadata() == null ? null : request.metadata().get("parentClientSessionId");
        if (parent instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return null;
    }

    public AgentEvent saveEvent(EventIngestRequest request, AgentSession session, Instant observedAt) {
        AgentEvent event = new AgentEvent(
                UUID.randomUUID().toString(),
                session.id(),
                request.source(),
                request.clientSessionId(),
                blankToNull(request.turnId()),
                request.eventType(),
                blankToNull(request.role()),
                blankToNull(request.text()),
                blankToNull(request.toolName()),
                toJson(request.toolInput()),
                toJson(request.toolOutput()),
                request.metadata() == null ? Map.of() : request.metadata(),
                observedAt);

        jdbcTemplate.update("""
                INSERT INTO agent_events (
                    id, session_id, source, client_session_id, turn_id, event_type, role, text,
                    tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                event.turnId(),
                event.eventType(),
                event.role(),
                event.text(),
                event.toolName(),
                event.toolInputJson(),
                event.toolOutputJson(),
                toJson(event.metadata()),
                event.observedAt().toString());

        jdbcTemplate.update("""
                UPDATE agent_sessions
                   SET event_count = event_count + 1,
                       last_seen_at = ?
                 WHERE id = ?
                """, observedAt.toString(), session.id());

        return event;
    }

    public Optional<AgentSession> findSession(String source, String clientSessionId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count, spawned_by
                      FROM agent_sessions
                     WHERE source = ? AND client_session_id = ?
                    """, this::mapSession, source, clientSessionId));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<AgentSession> findSessionById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count, spawned_by
                      FROM agent_sessions
                     WHERE id = ?
                    """, this::mapSession, id));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<AgentEvent> findEventById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text,
                           tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                      FROM agent_events
                     WHERE id = ?
                    """, this::mapEvent, id));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<AgentSession> recentSessions(int limit) {
        return recentSessions(limit, false);
    }

    public List<AgentSession> recentSessions(int limit, boolean includeChildren) {
        // Parents-only is the default view everywhere (REST list, MCP recentSessions, CLI):
        // spawned_by children surface through their parent's links, not the flat rail.
        String filter = includeChildren ? "" : " WHERE spawned_by IS NULL\n";
        return jdbcTemplate.query("""
                SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count, spawned_by
                  FROM agent_sessions
                """ + filter + """
                 ORDER BY last_seen_at DESC
                 LIMIT ?
                """, this::mapSession, limit);
    }

    public List<AgentSession> recentSessionsMissingSummary(int limit) {
        return jdbcTemplate.query("""
                SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count, spawned_by
                  FROM agent_sessions
                 WHERE summary IS NULL OR trim(summary) = ''
                 ORDER BY last_seen_at DESC
                 LIMIT ?
                """, this::mapSession, limit);
    }

    public List<AgentEvent> eventsForSession(String sessionId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text,
                       tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                  FROM agent_events
                 WHERE session_id = ?
                 ORDER BY observed_at DESC
                 LIMIT ?
                """, this::mapEvent, sessionId, limit);
    }

    @Override
    public EventFeedResponse feedForSession(String sessionId, String query, String before, int limit) {
        return feed(query, false, before, null, List.of(), limit, sessionId);
    }

    @Override
    public List<String> transcriptPathsForSession(String sessionId) {
        List<String> metadataRows = jdbcTemplate.queryForList("""
                SELECT metadata_json
                  FROM agent_events
                 WHERE session_id = ?
                   AND metadata_json IS NOT NULL
                   AND (metadata_json LIKE '%transcript_path%' OR metadata_json LIKE '%transcriptPath%')
                 ORDER BY CASE
                            WHEN lower(replace(replace(event_type, '_', ''), '-', '')) = 'sessionstart' THEN 0
                            ELSE 1
                          END,
                          observed_at DESC,
                          id DESC
                 LIMIT 100
                """, String.class, sessionId);
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (String json : metadataRows) {
            Map<String, Object> metadata = fromJsonMap(json);
            addTranscriptPath(paths, metadata);
            Object rawHook = metadata.get("rawHook");
            if (rawHook instanceof Map<?, ?> raw) {
                addTranscriptPath(paths, raw);
            }
            Object capture = metadata.get("cockpit_capture");
            if (capture instanceof Map<?, ?> captureMap) {
                Object replay = captureMap.get("replay");
                if (replay instanceof Map<?, ?> replayMap) {
                    addTranscriptPath(paths, replayMap);
                }
            }
        }
        return List.copyOf(paths);
    }

    @Override
    public List<AgentEvent> conversationEventsForSession(String sessionId) {
        return jdbcTemplate.query("""
                SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text, observed_at
                  FROM agent_events
                 WHERE session_id = ?
                   AND text IS NOT NULL
                   AND trim(text) <> ''
                   AND (
                        lower(coalesce(role, '')) IN ('user', 'assistant')
                        OR lower(replace(replace(event_type, '_', ''), '-', '')) IN (
                            'userpromptsubmit', 'beforesubmitprompt', 'stop', 'assistantmessage',
                            'agentmessage', 'agentresponse', 'finalresponse'
                        )
                   )
                 ORDER BY observed_at DESC, id DESC
                """, (rs, rowNum) -> new AgentEvent(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("source"),
                        rs.getString("client_session_id"),
                        rs.getString("turn_id"),
                        rs.getString("event_type"),
                        rs.getString("role"),
                        rs.getString("text"),
                        null,
                        null,
                        null,
                        Map.of(),
                        Instant.parse(rs.getString("observed_at"))),
                sessionId);
    }

    public EventFeedResponse feed(
            String query,
            boolean meaningfulOnly,
            String before,
            String since,
            List<String> projectScopes,
            int limit) {
        return feed(query, meaningfulOnly, before, since, projectScopes, limit, null);
    }

    private EventFeedResponse feed(
            String query,
            boolean meaningfulOnly,
            String before,
            String since,
            List<String> projectScopes,
            int limit,
            String hardSessionId) {
        EventQuery facets = EventQuery.parse(query);
        FeedCursor beforeCursor = parseBefore(before);
        Instant sinceInstant = parseSince(since);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, ")
                .append("e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, ")
                .append("e.observed_at, s.cwd AS cwd, s.title AS session_title\n")
                .append("  FROM agent_events e\n")
                .append("  JOIN agent_sessions s ON s.id = e.session_id\n")
                .append(" WHERE 1=1\n");
        if (hardSessionId != null) {
            sql.append("   AND e.session_id = ?\n");
            args.add(hardSessionId);
        }
        boolean usedFts = appendQueryPredicates(
                sql, args, facets, meaningfulOnly, projectScopes, null, hardSessionId == null);
        if (sinceInstant != null) {
            sql.append("   AND e.observed_at >= ?\n");
            args.add(sinceInstant.toString());
        }
        if (beforeCursor != null) {
            sql.append("   AND (e.observed_at < ? OR (e.observed_at = ? AND e.id < ?))\n");
            args.add(beforeCursor.observedAt());
            args.add(beforeCursor.observedAt());
            args.add(beforeCursor.id());
        }
        sql.append(" ORDER BY e.observed_at DESC, e.id DESC\n")
                .append(" LIMIT ?");
        args.add(limit + 1);

        List<EventFeedItem> fetched;
        try {
            fetched = jdbcTemplate.query(sql.toString(), this::mapFeedItem, args.toArray());
        }
        catch (org.springframework.dao.DataAccessException ex) {
            if (!usedFts) {
                throw ex;
            }
            // Fail soft: a broken FTS table must never take the feed down — drop to LIKE and retry.
            ftsIndex.markUnavailable();
            return feed(query, meaningfulOnly, before, since, projectScopes, limit, hardSessionId);
        }
        boolean hasMore = fetched.size() > limit;
        List<EventFeedItem> kept = hasMore ? fetched.subList(0, limit) : fetched;
        String nextBefore = hasMore && !kept.isEmpty() ? cursorFor(kept.get(kept.size() - 1)) : null;
        return new EventFeedResponse(limit, kept.size(), List.copyOf(kept), nextBefore);
    }

    /**
     * Query-scoped facet counts (spec §6.5): one total plus a GROUP BY per counted field, each
     * per-field query dropping that field's own include list so counts answer "what if I switched".
     * Free text without a ready FTS index skips counting entirely — four unindexed LIKE scans over
     * the corpus would blow the latency budget — and reports the degradation instead of a number.
     */
    @Override
    public EventFacetCounts facetCounts(String query, boolean meaningfulOnly, List<String> projectScopes) {
        EventQuery facets = EventQuery.parse(query);
        if (!facets.freeTerms().isEmpty() && !ftsIndex.ready()) {
            return EventFacetCounts.skipped("backfill");
        }
        try {
            // Without counted-field include lists every per-field WHERE is identical, so all four
            // group-bys plus the total can share one scan (the meaningful predicate makes each
            // scan ~250ms on the live corpus — paying it once instead of four times matters).
            // With includes set, drop-own-field makes the predicates genuinely differ per field.
            if (!hasCountedIncludes(facets)) {
                return onePassCounts(facets, meaningfulOnly, projectScopes);
            }
            Long total = queryTotal(facets, meaningfulOnly, projectScopes);
            return new EventFacetCounts(
                    total == null ? 0L : total,
                    new EventFacetCounts.Fields(
                            groupCounts(facets, meaningfulOnly, projectScopes, Field.SOURCE, "e.source", null),
                            groupCounts(facets, meaningfulOnly, projectScopes, Field.KIND, "e.event_type", null),
                            // Anchoring on tool_name IS NOT NULL lets the planner drive the whole
                            // group-by off idx_agent_events_tool_observed (measured ~11ms) while
                            // also keeping NULL out of the value list.
                            groupCounts(facets, meaningfulOnly, projectScopes, Field.TOOL, "e.tool_name",
                                    "e.tool_name IS NOT NULL"),
                            projectCounts(facets, meaningfulOnly, projectScopes)),
                    null);
        }
        catch (org.springframework.dao.DataAccessException ex) {
            if (facets.freeTerms().isEmpty()) {
                throw ex;
            }
            // A broken FTS table must never 500 the counts: report them unavailable (same envelope
            // as the backfill window) and drop the index so the feed's LIKE fallback takes over.
            ftsIndex.markUnavailable();
            return EventFacetCounts.skipped("backfill");
        }
    }

    private static boolean hasCountedIncludes(EventQuery facets) {
        return !facets.values(Field.SOURCE).isEmpty()
                || !facets.values(Field.KIND).isEmpty()
                || !facets.values(Field.TOOL).isEmpty()
                || !facets.values(Field.PROJECT).isEmpty();
    }

    /**
     * One scan, five answers: materialize the matching rows' grouping columns once, then take the
     * total and all four group-bys off the temp table. Valid only while no counted field carries
     * an include list (then every per-field WHERE is the same); ordering and the per-field cap
     * are applied in Java after the single round trip.
     */
    private EventFacetCounts onePassCounts(
            EventQuery facets, boolean meaningfulOnly, List<String> projectScopes) {
        List<Object> args = new ArrayList<>();
        StringBuilder matched = new StringBuilder()
                .append("SELECT e.source AS source, e.event_type AS event_type, ")
                .append("e.tool_name AS tool_name, e.session_id AS session_id\n")
                .append("  FROM agent_events e\n");
        appendSessionsJoinIfNeeded(matched, facets, null);
        matched.append(" WHERE 1=1\n");
        appendQueryPredicates(matched, args, facets, meaningfulOnly, projectScopes, null, true);
        String sql = """
                WITH matched AS MATERIALIZED (
                %s)
                SELECT 'total' AS field, '' AS value, COUNT(*) AS cnt FROM matched
                UNION ALL
                SELECT 'source', source, COUNT(*) FROM matched GROUP BY 2
                UNION ALL
                SELECT 'kind', event_type, COUNT(*) FROM matched GROUP BY 2
                UNION ALL
                SELECT 'tool', tool_name, COUNT(*) FROM matched WHERE tool_name IS NOT NULL GROUP BY 2
                UNION ALL
                SELECT 'project', %s, SUM(t.cnt)
                  FROM (SELECT session_id, COUNT(*) AS cnt FROM matched GROUP BY session_id) t
                  JOIN agent_sessions s ON s.id = t.session_id
                 GROUP BY 2
                """.formatted(matched, SESSION_CANONICAL_CWD_SQL);

        long[] total = {0};
        Map<String, List<EventFacetCounts.ValueCount>> byField = new java.util.HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String field = rs.getString("field");
            if ("total".equals(field)) {
                total[0] = rs.getLong("cnt");
                return;
            }
            byField.computeIfAbsent(field, ignored -> new ArrayList<>())
                    .add(new EventFacetCounts.ValueCount(rs.getString("value"), rs.getLong("cnt")));
        }, args.toArray());
        return new EventFacetCounts(
                total[0],
                new EventFacetCounts.Fields(
                        topValues(byField.get("source")),
                        topValues(byField.get("kind")),
                        topValues(byField.get("tool")),
                        topValues(byField.get("project"))),
                null);
    }

    /** Count-desc / value-asc, capped — the same order and cap the SQL path applies. */
    private static List<EventFacetCounts.ValueCount> topValues(List<EventFacetCounts.ValueCount> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .sorted(java.util.Comparator
                        .comparingLong(EventFacetCounts.ValueCount::count).reversed()
                        .thenComparing(EventFacetCounts.ValueCount::value))
                .limit(EventFacetCounts.VALUE_LIMIT)
                .toList();
    }

    private Long queryTotal(EventQuery facets, boolean meaningfulOnly, List<String> projectScopes) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT COUNT(*)\n")
                .append("  FROM agent_events e\n");
        appendSessionsJoinIfNeeded(sql, facets, null);
        sql.append(" WHERE 1=1\n");
        appendQueryPredicates(sql, args, facets, meaningfulOnly, projectScopes, null, true);
        return jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    private List<EventFacetCounts.ValueCount> groupCounts(
            EventQuery facets,
            boolean meaningfulOnly,
            List<String> projectScopes,
            Field droppedInclude,
            String valueExpr,
            String extraPredicate) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(valueExpr).append(" AS value, COUNT(*) AS cnt\n")
                .append("  FROM agent_events e\n");
        appendSessionsJoinIfNeeded(sql, facets, droppedInclude);
        sql.append(" WHERE 1=1\n");
        if (extraPredicate != null) {
            sql.append("   AND ").append(extraPredicate).append("\n");
        }
        appendQueryPredicates(sql, args, facets, meaningfulOnly, projectScopes, droppedInclude, true);
        sql.append(" GROUP BY value\n")
                .append(" ORDER BY cnt DESC, value ASC\n")
                .append(" LIMIT ").append(EventFacetCounts.VALUE_LIMIT);
        return jdbcTemplate.query(sql.toString(), this::mapValueCount, args.toArray());
    }

    /**
     * Project counts group events by session first (riding idx_agent_events_session_observed's
     * key column), then fold the small agent_sessions table over the per-session totals — the
     * spec's §6.5 SQL sketch. Joining sessions per event row measured ~90ms slower on the live
     * corpus for the same result.
     */
    private List<EventFacetCounts.ValueCount> projectCounts(
            EventQuery facets, boolean meaningfulOnly, List<String> projectScopes) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(SESSION_CANONICAL_CWD_SQL).append(" AS value, SUM(t.cnt) AS cnt\n")
                .append("  FROM (SELECT e.session_id AS session_id, COUNT(*) AS cnt\n")
                .append("          FROM agent_events e\n");
        appendSessionsJoinIfNeeded(sql, facets, Field.PROJECT);
        sql.append("         WHERE 1=1\n");
        appendQueryPredicates(sql, args, facets, meaningfulOnly, projectScopes, Field.PROJECT, true);
        sql.append("         GROUP BY e.session_id) t\n")
                .append("  JOIN agent_sessions s ON s.id = t.session_id\n")
                .append(" GROUP BY value\n")
                .append(" ORDER BY cnt DESC, value ASC\n")
                .append(" LIMIT ").append(EventFacetCounts.VALUE_LIMIT);
        return jdbcTemplate.query(sql.toString(), this::mapValueCount, args.toArray());
    }

    private EventFacetCounts.ValueCount mapValueCount(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new EventFacetCounts.ValueCount(rs.getString("value"), rs.getLong("cnt"));
    }

    /**
     * The counts queries join agent_sessions only when some surviving predicate actually reads
     * {@code s.*} — project substring/exact/group tokens. The common unfaceted query then scans
     * agent_events alone, which measured ~30-90ms faster per GROUP BY on the live corpus.
     */
    private void appendSessionsJoinIfNeeded(StringBuilder sql, EventQuery facets, Field droppedInclude) {
        boolean needsSessions = (droppedInclude != Field.PROJECT && !facets.values(Field.PROJECT).isEmpty())
                || !facets.excluded(Field.PROJECT).isEmpty()
                || !facets.values(Field.PROJECT_EXACT).isEmpty()
                || !facets.excluded(Field.PROJECT_EXACT).isEmpty()
                || !facets.projectGroups().isEmpty();
        if (needsSessions) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
    }

    /**
     * The one grammar-driven WHERE builder shared by the feed and the facet counts. Appends every
     * predicate the parsed query implies; {@code droppedInclude} omits that single field's include
     * list (counts' "what if I switched" semantics — its exclusions still apply). Returns whether
     * the free-text predicate rode FTS, so callers can fail soft on a broken index.
     */
    private boolean appendQueryPredicates(
            StringBuilder sql,
            List<Object> args,
            EventQuery facets,
            boolean meaningfulOnly,
            List<String> projectScopes,
            Field droppedInclude,
            boolean applySessionFacet) {
        if (droppedInclude != Field.SOURCE) {
            appendInList(sql, args, "lower(e.source)", facets.values(Field.SOURCE), false);
        }
        if (droppedInclude != Field.KIND) {
            appendInList(sql, args, "lower(e.event_type)", facets.values(Field.KIND), false);
        }
        if (droppedInclude != Field.TOOL) {
            appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.values(Field.TOOL), false);
        }
        if (droppedInclude != Field.PROJECT) {
            appendCwdLikes(sql, args, facets.values(Field.PROJECT), false);
        }
        List<String> exactCwds = facets.values(Field.PROJECT_EXACT);
        if (!exactCwds.isEmpty()) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" IN (")
                    .append(String.join(", ", Collections.nCopies(exactCwds.size(), "?")))
                    .append(")\n");
            args.addAll(exactCwds);
        }
        appendProjectGroup(sql, args, facets.projectGroups(), projectScopes);
        appendInList(sql, args, "lower(e.source)", facets.excluded(Field.SOURCE), true);
        appendInList(sql, args, "lower(e.event_type)", facets.excluded(Field.KIND), true);
        appendInList(sql, args, "lower(coalesce(e.tool_name, ''))", facets.excluded(Field.TOOL), true);
        appendCwdLikes(sql, args, facets.excluded(Field.PROJECT), true);
        List<String> excludedExactCwds = facets.excluded(Field.PROJECT_EXACT);
        if (!excludedExactCwds.isEmpty()) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" NOT IN (")
                    .append(String.join(", ", Collections.nCopies(excludedExactCwds.size(), "?")))
                    .append(")\n");
            args.addAll(excludedExactCwds);
        }
        if (applySessionFacet) {
            facets.sessionRef().ifPresent(ref -> {
                // Resolve through agent_sessions (small, uniquely keyed) so the event scan rides
                // idx_agent_events_session_observed; a naive OR on agent_events table-scans.
                sql.append("   AND e.session_id IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)\n");
                args.add(ref);
                args.add(ref);
            });
        }
        // Free text prefers the FTS5 index (which also reaches the clipped tool JSON in its
        // `extra` column) and falls back to the per-term LIKE path with identical AND semantics
        // whenever FTS is absent or the backfill has not finished.
        boolean usedFts = !facets.freeTerms().isEmpty() && ftsIndex.ready();
        if (usedFts) {
            sql.append("   AND e.rowid IN (SELECT rowid FROM event_fts WHERE event_fts MATCH ?)\n");
            args.add(EventFtsIndex.matchExpression(facets.freeTerms()));
        }
        else {
            for (String term : facets.freeTerms()) {
                String like = "%" + term.toLowerCase() + "%";
                sql.append("   AND (lower(coalesce(e.text, '')) LIKE ?")
                        .append(" OR lower(coalesce(e.tool_name, '')) LIKE ?")
                        .append(" OR lower(substr(coalesce(e.tool_input_json, ''), 1, 2000)) LIKE ?")
                        .append(" OR lower(substr(coalesce(e.tool_output_json, ''), 1, 6000)) LIKE ?")
                        .append(" OR lower(substr(coalesce(e.metadata_json, ''), 1, 4000)) LIKE ?)\n");
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
            }
        }
        if (meaningfulOnly && !facets.includeAll()) {
            sql.append("   AND ").append(MEANINGFUL_EVENT_PREDICATE).append("\n");
        }
        facets.sinceSpec().ifPresent(spec -> {
            sql.append("   AND e.observed_at >= ?\n");
            args.add(spec.resolve(clock).toString());
        });
        facets.untilSpec().ifPresent(spec -> {
            sql.append(spec.exclusiveEnd() ? "   AND e.observed_at < ?\n" : "   AND e.observed_at <= ?\n");
            args.add(spec.resolve(clock).toString());
        });
        return usedFts;
    }

     /**
     * Writes a session's summary and its AI-derived title atomically, so a half-applied summarize
     * can never leave a session summarized but still wearing its weak ingest-time title.
     */
    @Transactional
    public void saveSummaryAndTitle(String sessionId, String summary, String title, int titleRank) {
        jdbcTemplate.update("UPDATE agent_sessions SET summary = ? WHERE id = ?", summary, sessionId);
        jdbcTemplate.update("UPDATE agent_sessions SET title = ?, title_rank = ? WHERE id = ?",
                title, titleRank, sessionId);
    }

    public StorageStats stats() {
        Long sessions = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_sessions", Long.class);
        Long events = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_events", Long.class);
        return new StorageStats(sessions == null ? 0 : sessions, events == null ? 0 : events);
    }

    public DashboardStats dashboardStats() {
        StorageStats totals = stats();
        return new DashboardStats(
                totals.sessions(),
                totals.events(),
                countBy("""
                        SELECT source AS name, COUNT(*) AS count
                          FROM agent_events
                         GROUP BY source
                         ORDER BY count DESC, name ASC
                        """),
                countBy("""
                        SELECT event_type AS name, COUNT(*) AS count
                          FROM agent_events
                         GROUP BY event_type
                         ORDER BY count DESC, name ASC
                        """),
                countBy("""
                        SELECT source AS name, COUNT(*) AS count
                          FROM agent_sessions
                         GROUP BY source
                         ORDER BY count DESC, name ASC
                        """),
                postgres ? dailyCounts("""
                        SELECT substring(observed_at, 1, 10) AS day, COUNT(*) AS count
                          FROM agent_events
                         WHERE substring(observed_at, 1, 10) >= ?
                           AND substring(observed_at, 1, 10) <= ?
                         GROUP BY substring(observed_at, 1, 10)
                         ORDER BY day ASC
                        """, java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).minusDays(13).toString(),
                        java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).toString())
                : dailyCounts("""
                        SELECT date(observed_at) AS day, COUNT(*) AS count
                          FROM agent_events
                         WHERE date(observed_at) >= date('now', ?)
                           AND date(observed_at) <= date('now')
                         GROUP BY date(observed_at)
                         ORDER BY day ASC
                        """, "-13 days"));
    }

    private List<DashboardStats.BreakdownCount> countBy(String sql, Object... args) {
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new DashboardStats.BreakdownCount(rs.getString("name"), rs.getLong("count")),
                args);
    }

    private List<DashboardStats.DailyCount> dailyCounts(String sql, Object... args) {
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new DashboardStats.DailyCount(rs.getString("day"), rs.getLong("count")),
                args);
    }

    private void appendProjectGroup(
            StringBuilder sql,
            List<Object> args,
            List<String> projectGroups,
            List<String> projectScopes) {
        if (projectGroups.isEmpty()) {
            return;
        }
        List<String> scopes = projectScopes == null ? List.of() : projectScopes;
        if (scopes.isEmpty()) {
            sql.append("   AND 1=0\n");
            return;
        }
        sql.append("   AND ")
                .append(SESSION_CANONICAL_CWD_SQL)
                .append(" IN (")
                .append(String.join(", ", Collections.nCopies(scopes.size(), "?")))
                .append(")\n");
        args.addAll(scopes);
    }

    private static void appendInList(
            StringBuilder sql, List<Object> args, String columnExpr, List<String> values, boolean negated) {
        if (values.isEmpty()) {
            return;
        }
        sql.append("   AND ").append(columnExpr).append(negated ? " NOT IN (" : " IN (")
                .append(String.join(", ", Collections.nCopies(values.size(), "lower(?)")))
                .append(")\n");
        args.addAll(values);
    }

    private static void appendCwdLikes(
            StringBuilder sql, List<Object> args, List<String> values, boolean negated) {
        if (values.isEmpty()) {
            return;
        }
        if (negated) {
            for (String value : values) {
                sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
                args.add("%" + value + "%");
            }
            return;
        }
        sql.append("   AND (")
                .append(String.join(" OR ",
                        Collections.nCopies(values.size(), "lower(coalesce(s.cwd, '')) LIKE lower(?)")))
                .append(")\n");
        for (String value : values) {
            args.add("%" + value + "%");
        }
    }

    private AgentSession mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentSession(
                rs.getString("id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("title"),
                rs.getString("cwd"),
                rs.getString("summary"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("last_seen_at")),
                rs.getLong("event_count"),
                rs.getString("spawned_by"));
    }

    private AgentEvent mapEvent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentEvent(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("source"),
                rs.getString("client_session_id"),
                rs.getString("turn_id"),
                rs.getString("event_type"),
                rs.getString("role"),
                rs.getString("text"),
                rs.getString("tool_name"),
                rs.getString("tool_input_json"),
                rs.getString("tool_output_json"),
                fromJsonMap(rs.getString("metadata_json")),
                Instant.parse(rs.getString("observed_at")));
    }

    private EventFeedItem mapFeedItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        AgentEvent event = mapEvent(rs, rowNum);
        return new EventFeedItem(
                event.id(),
                event.sessionId(),
                event.source(),
                event.clientSessionId(),
                event.turnId(),
                event.eventType(),
                event.role(),
                event.text(),
                event.toolName(),
                event.toolInputJson(),
                event.toolOutputJson(),
                event.metadata(),
                event.observedAt(),
                rs.getString("cwd"),
                rs.getString("session_title"));
    }

    private static FeedCursor parseBefore(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        String[] parts = before.split("\\|", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid before cursor. Expected '<observedAt>|<id>'.");
        }
        try {
            return new FeedCursor(Instant.parse(parts[0]).toString(), parts[1]);
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid before cursor. Expected '<observedAt>|<id>'.", ex);
        }
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid since timestamp.", ex);
        }
    }

    private static String cursorFor(EventFeedItem item) {
        return item.observedAt() + "|" + item.id();
    }

    private record FeedCursor(String observedAt, String id) {
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize event payload", ex);
        }
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

    private static void addTranscriptPath(java.util.Set<String> paths, Map<?, ?> values) {
        Object snake = values.get("transcript_path");
        Object camel = values.get("transcriptPath");
        Object candidate = snake instanceof String ? snake : camel;
        if (candidate instanceof String path && !path.isBlank()) {
            paths.add(path);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
