package dev.nathan.sbaagentic.event;

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

import dev.nathan.sbaagentic.search.QueryFacets;
import dev.nathan.sbaagentic.session.AgentSession;
import dev.nathan.sbaagentic.session.TitleRank;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String MEANINGFUL_EVENT_PREDICATE = """
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

    private static final String SESSION_CANONICAL_CWD_SQL = """
            CASE
              WHEN s.cwd IS NULL OR trim(s.cwd) = '' THEN '__no_project__'
              WHEN rtrim(trim(s.cwd), '/') = '' THEN '/'
              ELSE rtrim(trim(s.cwd), '/')
            END
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Idempotent migration for databases created before title ranking existed.
     * {@code schema.sql} runs on every startup, and SQLite has no
     * {@code ADD COLUMN IF NOT EXISTS}, so the column add lives here. Fresh
     * databases already get {@code title_rank} from the CREATE statement and
     * skip this entirely.
     */
    @PostConstruct
    void ensureSchema() {
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
    }

    /**
     * Persists an event together with its session as one atomic unit. The session upsert, the event
     * insert, and the event-count bump either all land or none do, so a crash mid-ingest can never
     * leave a session whose count disagrees with its events.
     */
    @Transactional
    public Persisted persistEvent(EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        AgentSession session = findOrCreateSession(request, observedAt, title, titleRank);
        AgentEvent event = saveEvent(request, session, observedAt);
        AgentSession updated = findSessionById(session.id()).orElse(session);
        return new Persisted(updated, event);
    }

    /** A persisted event and the session snapshot it landed in. */
    public record Persisted(AgentSession session, AgentEvent event) {
    }

    public AgentSession findOrCreateSession(EventIngestRequest request, Instant observedAt, String title, int titleRank) {
        // One atomic upsert: insert a fresh session, or — when (source, client_session_id) already
        // exists — bump its activity and upgrade the title only if this event carries a strictly
        // higher-ranked one. ON CONFLICT makes find-or-create race-free: two concurrent first events
        // for the same session can't double-insert or trip the UNIQUE constraint. started_at and
        // event_count are set only on insert, so an existing session keeps its origin and count.
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO agent_sessions (
                    id, source, client_session_id, title, title_rank, cwd, started_at, last_seen_at, event_count
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (source, client_session_id) DO UPDATE SET
                    last_seen_at = excluded.last_seen_at,
                    cwd = COALESCE(excluded.cwd, agent_sessions.cwd),
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
                observedAt.toString(),
                observedAt.toString());
        return findSession(request.source(), request.clientSessionId()).orElseThrow();
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
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
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
                    SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
                      FROM agent_sessions
                     WHERE id = ?
                    """, this::mapSession, id));
        }
        catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<AgentSession> recentSessions(int limit) {
        return jdbcTemplate.query("""
                SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
                  FROM agent_sessions
                 ORDER BY last_seen_at DESC
                 LIMIT ?
                """, this::mapSession, limit);
    }

    public List<AgentSession> recentSessionsMissingSummary(int limit) {
        return jdbcTemplate.query("""
                SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count
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

    public List<AgentEvent> searchEvents(String query, int limit) {
        QueryFacets facets = QueryFacets.parse(query);

        // No field:value tokens — keep the original behaviour exactly: the whole query is one
        // case-insensitive substring matched across the event's text columns, newest first.
        if (!facets.hasAnyFacet()) {
            String like = "%" + (query == null ? "" : query).toLowerCase() + "%";
            return jdbcTemplate.query("""
                    SELECT id, session_id, source, client_session_id, turn_id, event_type, role, text,
                           tool_name, tool_input_json, tool_output_json, metadata_json, observed_at
                      FROM agent_events
                     WHERE lower(coalesce(text, '')) LIKE ?
                        OR lower(coalesce(tool_name, '')) LIKE ?
                        OR lower(coalesce(event_type, '')) LIKE ?
                        OR lower(coalesce(source, '')) LIKE ?
                        OR lower(coalesce(metadata_json, '')) LIKE ?
                     ORDER BY observed_at DESC
                     LIMIT ?
                    """, this::mapEvent, like, like, like, like, like, limit);
        }

        // Facet-aware path: equality on each present keyword facet (parameterised — values never
        // interpolated), an optional join to agent_sessions for the project/cwd facet, and any
        // remaining free text matched as one substring across the event columns.
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder()
                .append("SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, ")
                .append("e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, ")
                .append("e.observed_at\n")
                .append("  FROM agent_events e\n");
        boolean joinSessions = facets.cwd() != null || facets.excludedCwd() != null || facets.exactCwd() != null;
        if (joinSessions) {
            sql.append("  JOIN agent_sessions s ON s.id = e.session_id\n");
        }
        sql.append(" WHERE 1=1\n");
        if (facets.source() != null) {
            sql.append("   AND lower(e.source) = lower(?)\n");
            args.add(facets.source());
        }
        if (facets.eventType() != null) {
            sql.append("   AND lower(e.event_type) = lower(?)\n");
            args.add(facets.eventType());
        }
        if (facets.toolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) = lower(?)\n");
            args.add(facets.toolName());
        }
        if (facets.cwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) LIKE lower(?)\n");
            args.add("%" + facets.cwd() + "%");
        }
        if (facets.exactCwd() != null) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" = ?\n");
            args.add(facets.exactCwd());
        }
        if (facets.excludedSource() != null) {
            sql.append("   AND lower(e.source) <> lower(?)\n");
            args.add(facets.excludedSource());
        }
        if (facets.excludedEventType() != null) {
            sql.append("   AND lower(e.event_type) <> lower(?)\n");
            args.add(facets.excludedEventType());
        }
        if (facets.excludedToolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) <> lower(?)\n");
            args.add(facets.excludedToolName());
        }
        if (facets.excludedCwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
            args.add("%" + facets.excludedCwd() + "%");
        }
        String freePhrase = facets.freeTextPhrase();
        if (!freePhrase.isBlank()) {
            String like = "%" + freePhrase.toLowerCase() + "%";
            sql.append("   AND (lower(coalesce(e.text, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.tool_name, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.metadata_json, '')) LIKE ?)\n");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY e.observed_at DESC\n")
                .append(" LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    public EventFeedResponse feed(String query, boolean meaningfulOnly, String before, String since, int limit) {
        QueryFacets facets = QueryFacets.parse(query);
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
        if (facets.source() != null) {
            sql.append("   AND lower(e.source) = lower(?)\n");
            args.add(facets.source());
        }
        if (facets.eventType() != null) {
            sql.append("   AND lower(e.event_type) = lower(?)\n");
            args.add(facets.eventType());
        }
        if (facets.toolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) = lower(?)\n");
            args.add(facets.toolName());
        }
        if (facets.cwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) LIKE lower(?)\n");
            args.add("%" + facets.cwd() + "%");
        }
        if (facets.exactCwd() != null) {
            sql.append("   AND ").append(SESSION_CANONICAL_CWD_SQL).append(" = ?\n");
            args.add(facets.exactCwd());
        }
        if (facets.excludedSource() != null) {
            sql.append("   AND lower(e.source) <> lower(?)\n");
            args.add(facets.excludedSource());
        }
        if (facets.excludedEventType() != null) {
            sql.append("   AND lower(e.event_type) <> lower(?)\n");
            args.add(facets.excludedEventType());
        }
        if (facets.excludedToolName() != null) {
            sql.append("   AND lower(coalesce(e.tool_name, '')) <> lower(?)\n");
            args.add(facets.excludedToolName());
        }
        if (facets.excludedCwd() != null) {
            sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
            args.add("%" + facets.excludedCwd() + "%");
        }
        String freePhrase = facets.freeTextPhrase();
        if (!freePhrase.isBlank()) {
            String like = "%" + freePhrase.toLowerCase() + "%";
            sql.append("   AND (lower(coalesce(e.text, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.tool_name, '')) LIKE ?")
                    .append(" OR lower(coalesce(e.metadata_json, '')) LIKE ?)\n");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (meaningfulOnly) {
            sql.append("   AND ").append(MEANINGFUL_EVENT_PREDICATE).append("\n");
        }
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

        List<EventFeedItem> fetched = jdbcTemplate.query(sql.toString(), this::mapFeedItem, args.toArray());
        boolean hasMore = fetched.size() > limit;
        List<EventFeedItem> kept = hasMore ? fetched.subList(0, limit) : fetched;
        String nextBefore = hasMore && !kept.isEmpty() ? cursorFor(kept.get(kept.size() - 1)) : null;
        return new EventFeedResponse(limit, kept.size(), List.copyOf(kept), nextBefore);
    }

    /** A whitelisted enumerable column resolved to its physical table and column name. */
    private record FieldColumn(String table, String column) {
    }

    /**
     * SQLite fallback for query-bar value autocomplete when Elasticsearch is off. Returns the
     * distinct, prefix-matching values of a single whitelisted column.
     *
     * <p>The logical {@code field} name is mapped through a fixed switch to a physical
     * (table, column) pair — the column is never interpolated from user input, so an unknown or
     * crafted field yields an empty list rather than an injection vector. The four event columns
     * read from {@code agent_events}; {@code cwd} lives on {@code agent_sessions}. Both snake_case
     * and camelCase logical names are accepted so callers can pass either the UI field name or the
     * physical column name. The prefix is a bound parameter and the limit is clamped to {@code <=50}.
     */
    public List<String> distinctFieldValues(String field, String prefix, int limit) {
        FieldColumn target = switch (field) {
            case "source" -> new FieldColumn("agent_events", "source");
            case "event_type", "eventType" -> new FieldColumn("agent_events", "event_type");
            case "tool_name", "toolName" -> new FieldColumn("agent_events", "tool_name");
            case "client_session_id", "clientSessionId" -> new FieldColumn("agent_events", "client_session_id");
            case "cwd" -> new FieldColumn("agent_sessions", "cwd");
            default -> null;
        };
        if (target == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String like = (prefix == null ? "" : prefix) + "%";
        // table and column come only from the fixed switch above, never from caller input.
        String sql = "SELECT DISTINCT " + target.column() + " AS v"
                + "   FROM " + target.table()
                + "  WHERE " + target.column() + " IS NOT NULL AND " + target.column() + " <> ''"
                + "    AND lower(" + target.column() + ") LIKE lower(?)"
                + "  ORDER BY " + target.column()
                + "  LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("v"), like, safeLimit);
    }

    /**
     * Reads prior intent back out for {@link dev.nathan.sbaagentic.context.ContextService}. Filters
     * to the given event types, bounds the window by {@code since}, and — when {@code scopeLike} is
     * present — matches it against the session's working directory (recall by where you are working)
     * or the event text (recall by what you are working on). Newest first.
     */
    public List<AgentEvent> recall(List<String> eventTypes, String scopeLike, Instant since, int limit) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(eventTypes.size(), "?"));
        List<Object> args = new ArrayList<>(eventTypes);
        args.add(since.toString());
        StringBuilder sql = new StringBuilder()
                .append("SELECT e.id, e.session_id, e.source, e.client_session_id, e.turn_id, e.event_type, ")
                .append("e.role, e.text, e.tool_name, e.tool_input_json, e.tool_output_json, e.metadata_json, ")
                .append("e.observed_at\n")
                .append("  FROM agent_events e\n")
                .append("  JOIN agent_sessions s ON e.session_id = s.id\n")
                .append(" WHERE e.event_type IN (").append(placeholders).append(")\n")
                .append("   AND e.observed_at >= ?");
        if (scopeLike != null) {
            sql.append("\n   AND (lower(coalesce(s.cwd, '')) LIKE ? OR lower(coalesce(e.text, '')) LIKE ?)");
            args.add(scopeLike);
            args.add(scopeLike);
        }
        sql.append("\n ORDER BY e.observed_at DESC\n LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
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
                dailyCounts("""
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
                rs.getLong("event_count"));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
