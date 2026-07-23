# Subagent Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record Claude Code subagents as real child sessions linked `spawned` to their parents, and show sessions as a lineage tree in browse.

**Architecture:** The hook bridge stays a single never-fail POST per event; the recording module mints child sessions (key `<parent>:<agent_id>`) and stamps a `spawned_by` hint; a workflow-module listener turns SubagentStart/Stop events into `session_links` rows exactly as the Codex runner does today; the SolidJS browse rail nests children lazily and the existing DagView is un-gated. Spec: `docs/superpowers/specs/2026-07-23-subagent-lineage-design.md`.

**Tech Stack:** Java 21 / Spring Boot modular monolith (Spring Modulith ratchet), SQLite (JdbcTemplate), bash hook scripts, SolidJS + Vite + vitest frontend compiled into committed `static/`.

## Global Constraints

- Canonical names (all areas): column `agent_sessions.spawned_by TEXT NULL`; record field `AgentSession.spawnedBy` (nullable, LAST positional component); param `includeChildren` (default false) on `GET /api/sessions`; child key `"<parentClientSessionId>:<agentId>"` with `source=claude`; metadata keys `agentId`, `agentType`, `parentClientSessionId`; eventTypes `SubagentStart`/`SubagentStop`; listener `dev.nathan.sbaagentic.workflow.internal.application.SubagentLinkListener` at `@Order(25)` (after ElasticIndexClient@20, BEFORE EventBroadcaster@30 so the `session.updated` SSE frame sees the link); endpoint `GET /api/session-links/child-counts?ids=` returning `{"<sessionId>": count}`.
- SubagentLinkListener resolves the PARENT via `findSession(event.source(), parentClientSessionId)` and takes the CHILD from the recorded event's own session — no second lookup, no hardcoded source.
- Hook never-fail contract is inviolable: no `set -e`, jq guard, `curl -fsS --max-time 3 ... || true`, unconditional `exit 0`.
- Module ratchet must stay green (`ApplicationModuleStructureTest`): recording depends on nothing; workflow may depend on recording; never the reverse.
- Wire-contract fixtures (`wire-fixtures.json`, `rest-contract-matrix.json`, `rest-mappings.txt`) change in the SAME commit as the record/endpoint they describe.
- Commits: Nathan sole author, imperative Title Case subject, NO Co-Authored-By / Generated-with lines.
- Lineage depth truth: single-level is guaranteed; a subagent spawning a subagent attaches to the TOP-LEVEL session (hook payloads carry the root session_id) — do not build UI assumptions about grandchildren under intermediate children.
- Verify bar: `mvn -q test` green incl. ratchets; `cd frontend && npx vitest run` green; no `mvn package` needed — if packaging happens anyway, run `scripts/deploy-local.sh` (live :8766 service).

---

### Task 1: Add spawned_by Lineage Column, Migration, and AgentSession.spawnedBy

**Files:**
- Modify: `src/main/resources/schema.sql:1-13` (agent_sessions CREATE TABLE)
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/AgentSession.java:5-15`
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java:79-97` (ensureSchema), `:192-235` (four session SELECT column lists), `:421-432` (mapSession)
- Modify: `src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java:123-131` and `:143-151` (two session SELECTs), `:404-415` (mapSession)
- Modify: `src/test/java/dev/nathan/sbaagentic/recording/internal/application/RecordingLifecyclePublicationTest.java:26-28` (constructor arity)
- Modify: `src/test/java/dev/nathan/sbaagentic/search/ElasticIndexClientTest.java:273-285` (constructor arity)
- Modify: `src/test/resources/contracts/wire-fixtures.json:4` (AgentSession fixture — lockstep, same commit)
- Modify: `src/test/resources/contracts/rest-contract-matrix.json:7,8,12,18,19` (responseFields for the five AgentSession-shaped rows)
- Test: `src/test/java/dev/nathan/sbaagentic/event/EventRepositoryMigrationTest.java` (note: file lives under `event/` but declares `package dev.nathan.sbaagentic.recording;` — keep that)

**Interfaces:**
- Consumes: existing migration pattern `RecordingSqlStore.ensureSchema()` (`PRAGMA table_info(agent_sessions)` + `ALTER TABLE`, the `title_rank` precedent); `spring.sql.init.mode=always` re-runs idempotent `schema.sql` (`CREATE TABLE IF NOT EXISTS` no-ops on existing DBs, so column adds MUST go through `ensureSchema` — this is how this repo evolves SQLite schema); test helpers `@TempDir` + `DriverManagerDataSource` + `JdbcTemplate` from `EventRepositoryMigrationTest`.
- Produces: column `agent_sessions.spawned_by TEXT NULL`; record `AgentSession(String id, String source, String clientSessionId, String title, String cwd, String summary, Instant startedAt, Instant lastSeenAt, long eventCount, String spawnedBy)` — `spawnedBy` is the LAST positional component, nullable, serialized as `"spawnedBy"`.

**Steps:**

- [ ] Write the failing migration test. Append to `src/test/java/dev/nathan/sbaagentic/event/EventRepositoryMigrationTest.java` (all imports already present in the file):

```java
    @Test
    void addsSpawnedByColumnOnPreLineageDatabase(@TempDir Path tempDir) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("pre-lineage.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Recreate the pre-lineage schema (title_rank present, no spawned_by) with one session.
        jdbc.execute("""
                CREATE TABLE agent_sessions (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    client_session_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    title_rank INTEGER NOT NULL DEFAULT 1,
                    cwd TEXT,
                    summary TEXT,
                    started_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    event_count INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (source, client_session_id)
                )
                """);
        jdbc.update("""
                INSERT INTO agent_sessions (id, source, client_session_id, title, title_rank, started_at, last_seen_at, event_count)
                VALUES ('s1', 'claude', 'pre-lineage-session', 'Parent work', 3, '2026-07-20T12:00:00Z', '2026-07-20T12:00:00Z', 1)
                """);

        RecordingSqlStore repository = new RecordingSqlStore(
                jdbc,
                new ObjectMapper());
        repository.ensureSchema();

        // Existing sessions backfill as parents: spawned_by is NULL and maps through the record.
        assertThat(jdbc.queryForObject("SELECT spawned_by FROM agent_sessions WHERE id = 's1'", String.class))
                .isNull();
        assertThat(repository.findSessionById("s1").orElseThrow().spawnedBy()).isNull();

        // Running again is an idempotent no-op.
        repository.ensureSchema();
        assertThat(repository.findSessionById("s1").orElseThrow().spawnedBy()).isNull();
    }
```

- [ ] Run: `mvn -q test -Dtest=EventRepositoryMigrationTest` — expect FAIL with COMPILATION ERROR: `cannot find symbol: method spawnedBy()` (the record field does not exist yet).
- [ ] Add the column to `src/main/resources/schema.sql` — in the `agent_sessions` CREATE TABLE, after `event_count INTEGER NOT NULL DEFAULT 0,` insert:

```sql
    spawned_by TEXT,
```

- [ ] Replace `src/main/java/dev/nathan/sbaagentic/recording/AgentSession.java` in full:

```java
package dev.nathan.sbaagentic.recording;

import java.time.Instant;

public record AgentSession(
        String id,
        String source,
        String clientSessionId,
        String title,
        String cwd,
        String summary,
        Instant startedAt,
        Instant lastSeenAt,
        long eventCount,
        String spawnedBy) {
}
```

- [ ] Add the idempotent migration in `RecordingSqlStore.ensureSchema()`, after the existing `if (!hasTitleRank) { ... }` block (same `columns` list, same pattern — SQLite has no `ADD COLUMN IF NOT EXISTS`):

```java
        boolean hasSpawnedBy = columns.stream()
                .anyMatch(column -> "spawned_by".equalsIgnoreCase(String.valueOf(column.get("name"))));
        if (!hasSpawnedBy) {
            jdbcTemplate.execute("ALTER TABLE agent_sessions ADD COLUMN spawned_by TEXT");
        }
```

- [ ] In `RecordingSqlStore`, replace ALL FOUR occurrences (findSession, findSessionById, recentSessions, recentSessionsMissingSummary) of
`SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count` with
`SELECT id, source, client_session_id, title, cwd, summary, started_at, last_seen_at, event_count, spawned_by`, and extend `mapSession` to read it:

```java
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
```

- [ ] In `ProjectRepository`, in BOTH `sessionsForProject` and `sessionsForProjectByIds`, change the select list
`SELECT s.id, s.source, s.client_session_id, s.title, s.cwd, s.summary,` / `s.started_at, s.last_seen_at, s.event_count` to end `s.started_at, s.last_seen_at, s.event_count, s.spawned_by`, and in its `mapSession` (line 404) add `rs.getString("spawned_by")` as the last constructor argument (mirroring the RecordingSqlStore mapper above, but with `ResultSet`/`SQLException` already imported in that file).
- [ ] Fix the two test constructors: in `RecordingLifecyclePublicationTest` change `observedAt, observedAt, 1);` to `observedAt, observedAt, 1, null);` and in `ElasticIndexClientTest.session()` change the final `1);` to `1,` + new line `null);`.
- [ ] Lockstep wire fixture (same commit — `WireContractFixtureTest` does field-set equality and is red until this lands). In `src/test/resources/contracts/wire-fixtures.json` line 4, replace the AgentSession entry with:

```json
    "AgentSession": {"id":"session-1","source":"codex","clientSessionId":"client-1","title":"Refactor","cwd":"/repo","summary":"summary","startedAt":"2026-07-20T11:00:00Z","lastSeenAt":"2026-07-20T12:00:00Z","eventCount":1,"spawnedBy":null},
```

- [ ] Lockstep matrix docs: in `src/test/resources/contracts/rest-contract-matrix.json` append `spawnedBy` to `responseFields` on lines 7 (`GET /api/sessions` — the compressed form becomes `"[]:id,source,clientSessionId,title,titleRank,cwd,summary,startedAt,lastSeenAt,eventCount,spawnedBy"`), 8 (`GET /api/sessions/{sessionId}` — append `"spawnedBy"` to the array), 12 (`GET /api/projects/{projectKey}/sessions` — compressed form), 18 (`POST /api/sessions/{sessionId}/summarize` — array), and 19 (`POST /api/sessions/summarize` — array).
- [ ] Run: `mvn -q test -Dtest='EventRepositoryMigrationTest,WireContractFixtureTest,SqliteCompatibilityTest,RecordingLifecyclePublicationTest'` — expect PASS (SqliteCompatibilityTest proves the pre-refactor on-disk DB path migrates via ensureSchema; no change to `contracts/pre-refactor.sqlite.sql` needed).
- [ ] Commit: `git add src/main/resources/schema.sql src/main/java/dev/nathan/sbaagentic/recording/AgentSession.java src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java src/main/java/dev/nathan/sbaagentic/project/internal/adapter/out/sqlite/ProjectRepository.java src/test/java/dev/nathan/sbaagentic/event/EventRepositoryMigrationTest.java src/test/java/dev/nathan/sbaagentic/recording/internal/application/RecordingLifecyclePublicationTest.java src/test/java/dev/nathan/sbaagentic/search/ElasticIndexClientTest.java src/test/resources/contracts/wire-fixtures.json src/test/resources/contracts/rest-contract-matrix.json && git commit -m "Add Spawned By Lineage Column To Agent Sessions"`

### Task 2: Stamp spawned_by From Subagent Event Metadata and Seed agent_type Titles

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java:114-143` (findOrCreateSession upsert + new private helper; add `import java.util.Locale;`)
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/application/EventIngestService.java:119-131` (titleFor)
- Test: `src/test/java/dev/nathan/sbaagentic/event/EventIngestServiceTest.java` (`package dev.nathan.sbaagentic.recording;` — SpringBootTest wired against the real RecordingSqlStore; reuse its `ingestService`/`repository` autowired fields)

**Interfaces:**
- Consumes: event metadata JSON keys `agentId`, `agentType`, `parentClientSessionId` (hook posts them on `SubagentStart`/`SubagentStop`, recorded verbatim as eventType strings); child session key `"<parentClientSessionId>:<agentId>"` with `source=claude` (opaque to recording — identity stays `(source, client_session_id)`, upsert unchanged in shape); `TitleRank.FALLBACK` (= 1, the lowest auto-title rank) and `Titles.sanitize`; `RecordingStore.persistEvent(EventIngestRequest, Instant, String title, int titleRank)` signature UNCHANGED (stamping is derived inside the adapter from the request, so `RecordingLifecyclePublicationTest`'s store lambda and `SqliteCompatibilityTest`'s direct call keep compiling).
- Produces: upsert stamps `spawned_by` on insert from `metadata.parentClientSessionId` when normalized eventType is `subagentstart`/`subagentstop`; `ON CONFLICT` uses `COALESCE(agent_sessions.spawned_by, excluded.spawned_by)` so a stamp is never cleared or overwritten (covers the missed-SubagentStart edge: Stop alone mints and stamps); title candidate `(Titles.sanitize(metadata.agentType), TitleRank.FALLBACK)` — upgradeable by TEXT/TOOL/EXPLICIT/AI ranks.

**Steps:**

- [ ] Write the failing ingest tests. Append to `EventIngestServiceTest` (imports already present):

```java
    @Test
    void subagentStartMintsChildSessionStampedWithParentAndAgentTypeTitle() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-1:agent-abc",
                null,
                "SubagentStart",
                "agent",
                null,
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-abc",
                        "agentType", "code-reviewer",
                        "parentClientSessionId", "parent-1"),
                Instant.parse("2026-07-22T12:00:00Z")));

        AgentSession child = repository.findSession("claude", "parent-1:agent-abc").orElseThrow();
        assertThat(child.spawnedBy()).isEqualTo("parent-1");
        assertThat(child.title()).isEqualTo("code-reviewer");
    }

    @Test
    void subagentStopAloneMintsChildAndLaterEventsNeverClearSpawnedBy() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-2:agent-def",
                null,
                "SubagentStop",
                "assistant",
                "Reviewed the diff and found two issues.",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-def",
                        "agentType", "code-reviewer",
                        "parentClientSessionId", "parent-2"),
                Instant.parse("2026-07-22T12:01:00Z")));
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "parent-2:agent-def",
                null,
                "PostToolUse",
                "tool",
                null,
                "/tmp/project",
                "shell",
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T12:02:00Z")));

        assertThat(repository.findSession("claude", "parent-2:agent-def").orElseThrow().spawnedBy())
                .isEqualTo("parent-2");
    }

    @Test
    void ordinaryEventsWithoutSubagentMetadataStayUnspawned() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "plain-parent",
                null,
                "UserPromptSubmit",
                "user",
                "Hello",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T12:03:00Z")));

        assertThat(repository.findSession("claude", "plain-parent").orElseThrow().spawnedBy()).isNull();
    }
```

- [ ] Run: `mvn -q test -Dtest=EventIngestServiceTest` — expect FAIL: `child.spawnedBy()` expected `"parent-1"` but was `null`, and `child.title()` expected `"code-reviewer"` but was `"claude SubagentStart"` (today's FALLBACK title `source + " " + eventType`).
- [ ] Implement stamping in `RecordingSqlStore.findOrCreateSession` — add `import java.util.Locale;` to the imports, compute the stamp, and thread it through the existing atomic upsert (comment explains the COALESCE monotonicity):

```java
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
     * Event-type matching mirrors {@code EventIngestService}'s normalization (lowercase, alnum only).
     */
    private static String spawnedByFrom(EventIngestRequest request) {
        String type = request.eventType() == null
                ? ""
                : request.eventType().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!"subagentstart".equals(type) && !"subagentstop".equals(type)) {
            return null;
        }
        Object parent = request.metadata() == null ? null : request.metadata().get("parentClientSessionId");
        if (parent instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return null;
    }
```

- [ ] Implement the title seed in `EventIngestService.titleFor` — insert the agentType branch between the existing `toolName` branch and the final fallback return (rank FALLBACK = the lowest auto-title rank, so any later TEXT/TOOL/EXPLICIT/AI candidate upgrades it via the existing strictly-higher-rank upsert rule):

```java
    private TitleCandidate titleFor(EventIngestRequest request) {
        Object title = request.metadata().get("title");
        if (title instanceof String value && !value.isBlank()) {
            return new TitleCandidate(Titles.sanitize(value), TitleRank.EXPLICIT);
        }
        if (request.text() != null && !request.text().isBlank()) {
            return new TitleCandidate(Titles.sanitize(Titles.firstLine(request.text())), TitleRank.TEXT);
        }
        if (request.toolName() != null && !request.toolName().isBlank()) {
            return new TitleCandidate(Titles.sanitize(request.toolName() + " via " + request.eventType()), TitleRank.TOOL);
        }
        Object agentType = request.metadata().get("agentType");
        if (agentType instanceof String type && !type.isBlank()) {
            return new TitleCandidate(Titles.sanitize(type), TitleRank.FALLBACK);
        }
        return new TitleCandidate(Titles.sanitize(request.source() + " " + request.eventType()), TitleRank.FALLBACK);
    }
```

- [ ] Run: `mvn -q test -Dtest='EventIngestServiceTest,SqliteCompatibilityTest,RecordingLifecyclePublicationTest'` — expect PASS (the untouched persistEvent signature keeps the other two green).
- [ ] Commit: `git add src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java src/main/java/dev/nathan/sbaagentic/recording/internal/application/EventIngestService.java src/test/java/dev/nathan/sbaagentic/event/EventIngestServiceTest.java && git commit -m "Stamp Subagent Sessions With Their Spawning Parent"`

### Task 3: Default Session Lists to Parents Only With includeChildren Opt-In

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/RecordingCatalog.java:13` (add two-arg overload)
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java:218-225` (recentSessions)
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/in/web/EventController.java:62-65` (GET /api/sessions)
- Modify: `src/test/resources/contracts/rest-contract-matrix.json:7` (GET /api/sessions row — lockstep)
- Test: `src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java`, `src/test/java/dev/nathan/sbaagentic/event/EventIngestServiceTest.java`

**Interfaces:**
- Consumes: Task "spawned_by column" and Task "stamping" above (SubagentStart must stamp children before this filter is observable); `MemoryMcpTools.recentSessions` (`memory/internal/adapter/in/mcp/MemoryMcpTools.java:49-52`) calls `recordingCatalog.recentSessions(limit)` — the ONE-ARG method — so the MCP tool inherits parents-only with zero memory-module changes (confirmed by reading it; `contracts/mcp-tools.json` freezes only input schemas, which are unchanged). `SbaCli.java:77` uses the same one-arg call and inherits identically. `recentSessionsMissingSummary` is intentionally NOT filtered — child sessions must still be summarized by the SessionStopped pipeline.
- Produces: `RecordingCatalog.recentSessions(int limit, boolean includeChildren)` (new abstract method; existing `recentSessions(int limit)` now means parents-only, i.e. `WHERE spawned_by IS NULL`); `GET /api/sessions?includeChildren=true|false` — param name exactly `includeChildren`, boolean, default `false`.

**Steps:**

- [ ] Write the failing store-level test. Append to `EventIngestServiceTest`:

```java
    @Test
    void recentSessionsHidesChildrenByDefaultAndRevealsThemOnRequest() {
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "list-parent",
                null,
                "UserPromptSubmit",
                "user",
                "Parent prompt",
                "/tmp/project",
                null,
                null,
                null,
                Map.of(),
                Instant.parse("2026-07-22T14:00:00Z")));
        ingestService.ingest(new EventIngestRequest(
                "claude",
                "list-parent:agent-9",
                null,
                "SubagentStart",
                "agent",
                null,
                "/tmp/project",
                null,
                null,
                null,
                Map.of(
                        "agentId", "agent-9",
                        "agentType", "explorer",
                        "parentClientSessionId", "list-parent"),
                Instant.parse("2026-07-22T14:00:01Z")));

        // The one-arg overload is what the MCP recentSessions tool and the CLI call: parents only.
        assertThat(repository.recentSessions(50))
                .extracting(AgentSession::clientSessionId)
                .contains("list-parent")
                .doesNotContain("list-parent:agent-9");
        assertThat(repository.recentSessions(50, true))
                .extracting(AgentSession::clientSessionId)
                .contains("list-parent", "list-parent:agent-9");
    }
```

- [ ] Write the failing REST test. Append to `AgenticControllerTest` (uses its existing `mockMvc`, `hasItem`, `MediaType`, and the `jsonPath(...).isEmpty()` matcher; large limit defends against the class's shared in-memory DB):

```java
    @Test
    void sessionListHidesSubagentChildrenUnlessIncludeChildrenIsSet() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "lineage-parent",
                                  "eventType": "UserPromptSubmit",
                                  "role": "user",
                                  "text": "Spawn a reviewer subagent.",
                                  "observedAt": "2026-07-22T12:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "claude",
                                  "clientSessionId": "lineage-parent:agent-1",
                                  "eventType": "SubagentStart",
                                  "role": "agent",
                                  "metadata": {
                                    "agentId": "agent-1",
                                    "agentType": "code-reviewer",
                                    "parentClientSessionId": "lineage-parent"
                                  },
                                  "observedAt": "2026-07-22T12:00:01Z"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sessions").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.clientSessionId == 'lineage-parent')].title").value(hasItem("Spawn a reviewer subagent.")))
                .andExpect(jsonPath("$[?(@.clientSessionId == 'lineage-parent:agent-1')]").isEmpty());

        mockMvc.perform(get("/api/sessions").param("limit", "200").param("includeChildren", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.clientSessionId == 'lineage-parent:agent-1')].spawnedBy")
                        .value(hasItem("lineage-parent")));
    }
```

- [ ] Run: `mvn -q test -Dtest='EventIngestServiceTest,AgenticControllerTest'` — expect FAIL with COMPILATION ERROR: `cannot find symbol: method recentSessions(int,boolean)`.
- [ ] Implement the catalog overload in `RecordingCatalog.java` — replace line 13's single declaration with:

```java
    List<AgentSession> recentSessions(int limit);

    /** Flat listing that also includes subagent children; {@link #recentSessions(int)} hides them. */
    List<AgentSession> recentSessions(int limit, boolean includeChildren);
```

- [ ] Implement in `RecordingSqlStore` — replace the existing `recentSessions(int limit)` method body with a delegate plus the filtered two-arg query:

```java
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
```

- [ ] Implement in `EventController` — replace the `sessions` handler:

```java
    @GetMapping("/sessions")
    public List<AgentSession> sessions(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "false") boolean includeChildren) {
        return repository.recentSessions(safeLimit(limit), includeChildren);
    }
```

- [ ] Lockstep matrix: on line 7 of `rest-contract-matrix.json` change `"optionalInputs":["limit"]` to `"optionalInputs":["limit","includeChildren"]`, `"defaults":{"limit":25}` to `"defaults":{"limit":25,"includeChildren":false}`, and `"characterizationTest":"AgenticControllerTest#ingestAndListSessions"` to `"characterizationTest":"AgenticControllerTest#sessionListHidesSubagentChildrenUnlessIncludeChildrenIsSet"`.
- [ ] Run: `mvn -q test -Dtest='EventIngestServiceTest,AgenticControllerTest,RestContractSnapshotTest,WireContractFixtureTest'` — expect PASS (`rest-mappings.txt` needs no change: no new endpoint, only a param on an existing mapping).
- [ ] Commit: `git add src/main/java/dev/nathan/sbaagentic/recording/RecordingCatalog.java src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/in/web/EventController.java src/test/resources/contracts/rest-contract-matrix.json src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java src/test/java/dev/nathan/sbaagentic/event/EventIngestServiceTest.java && git commit -m "Hide Subagent Children From Default Session Lists"`

### Task 4: Treat SubagentStop as a Final Event

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/recording/internal/application/EventIngestService.java:24` (FINAL_EVENT_TYPES)
- Test: `src/test/java/dev/nathan/sbaagentic/recording/internal/application/RecordingLifecyclePublicationTest.java`

**Interfaces:**
- Consumes: `EventIngestService.isFinalEvent` (already `trim().toLowerCase(Locale.ROOT)`s the recorded eventType, so verbatim `"SubagentStop"` matches lowercase `"subagentstop"`); existing `SessionStopped(AgentSession session, AgentEvent event)` publication and the summary pipeline listening to it (child sessions get summarized for free — that is why `recentSessionsMissingSummary` stayed unfiltered).
- Produces: `FINAL_EVENT_TYPES = Set.of("sessionend", "stop", "subagentstop")` — ingesting a `SubagentStop` event publishes `SessionStopped` for the child session.

**Steps:**

- [ ] Write the failing publication test. Append to `RecordingLifecyclePublicationTest` (imports already present; fake-store lambda pattern copied from the existing test; AgentSession now takes the 10th `spawnedBy` argument):

```java
    @Test
    void subagentStopIsAFinalEventThatPublishesSessionStopped() {
        List<String> sequence = new ArrayList<>();
        Instant observedAt = Instant.parse("2026-07-22T13:00:00Z");
        AgentSession session = new AgentSession(
                "child-session-id", "claude", "parent-1:agent-abc", "code-reviewer", "/repo", null,
                observedAt, observedAt, 1, "parent-1");
        AgentEvent event = new AgentEvent(
                "event-id", session.id(), "claude", "parent-1:agent-abc", null, "SubagentStop",
                "assistant", "done", null, null, null,
                Map.of("agentId", "agent-abc", "agentType", "code-reviewer", "parentClientSessionId", "parent-1"),
                observedAt);
        RecordingStore store = (request, at, title, titleRank) -> {
            sequence.add("persisted");
            return new RecordingStore.Persisted(session, event);
        };
        EventIngestService service = new EventIngestService(
                store,
                new IngestionProperties(),
                new RedactionService(new IngestionProperties()),
                published -> {
                    if (published instanceof EventRecorded) {
                        sequence.add("recorded");
                    }
                    else if (published instanceof SessionStopped) {
                        sequence.add("stopped");
                    }
                });

        service.ingest(new EventIngestRequest(
                "claude", "parent-1:agent-abc", null, "SubagentStop", "assistant", "done", "/repo",
                null, null, null,
                Map.of("agentId", "agent-abc", "agentType", "code-reviewer", "parentClientSessionId", "parent-1"),
                observedAt));

        assertThat(sequence).containsExactly("persisted", "recorded", "stopped");
    }
```

- [ ] Run: `mvn -q test -Dtest=RecordingLifecyclePublicationTest` — expect FAIL: `containsExactly` actual is `["persisted", "recorded"]` — no `"stopped"` element.
- [ ] Implement — in `EventIngestService.java` line 24, change:

```java
    private static final Set<String> FINAL_EVENT_TYPES = Set.of("sessionend", "stop", "subagentstop");
```

- [ ] Run: `mvn -q test -Dtest=RecordingLifecyclePublicationTest` — expect PASS.
- [ ] Full recording-area verification sweep: `mvn -q test` — expect BUILD SUCCESS: architecture/module ratchet tests, `WireContractFixtureTest`, `RestContractSnapshotTest`, `McpContractSnapshotTest`, and all recording/project/web suites green. (Do not run `mvn package` — per repo ops notes a packaging build overwrites the jar the live :8766 launchd service runs from.)
- [ ] Commit: `git add src/main/java/dev/nathan/sbaagentic/recording/internal/application/EventIngestService.java src/test/java/dev/nathan/sbaagentic/recording/internal/application/RecordingLifecyclePublicationTest.java && git commit -m "Treat Subagent Stop As A Session Final Event"`

---

### Task 5: SubagentLinkListener — event-driven SPAWNED links in workflow

**Files:**
- Create: `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListener.java`
- Test: `src/test/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListenerTest.java`

**Interfaces:**
- Consumes: `dev.nathan.sbaagentic.recording.EventRecorded` (plain class: `AgentSession session()`, `AgentEvent event()`; published synchronously by `EventIngestService` after persistence, before `SessionStopped`); `dev.nathan.sbaagentic.recording.RecordingCatalog.findSession(String source, String clientSessionId)` → `Optional<AgentSession>`; `dev.nathan.sbaagentic.recording.AgentEvent` record accessors `eventType()`, `source()`, `clientSessionId()`, `metadata()` (`Map<String, Object>`); existing `SessionLinkService.createLink(CreateSessionLinkRequest)` which throws `LinkDomainException` with `code() == LinkErrorCode.DUPLICATE_LINK` on the SQLite unique triple; canonical event metadata keys `agentId`, `agentType`, `parentClientSessionId`; eventType strings `SubagentStart`/`SubagentStop` recorded verbatim (compare case-insensitively); child key `"<parentClientSessionId>:<agentId>"`, source `claude` (ingest lowercases source). In tests: `dev.nathan.sbaagentic.recording.EventRecorder.ingest(EventIngestRequest)` → `IngestResponse` (`sessionId()` accessor).
- Produces: bean `dev.nathan.sbaagentic.workflow.internal.application.SubagentLinkListener` with `@EventListener @Order(25) public void linkSubagentSession(EventRecorded recorded)` — creates a `LinkType.SPAWNED` link (taskId `null`) from parent session UUID to child session UUID; swallows `DUPLICATE_LINK`; skips silently when parent session is not yet recorded or metadata lacks `parentClientSessionId`. No other area calls this class; it is wired purely by Spring events.

**Notes:** The listener never constructs `AgentSession`/`AgentEvent` and never reads the new `agent_sessions.spawned_by` column, so it is insulated from the recording-area record change (`AgentSession.spawnedBy` added as last positional component). Tests drive the real path: `EventRecorder.ingest(...)` persists then publishes `EventRecorded` synchronously, so link rows are assertable immediately after `ingest` returns. Reuses the existing `@SpringBootTest` property block and `DELETE FROM ...` reset pattern from `TaskServiceIntegrationTest`/`SessionLinkRepositoryTest`.

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListenerTest.java`:

```java
package dev.nathan.sbaagentic.workflow.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.IngestResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/subagent-link-listener-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class SubagentLinkListenerTest {

    private static final String PARENT_KEY = "parent-client-session";
    private static final String CHILD_KEY = PARENT_KEY + ":agent-1";
    private static final Map<String, Object> SUBAGENT_METADATA = Map.of(
            "agentId", "agent-1",
            "agentType", "code-reviewer",
            "parentClientSessionId", PARENT_KEY);

    @Autowired
    EventRecorder recorder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM session_links");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
    }

    @Test
    void subagentStartLinksChildToRecordedParent() {
        IngestResponse parent = ingest(
                PARENT_KEY, "UserPromptSubmit", "user", "Spawn a reviewer.", Map.of());
        IngestResponse child = ingest(
                CHILD_KEY, "SubagentStart", "agent", null, SUBAGENT_METADATA);

        List<Map<String, Object>> links = linkRows();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("parent_session_id")).isEqualTo(parent.sessionId());
        assertThat(links.get(0).get("child_session_id")).isEqualTo(child.sessionId());
        assertThat(links.get(0).get("link_type")).isEqualTo("spawned");
        assertThat(links.get(0).get("task_id")).isNull();
    }

    @Test
    void subagentStopAloneStillLinksChild() {
        IngestResponse parent = ingest(
                PARENT_KEY, "UserPromptSubmit", "user", "Spawn a reviewer.", Map.of());
        IngestResponse child = ingest(
                CHILD_KEY, "SubagentStop", "assistant", "Reviewed the diff.", SUBAGENT_METADATA);

        List<Map<String, Object>> links = linkRows();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("parent_session_id")).isEqualTo(parent.sessionId());
        assertThat(links.get(0).get("child_session_id")).isEqualTo(child.sessionId());
        assertThat(links.get(0).get("link_type")).isEqualTo("spawned");
    }

    @Test
    void duplicateLinkAcrossStartAndStopIsSwallowed() {
        ingest(PARENT_KEY, "UserPromptSubmit", "user", "Spawn a reviewer.", Map.of());
        ingest(CHILD_KEY, "SubagentStart", "agent", null, SUBAGENT_METADATA);
        ingest(CHILD_KEY, "SubagentStop", "assistant", "Reviewed the diff.", SUBAGENT_METADATA);

        assertThat(linkRows()).hasSize(1);
    }

    @Test
    void missingParentSkipsLinkingSilently() {
        IngestResponse child = ingest(
                CHILD_KEY, "SubagentStart", "agent", null, SUBAGENT_METADATA);

        assertThat(linkRows()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_sessions WHERE id = ?",
                Integer.class, child.sessionId())).isEqualTo(1);
    }

    @Test
    void nonSubagentEventsAndMissingParentMetadataAreIgnored() {
        ingest(PARENT_KEY, "UserPromptSubmit", "user", "Spawn a reviewer.", Map.of());
        ingest(CHILD_KEY, "PostToolUse", "tool", null, SUBAGENT_METADATA);
        ingest(CHILD_KEY, "SubagentStart", "agent", null,
                Map.of("agentId", "agent-1", "agentType", "code-reviewer"));

        assertThat(linkRows()).isEmpty();
    }

    private IngestResponse ingest(
            String clientSessionId,
            String eventType,
            String role,
            String text,
            Map<String, Object> metadata) {
        return recorder.ingest(new EventIngestRequest(
                "claude",
                clientSessionId,
                "turn-1",
                eventType,
                role,
                text,
                "/tmp/lineage-project",
                null,
                null,
                null,
                metadata,
                Instant.parse("2026-07-23T12:00:00Z")));
    }

    private List<Map<String, Object>> linkRows() {
        return jdbcTemplate.queryForList(
                "SELECT parent_session_id, child_session_id, link_type, task_id FROM session_links");
    }
}
```

- [ ] **Step 2: Run test to verify the linking tests fail**

Run: `mvn -q test -Dtest=SubagentLinkListenerTest`
Expected: 5 tests run, 3 FAIL — `subagentStartLinksChildToRecordedParent`, `subagentStopAloneStillLinksChild` with `Expected size: 1 but was: 0`, and `duplicateLinkAcrossStartAndStopIsSwallowed` with the same size assertion. The two skip/ignore tests pass trivially (they assert absence). The file compiles without the listener because it only references recording's public API.

- [ ] **Step 3: Write the listener**

Create `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListener.java`:

```java
package dev.nathan.sbaagentic.workflow.internal.application;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.nathan.sbaagentic.recording.AgentEvent;
import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.EventRecorded;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.CreateSessionLinkRequest;
import dev.nathan.sbaagentic.workflow.LinkDomainException;
import dev.nathan.sbaagentic.workflow.LinkErrorCode;
import dev.nathan.sbaagentic.workflow.LinkType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Turns Claude subagent lifecycle events into {@code spawned} session links. Both
 * SubagentStart and SubagentStop carry the full parent metadata, so a link is attempted
 * on each: whichever arrives first while the parent session exists wins, the other is
 * swallowed as a duplicate. Runs synchronously inside ingest (after alias discovery at
 * order 10 and search indexing at order 20), so it must never throw for expected cases.
 */
@Component
public class SubagentLinkListener {

    private static final Logger log = LoggerFactory.getLogger(SubagentLinkListener.class);

    private final SessionLinkService links;
    private final RecordingCatalog sessions;

    public SubagentLinkListener(SessionLinkService links, RecordingCatalog sessions) {
        this.links = links;
        this.sessions = sessions;
    }

    @EventListener
    @Order(25)
    public void linkSubagentSession(EventRecorded recorded) {
        AgentEvent event = recorded.event();
        if (!isSubagentLifecycleEvent(event.eventType())) {
            return;
        }
        String parentClientSessionId = parentClientSessionId(event.metadata());
        if (parentClientSessionId == null) {
            return;
        }
        Optional<AgentSession> parent = sessions.findSession(event.source(), parentClientSessionId);
        if (parent.isEmpty()) {
            log.debug("Skipping subagent link for {}: parent {} not recorded yet",
                    event.clientSessionId(), parentClientSessionId);
            return;
        }
        try {
            links.createLink(new CreateSessionLinkRequest(
                    parent.get().id(), recorded.session().id(), LinkType.SPAWNED.value(), null));
        }
        catch (LinkDomainException ex) {
            if (ex.code() != LinkErrorCode.DUPLICATE_LINK) {
                throw ex;
            }
        }
    }

    private static boolean isSubagentLifecycleEvent(String eventType) {
        if (eventType == null) {
            return false;
        }
        String normalized = eventType.toLowerCase(Locale.ROOT);
        return normalized.equals("subagentstart") || normalized.equals("subagentstop");
    }

    private static String parentClientSessionId(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.get("parentClientSessionId") instanceof String value && !value.isBlank()
                ? value
                : null;
    }
}
```

Notes for the implementer: `CreateSessionLinkRequest.linkType` is a `String`, hence `LinkType.SPAWNED.value()` (serializes as `"spawned"`). The child session UUID is `recorded.session().id()` — the event was ingested under the composite child key, so the session on the event IS the child. `event.source()` is already lowercased `"claude"` by ingest. `LinkDomainException.code()` is the accessor (not `getCode()`).

- [ ] **Step 4: Run test to verify all five pass**

Run: `mvn -q test -Dtest=SubagentLinkListenerTest`
Expected: PASS (5/5).

- [ ] **Step 5: Keep the architecture ratchet green**

Run: `mvn -q test -Dtest='PackageArchitectureTest,ApplicationModuleStructureTest,WorkflowApplicationModuleTest'`
Expected: PASS. The listener lives in `workflow.internal.application`, imports only `recording` public types (workflow's `package-info.java` already declares `allowedDependencies = "recording"`), and depends on no adapter package, so `fullyMigratedModulesFollowInternalLayeringRules` and `moduleInternalsNeverLeakAcrossFeatureBoundaries` stay green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListener.java src/test/java/dev/nathan/sbaagentic/workflow/internal/application/SubagentLinkListenerTest.java
git commit -m "Link Subagent Sessions From Recorded Hook Events"
```

---

### Task 6: Child-counts GROUP BY query on the session-link store

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/port/SessionLinkStore.java` (add one method + `java.util.Map` import)
- Modify: `src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepository.java:1-16` (imports) and append one method after `linksForTaskId` (line 96)
- Test: `src/test/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepositoryTest.java`

**Interfaces:**
- Consumes: existing `session_links` table (`parent_session_id`, `child_session_id`, `link_type`, `task_id`, `created_at`), existing `JdbcTemplate` field, existing `requireText` helper untouched.
- Produces: `Map<String, Long> childCounts(List<String> parentSessionIds)` on both `SessionLinkStore` (port) and `SessionLinkRepository` (impl) — single GROUP BY, only parents with at least one link appear as keys; empty/null input returns `Map.of()`. The next task's `SessionLinkService` calls exactly this signature.

- [ ] **Step 1: Write the failing repository tests**

In `src/test/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepositoryTest.java`, add to the imports (top of file, with the other `java.util` imports — currently there are none, so insert before the `dev.nathan...` imports):

```java
import java.util.List;
import java.util.Map;
```

and add these tests after `linksForTaskIdReturnsOnlyMatchingLinks` (line 77):

```java
    @Test
    void childCountsGroupsLinksByRequestedParents() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED, null);
        repository.createLink("parent-a", "child-2", LinkType.CONTINUED, null);
        repository.createLink("parent-b", "child-3", LinkType.SPAWNED, "task-1");
        repository.createLink("parent-c", "child-4", LinkType.SPAWNED, null);

        assertThat(repository.childCounts(List.of("parent-a", "parent-b", "parent-missing")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("parent-a", 2L, "parent-b", 1L));
    }

    @Test
    void childCountsWithoutIdsReturnsEmptyMap() {
        repository.createLink("parent-a", "child-1", LinkType.SPAWNED, null);

        assertThat(repository.childCounts(List.of())).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SessionLinkRepositoryTest`
Expected: COMPILE ERROR — `cannot find symbol: method childCounts(java.util.List<java.lang.String>)` in `SessionLinkRepository`.

- [ ] **Step 3: Add the port method and the GROUP BY implementation**

In `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/port/SessionLinkStore.java`, add `import java.util.Map;` after `import java.util.List;` and add to the interface body:

```java
    Map<String, Long> childCounts(List<String> parentSessionIds);
```

In `src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepository.java`, extend the `java.*` import block (lines 3-7) to:

```java
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
```

and add this method after `linksForTaskId` (after line 96):

```java
    public Map<String, Long> childCounts(List<String> parentSessionIds) {
        if (parentSessionIds == null || parentSessionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(parentSessionIds.size(), "?"));
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT parent_session_id, COUNT(*) AS child_count
                  FROM session_links
                 WHERE parent_session_id IN (%s)
                 GROUP BY parent_session_id
                """.formatted(placeholders),
                rs -> {
                    counts.put(rs.getString("parent_session_id"), rs.getLong("child_count"));
                },
                parentSessionIds.toArray());
        return counts;
    }
```

(The lambda is Spring's `RowCallbackHandler`; `query(String, RowCallbackHandler, Object...)` is the non-deprecated overload in Spring 6.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SessionLinkRepositoryTest`
Expected: PASS (6/6 — the 4 pre-existing tests plus the 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/nathan/sbaagentic/workflow/internal/application/port/SessionLinkStore.java src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepository.java src/test/java/dev/nathan/sbaagentic/workflow/internal/adapter/out/sqlite/SessionLinkRepositoryTest.java
git commit -m "Add Child Link Counts Query To Session Link Store"
```

---

### Task 7: GET /api/session-links/child-counts endpoint + contract fixtures

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/workflow/SessionLineageOperations.java` (add one method + `java.util.Map` import)
- Modify: `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SessionLinkService.java:1-18` (imports) and append one override
- Modify: `src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/in/web/SessionLinkController.java` (one new mapping + imports)
- Modify: `src/test/resources/contracts/rest-mappings.txt` (one line, sorted position)
- Modify: `src/test/resources/contracts/rest-contract-matrix.json` (one row)
- Test: `src/test/java/dev/nathan/sbaagentic/web/SessionLinkApiContractTest.java`

**Interfaces:**
- Consumes: `SessionLinkStore.childCounts(List<String> parentSessionIds)` → `Map<String, Long>` from the previous task; existing `SessionLineageOperations` injection in `SessionLinkController`; `ApiExceptionHandler` already maps `MissingServletRequestParameterException` → 400 `{"error":{"type":"missing_parameter",...}}` — no handler changes needed.
- Produces: `GET /api/session-links/child-counts?ids=<id,id,...>` → 200 `{"<sessionId>": <count>}` (plain JSON object; parents with zero children are simply absent; `ids` is required, comma-separated, bound by Spring to `List<String>`); `SessionLineageOperations.childCounts(List<String> sessionIds)` → `Map<String, Long>` (blank/duplicate ids filtered, empty input → `{}`). The frontend browse-rail area calls this endpoint with the visible parent session UUIDs. No `wire-fixtures.json` change: the response is a `Map`, not a new record, and `WireContractFixtureTest` enumerates records/enums/SSE frames only.

- [ ] **Step 1: Write the failing contract tests**

In `src/test/java/dev/nathan/sbaagentic/web/SessionLinkApiContractTest.java`, add one static import alongside the existing MockMvc static imports (lines 17-20):

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

and add these tests after `taskIdRoundTripsThroughCreateAndRead` (line 129):

```java
    @Test
    void childCountsGroupsChildrenByParentForRequestedIds() throws Exception {
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-a", "child-1", "spawned", null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-a", "child-2", "spawned", null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/session-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkJson("parent-b", "child-3", "continued", null)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/session-links/child-counts")
                        .param("ids", "parent-a,parent-b,parent-none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['parent-a']").value(2))
                .andExpect(jsonPath("$['parent-b']").value(1))
                .andExpect(jsonPath("$['parent-none']").doesNotExist());
    }

    @Test
    void childCountsRequiresIdsAndReturnsEmptyObjectForBlankIds() throws Exception {
        mockMvc.perform(get("/api/session-links/child-counts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("missing_parameter"));

        mockMvc.perform(get("/api/session-links/child-counts").param("ids", ""))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }
```

(Reuses the pre-existing `linkJson(...)` helper and the `resetLinks()` `@BeforeEach` in this class.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SessionLinkApiContractTest`
Expected: the 2 new tests FAIL — unmapped path, so `Status expected:<200> but was:<404>` (and `<400>` vs `<404>` for the missing-param case). Pre-existing 5 tests still pass.

- [ ] **Step 3: Implement operations method, service, and controller mapping**

In `src/main/java/dev/nathan/sbaagentic/workflow/SessionLineageOperations.java`, add `import java.util.Map;` after `import java.util.List;` and add to the interface:

```java
    Map<String, Long> childCounts(List<String> sessionIds);
```

In `src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SessionLinkService.java`, add `import java.util.Map;` after `import java.util.List;` (line 3) and add this override after `linksForTask` (line 86):

```java
    @Override
    public Map<String, Long> childCounts(List<String> sessionIds) {
        if (sessionIds == null) {
            return Map.of();
        }
        List<String> ids = sessionIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.childCounts(ids);
    }
```

In `src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/in/web/SessionLinkController.java`, add imports:

```java
import java.util.List;
import java.util.Map;
```

(before the `dev.nathan...` imports) and

```java
import org.springframework.web.bind.annotation.RequestParam;
```

(with the other `org.springframework.web.bind.annotation` imports), then add after the `sessionLinks` GET mapping (line 33):

```java
    @GetMapping("/session-links/child-counts")
    public Map<String, Long> childCounts(@RequestParam("ids") List<String> ids) {
        return sessionLinks.childCounts(ids);
    }
```

(Controller keeps talking only to the `SessionLineageOperations` interface — the adapter-in ratchet forbids `@Repository` dependencies.)

- [ ] **Step 4: Run the contract test to verify it passes**

Run: `mvn -q test -Dtest=SessionLinkApiContractTest`
Expected: PASS (7/7).

- [ ] **Step 5: Extend the frozen REST contract fixtures**

In `src/test/resources/contracts/rest-mappings.txt`, insert this line between `GET /api/search/values` and `GET /api/sessions` (sorted position; the snapshot test sorts before comparing, but keep the file tidy):

```
GET /api/session-links/child-counts
```

In `src/test/resources/contracts/rest-contract-matrix.json`, add this row adjacent to the existing `POST /api/session-links` row (array order is irrelevant to the test; every field is mandatory and non-null):

```json
  {
    "method": "GET",
    "path": "/api/session-links/child-counts",
    "requiredInputs": [
      "ids"
    ],
    "optionalInputs": [],
    "defaults": {},
    "clamps": {},
    "successStatus": [
      200
    ],
    "errorStatus": [
      400
    ],
    "contentType": "application/json",
    "requestFields": [],
    "responseFields": [],
    "characterizationTest": "SessionLinkApiContractTest"
  },
```

- [ ] **Step 6: Run the contract snapshot suite to verify it passes**

Run: `mvn -q test -Dtest='RestContractSnapshotTest,WireContractFixtureTest,SessionLinkApiContractTest'`
Expected: PASS. `applicationMappingsMatchTheFrozenSnapshot` sees the new mapping in both the app and the txt file; `everyApiMappingHasACompleteContractMatrixRow` finds the new row; `WireContractFixtureTest` is untouched (no new record types).

- [ ] **Step 7: Run the full suite including module ratchets**

Run: `mvn -q test`
Expected: PASS, including `PackageArchitectureTest`, `ApplicationModuleStructureTest`, and all `*ApplicationModuleTest` ratchets. Do NOT run `mvn package` (a rebuilt jar degrades the live :8766 service; if packaging ever happens, restart via `launchctl kickstart -k` per repo memory).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/nathan/sbaagentic/workflow/SessionLineageOperations.java src/main/java/dev/nathan/sbaagentic/workflow/internal/application/SessionLinkService.java src/main/java/dev/nathan/sbaagentic/workflow/internal/adapter/in/web/SessionLinkController.java src/test/java/dev/nathan/sbaagentic/web/SessionLinkApiContractTest.java src/test/resources/contracts/rest-mappings.txt src/test/resources/contracts/rest-contract-matrix.json
git commit -m "Expose Session Link Child Counts Endpoint"
```

---

### Task 8: Derive Subagent Child Sessions In The Agent Hook

**Files:**
- Modify: `scripts/hooks/sba-agent-hook.sh:29-79` (payload extraction block, role case map, event-envelope `jq -n` build)
- Test: `scripts/test-agent-hook.sh` (append after line 115, the current last fixture)

**Interfaces:**
- Consumes: Claude Code hook stdin payloads — SubagentStart `{"session_id":"<parent>","hook_event_name":"SubagentStart","agent_type":"Explore","agent_id":"<uuid>"}`; SubagentStop same plus `"last_assistant_message":"<final text>"`. Existing `POST /api/events` envelope (unchanged field set: `source, clientSessionId, turnId, eventType, role, text, cwd, toolName, toolInput, toolOutput, metadata, observedAt`). Pre-existing helpers reused, no changes needed: the `TEXT` extraction at `sba-agent-hook.sh:31` already reads `.last_assistant_message`, and the `EVENT_KEY` normalization at line 39 already lowercases `SubagentStart` → `subagentstart`.
- Produces: for subagent events the hook posts `clientSessionId = "<parentClientSessionId>:<agentId>"` (child session key; `source=claude` when registered with the `claude` arg), `eventType` recorded **verbatim** as `SubagentStart` / `SubagentStop` (server-side final-event detection lowercases to `subagentstop`), `role=agent` on start, `role=assistant` on stop when `last_assistant_message` is non-whitespace (else `agent`), `text` = `last_assistant_message`, and `metadata = { rawHook: <full payload>, agentId: <string>, agentType: <string>, parentClientSessionId: <string> }`. The recording ingest stamps `agent_sessions.spawned_by` from `metadata.parentClientSessionId`, and `dev.nathan.sbaagentic.workflow.internal.application.SubagentLinkListener` reads `agentId`/`parentClientSessionId` to create the `LinkType.SPAWNED` link — the three metadata key names are load-bearing. Test helper `assert_subagent_event` (9 positional args) is produced for later fixture additions.

- [ ] **Step 1: Write the failing fixture cases**

Append to `scripts/test-agent-hook.sh` (after the final `"unmapped event"` fixture at line 115). This reuses the existing `assert_event` helper and the fake-`curl` capture already set up at the top of the file:

```bash
assert_subagent_event() {
  local label="$1"
  local payload="$2"
  local expected_role="$3"
  local expected_event_type="$4"
  local expected_client_session_id="$5"
  local expected_agent_id="$6"
  local expected_agent_type="$7"
  local expected_parent="$8"
  local expected_text="$9"

  assert_event "$label" "$payload" "$expected_role" "$expected_event_type"

  local actual_client_session_id
  local actual_agent_id
  local actual_agent_type
  local actual_parent
  local actual_text
  actual_client_session_id="$(jq -r '.clientSessionId' "$CAPTURE")"
  actual_agent_id="$(jq -r '.metadata.agentId // ""' "$CAPTURE")"
  actual_agent_type="$(jq -r '.metadata.agentType // ""' "$CAPTURE")"
  actual_parent="$(jq -r '.metadata.parentClientSessionId // ""' "$CAPTURE")"
  actual_text="$(jq -r '.text // ""' "$CAPTURE")"

  if [[ "$actual_client_session_id" != "$expected_client_session_id" ]]; then
    echo "$label: expected clientSessionId=$expected_client_session_id, got clientSessionId=$actual_client_session_id" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_agent_id" != "$expected_agent_id" ]]; then
    echo "$label: expected metadata.agentId=$expected_agent_id, got metadata.agentId=$actual_agent_id" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_agent_type" != "$expected_agent_type" ]]; then
    echo "$label: expected metadata.agentType=$expected_agent_type, got metadata.agentType=$actual_agent_type" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_parent" != "$expected_parent" ]]; then
    echo "$label: expected metadata.parentClientSessionId=$expected_parent, got metadata.parentClientSessionId=$actual_parent" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_text" != "$expected_text" ]]; then
    echo "$label: expected text=$expected_text, got text=$actual_text" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
}

assert_subagent_event \
  "subagent start derives child session" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStart","agent_type":"Explore","agent_id":"agent-uuid-1"}' \
  "agent" \
  "SubagentStart" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  ""
assert_subagent_event \
  "subagent stop with final text" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStop","agent_type":"Explore","agent_id":"agent-uuid-1","last_assistant_message":"Explored the repository tree"}' \
  "assistant" \
  "SubagentStop" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  "Explored the repository tree"
assert_subagent_event \
  "subagent stop without final text" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStop","agent_type":"Explore","agent_id":"agent-uuid-1"}' \
  "agent" \
  "SubagentStop" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  ""
assert_subagent_event \
  "snake-case subagent stop" \
  '{"session_id":"parent-abc","hook_event_name":"subagent_stop","agent_type":"general-purpose","agent_id":"agent-uuid-2","last_assistant_message":"Refactor complete"}' \
  "assistant" \
  "subagent_stop" \
  "parent-abc:agent-uuid-2" \
  "agent-uuid-2" \
  "general-purpose" \
  "parent-abc" \
  "Refactor complete"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `scripts/test-agent-hook.sh`

Expected: the 12 pre-existing fixtures pass, then FAIL with exit 1 and:

```
subagent start derives child session: expected clientSessionId=parent-abc:agent-uuid-1, got clientSessionId=parent-abc
```

(The role/eventType sub-checks pass even today — the derived key and metadata are what is missing.)

- [ ] **Step 3: Implement the hook changes (four surgical edits)**

Edit 1 — `scripts/hooks/sba-agent-hook.sh:34`, extend the extraction block. After:

```bash
TOOL_OUTPUT="$(jq -c '.tool_response // .toolResponse // .tool_output // .toolOutput // null' <<<"$PAYLOAD")"
```

add (matching the existing snake//camel//empty style):

```bash
AGENT_ID="$(jq -r '.agent_id // .agentId // empty' <<<"$PAYLOAD")"
AGENT_TYPE="$(jq -r '.agent_type // .agentType // empty' <<<"$PAYLOAD")"
```

Edit 2 — role map, lines 45-49. `subagentstop` joins the assistant-when-text alternation; `subagentstart` intentionally falls through to the default `ROLE="agent"` set at line 40. Replace:

```bash
  stop|assistantmessage)
    if [[ -n "${TEXT//[[:space:]]/}" ]]; then
      ROLE="assistant"
    fi
    ;;
```

with:

```bash
  stop|assistantmessage|subagentstop)
    if [[ -n "${TEXT//[[:space:]]/}" ]]; then
      ROLE="assistant"
    fi
    ;;
```

Edit 3 — insert the child-key derivation between `esac` (line 53) and the `jq -n` build (line 55):

```bash
# Subagent hooks fire in the PARENT session: the payload's session_id is the parent's id and
# agent_id is unique per spawn. Derive the child session key "<parent>:<agent_id>" and carry the
# lineage in metadata (SubagentStart keeps the default agent role above). Non-subagent events
# never enter this branch, so their output stays byte-identical.
SUBAGENT_METADATA="null"
if [[ "$EVENT_KEY" == "subagentstart" || "$EVENT_KEY" == "subagentstop" ]] && [[ -n "$AGENT_ID" ]]; then
  PARENT_SESSION_ID="$SESSION_ID"
  SESSION_ID="${PARENT_SESSION_ID}:${AGENT_ID}"
  SUBAGENT_METADATA="$(jq -n \
    --arg agentId "$AGENT_ID" \
    --arg agentType "$AGENT_TYPE" \
    --arg parentClientSessionId "$PARENT_SESSION_ID" \
    '{agentId: $agentId, agentType: $agentType, parentClientSessionId: $parentClientSessionId}')"
fi
```

Edit 4 — the envelope build. In the `jq -n` argument list, after `--argjson raw "$PAYLOAD" \` (line 66) add:

```bash
  --argjson subagent "$SUBAGENT_METADATA" \
```

and replace the metadata line (line 78):

```bash
    metadata: { rawHook: $raw },
```

with:

```bash
    metadata: ({ rawHook: $raw } + ($subagent // {})),
```

(`null // {}` merges nothing, so non-subagent events keep exactly `{ rawHook: ... }`.)

Never-fail contract untouched, verbatim: no `set -e` (line 8 stays `set -uo pipefail`), the `command -v jq || exit 0` guard at line 14, `curl -fsS --max-time 3 ... || true` at lines 81-85, and unconditional `exit 0` at line 87 are all unmodified. The new `jq -n` for `SUBAGENT_METADATA` cannot fail — it only receives `--arg` strings.

- [ ] **Step 4: Run the test to verify it passes**

Run: `scripts/test-agent-hook.sh`

Expected: PASS (exit 0) with all pre-existing lines unchanged plus:

```
subagent start derives child session: role=agent eventType=SubagentStart
subagent stop with final text: role=assistant eventType=SubagentStop
subagent stop without final text: role=agent eventType=SubagentStop
snake-case subagent stop: role=assistant eventType=subagent_stop
```

- [ ] **Step 5: Commit**

```bash
git add scripts/hooks/sba-agent-hook.sh scripts/test-agent-hook.sh
git commit -m "Derive Subagent Child Sessions In Agent Hook"
```

### Task 9: Lock The Hook's Non-Subagent And Never-Fail Contracts

**Files:**
- Test: `scripts/test-agent-hook.sh` (append at end of file, after the subagent fixtures)

**Interfaces:**
- Consumes: `assert_event` helper and `$CAPTURE`/`$HOOK` variables already defined in `scripts/test-agent-hook.sh`; the hook's safety contract (`curl --max-time 3 || true`, unconditional `exit 0`, RawText wrapping of non-JSON stdin at `sba-agent-hook.sh:18-22`).
- Produces: characterization guards other areas rely on — plain events keep their raw `clientSessionId` and a `metadata` object whose only key is `rawHook`; a `SubagentStart` missing `agent_id` is NOT rewritten; the hook exits 0 with the recorder unreachable and on garbage stdin. These are regression locks: they must pass immediately and forever.

- [ ] **Step 1: Write the regression guards**

Append to `scripts/test-agent-hook.sh`:

```bash
# Byte-identical contract for non-subagent events: raw session key, metadata is rawHook only.
assert_event \
  "plain stop keeps raw session key" \
  '{"hook_event_name":"Stop","session_id":"plain-session","last_assistant_message":"done"}' \
  "assistant" \
  "Stop"
if [[ "$(jq -r '.clientSessionId' "$CAPTURE")" != "plain-session" ]]; then
  echo "plain stop keeps raw session key: clientSessionId was rewritten" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi
if [[ "$(jq -r '.metadata | keys | join(",")' "$CAPTURE")" != "rawHook" ]]; then
  echo "plain stop keeps raw session key: metadata gained unexpected keys" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi

# A subagent event without agent_id must not derive a child key.
assert_event \
  "subagent start without agent id stays plain" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStart","agent_type":"Explore"}' \
  "agent" \
  "SubagentStart"
if [[ "$(jq -r '.clientSessionId' "$CAPTURE")" != "parent-abc" ]]; then
  echo "subagent start without agent id stays plain: clientSessionId was rewritten" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi

# Never-fail contract: real curl on PATH, recorder unreachable -> still exit 0.
set +e
printf '%s' '{"hook_event_name":"SubagentStop","session_id":"p","agent_id":"a","agent_type":"Explore","last_assistant_message":"done"}' |
  SBA_AGENTIC_URL="http://127.0.0.1:1" SBA_AGENT_SOURCE=fixture-source "$HOOK"
HOOK_STATUS=$?
set -e
if [[ "$HOOK_STATUS" -ne 0 ]]; then
  echo "never-fail with recorder down: expected exit 0, got $HOOK_STATUS" >&2
  exit 1
fi
echo "never-fail with recorder down: exit=0"

# Never-fail contract: non-JSON stdin is wrapped as RawText and still exits 0.
set +e
printf '%s' 'not json at all' |
  SBA_AGENTIC_URL="http://127.0.0.1:1" SBA_AGENT_SOURCE=fixture-source "$HOOK"
HOOK_STATUS=$?
set -e
if [[ "$HOOK_STATUS" -ne 0 ]]; then
  echo "never-fail with non-JSON stdin: expected exit 0, got $HOOK_STATUS" >&2
  exit 1
fi
echo "never-fail with non-JSON stdin: exit=0"
```

(The `set +e`/`set -e` bracket is required because the test script runs under `set -euo pipefail` while the hook's exit code is exactly what is under test. The never-fail blocks deliberately do NOT prepend `$FAKE_BIN` to `PATH`, so the real `curl` runs against the dead port and the `|| true` swallow path is exercised.)

- [ ] **Step 2: Run the test to verify it passes**

Run: `scripts/test-agent-hook.sh`

Expected: PASS (exit 0). These are characterization locks of behavior the hook already has after the previous task — a failure here means the subagent change broke the byte-identical or never-fail contract. Final lines:

```
plain stop keeps raw session key: role=assistant eventType=Stop
subagent start without agent id stays plain: role=agent eventType=SubagentStart
never-fail with recorder down: exit=0
never-fail with non-JSON stdin: exit=0
```

- [ ] **Step 3: Commit**

```bash
git add scripts/test-agent-hook.sh
git commit -m "Lock Agent Hook Byte-Identical And Never-Fail Contracts"
```

### Task 10: Document Subagent Hook Registration In The README

**Files:**
- Modify: `README.md:337-357` (the "Optional capture and recall hooks" section)

**Interfaces:**
- Consumes: the hook's `SOURCE="${SBA_AGENT_SOURCE:-${1:-unknown}}"` convention (`scripts/hooks/sba-agent-hook.sh:11`) — the registered command passes `claude` as `$1` so child sessions land with `source=claude`; the repo's existing doc placeholder convention `/ABSOLUTE/PATH/TO/...` (see `docs/local-writes-and-elasticsearch.md:19-20`).
- Produces: user-facing registration snippet for `~/.claude/settings.json` keys `SubagentStart`/`SubagentStop` with wildcard matcher, and a copy-pasteable subagent smoke command. No code interfaces.

- [ ] **Step 1: Extend the hook bullet and add the registration subsection**

In `README.md`, replace the first bullet of "Optional capture and recall hooks" (lines 341-343):

```markdown
- `scripts/hooks/sba-agent-hook.sh` normalizes supported Claude Code or Codex hook payloads and posts
  them to `/api/events`. Prompt, final-response, and tool hooks receive semantic `user`, `assistant`,
  and `tool` roles so Browse can reconstruct the recorded conversation.
```

with:

```markdown
- `scripts/hooks/sba-agent-hook.sh` normalizes supported Claude Code or Codex hook payloads and posts
  them to `/api/events`. Prompt, final-response, and tool hooks receive semantic `user`, `assistant`,
  and `tool` roles so Browse can reconstruct the recorded conversation. `SubagentStart` and
  `SubagentStop` payloads are recorded as child sessions keyed `<parent session_id>:<agent_id>`, with
  the lineage carried in event metadata (`agentId`, `agentType`, `parentClientSessionId`) so Browse
  can nest subagents under their parent.
```

Then insert a new subsection between the `AGENTS.md` paragraph (line 348) and "Hook smoke test:" (line 350):

````markdown
### Subagent lineage (Claude Code)

`SubagentStart`/`SubagentStop` hooks fire in the parent session; the bridge derives the child
session identity from `session_id` + `agent_id`. Registration is user-global (not in-repo): add
both events to `~/.claude/settings.json` with a wildcard matcher, alongside any existing hook
entries, passing `claude` as the source argument:

```json
{
  "hooks": {
    "SubagentStart": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/ABSOLUTE/PATH/TO/sba-agentic/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/ABSOLUTE/PATH/TO/sba-agentic/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ]
  }
}
```
````

Finally, extend the existing "Hook smoke test" bash block (lines 352-357) by appending one more example after the `UserPromptSubmit` printf:

```bash
printf '{"hook_event_name":"SubagentStart","session_id":"hook-test","agent_type":"Explore","agent_id":"hook-test-agent"}' |
  SBA_AGENT_SOURCE=manual scripts/hooks/sba-agent-hook.sh
```

- [ ] **Step 2: Verify the documented smoke command works without a live recorder**

Run (from the repo root; the dead port proves the never-fail path, no recorder needed):

```bash
printf '{"hook_event_name":"SubagentStart","session_id":"hook-test","agent_type":"Explore","agent_id":"hook-test-agent"}' |
  SBA_AGENT_SOURCE=manual SBA_AGENTIC_URL=http://127.0.0.1:1 scripts/hooks/sba-agent-hook.sh; echo "exit=$?"
```

Expected: `exit=0`

- [ ] **Step 3: Run the full hook fixture suite one more time**

Run: `scripts/test-agent-hook.sh`

Expected: PASS (exit 0) — the doc change touches no scripts, this is the pre-commit sanity gate.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "Document Subagent Hook Registration"
```

---

### Task 11: Add Subagent Lineage API Helpers

**Files:**
- Modify: `frontend/src/lib/api.ts:1-11` (AgentSession type), `frontend/src/lib/api.ts:478-480` (getSessions), insert new function after `getSessionLinks` at `frontend/src/lib/api.ts:601-603`
- Test: `frontend/src/lib/api.test.ts` (extend imports at :1-41, append new describe after :463)

**Interfaces:**
- Produces: `AgentSession.spawnedBy?: string | null`; `getSessions(limit = 250, includeChildren = false): Promise<AgentSession[]>` (param name exactly `includeChildren`, omitted from the query when false); `getSessionChildCounts(ids: string[]): Promise<Record<string, number>>` hitting `GET /api/session-links/child-counts?ids=<id,id,...>` (per-id `encodeURIComponent`, literal comma joins, no fetch for empty batch)
- Consumes: backend `GET /api/sessions?limit=&includeChildren=true` (server defaults to `WHERE spawned_by IS NULL`); backend `GET /api/session-links/child-counts?ids=<uuid,uuid,...>` returning `{"<sessionId>": <count>}`; existing private `getJson` helper in `api.ts`; existing `stubJson` test helper in `api.test.ts`

**Steps:**

- [ ] Write the failing tests. In `frontend/src/lib/api.test.ts`, add `getSessionChildCounts,` and `getSessions,` to the existing `from "./api"` import list (alphabetical slot, next to `getSessionDag`/`getSessionLinks`), then append this describe block at the end of the file (after the closing `});` of `describe("task API helpers", ...)` at line 463). `expectTypeOf` is a vitest global here (`globals: true` + `types: ["vitest/globals"]`), matching its existing bare use at line 354:

```ts
describe("subagent lineage API helpers", () => {
  it("carries the spawnedBy lineage hint on sessions", () => {
    expectTypeOf<AgentSession>().toMatchTypeOf<{ spawnedBy?: string | null }>();
  });

  it("requests parent sessions by default and passes includeChildren explicitly", async () => {
    const fetchMock = stubJson([] as AgentSession[]);

    await getSessions(2_000);
    await getSessions(50, true);

    const first = new URL(String(fetchMock.mock.calls[0]?.[0]), "http://blackbox.test");
    expect(first.pathname).toBe("/api/sessions");
    expect(first.searchParams.get("limit")).toBe("2000");
    expect(first.searchParams.has("includeChildren")).toBe(false);

    const second = new URL(String(fetchMock.mock.calls[1]?.[0]), "http://blackbox.test");
    expect(second.searchParams.get("limit")).toBe("50");
    expect(second.searchParams.get("includeChildren")).toBe("true");
  });

  it("batches child counts through the session-links child-counts endpoint", async () => {
    const fetchMock = stubJson<Record<string, number>>({ "session-1": 2 });

    await expect(getSessionChildCounts(["session-1", "session/2"])).resolves.toEqual({ "session-1": 2 });

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/session-links/child-counts?ids=session-1,session%2F2");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
  });

  it("resolves an empty child-count batch without touching the network", async () => {
    const fetchMock = stubJson<Record<string, number>>({});

    await expect(getSessionChildCounts([])).resolves.toEqual({});

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
```

- [ ] Run it and watch it fail: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/lib/api.test.ts` — expected failure: the whole file errors with `SyntaxError: The requested module ... does not provide an export named 'getSessionChildCounts'` (import of a not-yet-existing export).

- [ ] Minimal implementation in `frontend/src/lib/api.ts`. Replace the `AgentSession` type (lines 1-11) with:

```ts
export type AgentSession = {
  id: string;
  source: string;
  clientSessionId: string;
  title: string;
  cwd?: string | null;
  summary?: string | null;
  spawnedBy?: string | null;
  startedAt: string;
  lastSeenAt: string;
  eventCount: number;
};
```

Replace `getSessions` (lines 478-480) with:

```ts
export function getSessions(limit = 250, includeChildren = false): Promise<AgentSession[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (includeChildren) params.set("includeChildren", "true");
  return getJson(`/api/sessions?${params.toString()}`);
}
```

Insert directly after `getSessionLinks` (after line 603):

```ts
export function getSessionChildCounts(ids: string[]): Promise<Record<string, number>> {
  if (!ids.length) return Promise.resolve({});
  return getJson(`/api/session-links/child-counts?ids=${ids.map(encodeURIComponent).join(",")}`);
}
```

- [ ] Run again: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/lib/api.test.ts` — expected: all tests in the file pass, including the 4 new ones.

- [ ] Commit: `git add frontend/src/lib/api.ts frontend/src/lib/api.test.ts && git commit -m "Add Subagent Lineage API Helpers"`

### Task 12: Nest Subagent Children In The Session Rail

**Files:**
- Modify: `frontend/src/pages/SessionsPage.tsx:8-18` (api imports), `:113-119` (new resources/signals next to the existing `sessionDag` resource), `:141-156` (live-refresh effect), `:263-283` (rail markup), append `SessionChildRows` + `agentTypeLabel` helpers after the `SessionsPage` component closing brace at :381; `frontend/src/theme.css` (append nesting styles after the `.session-row-main` block at :952); `frontend/src/pages/__tests__/ActivityPage.test.tsx:39-49,74-88,102-160` (extend the partial api mock — ActivityPage embeds SessionsPage, so unmocked new calls would hit real fetch)
- Test: `frontend/src/pages/__tests__/SessionsPage.test.tsx`

**Interfaces:**
- Consumes: `getSessionChildCounts(ids)` from Task 11 (one batch call over the loaded rail ids); existing `getSessionLinks(sessionId): Promise<SessionLinksResponse>` — children arrive as `SessionLink[]` with `session: SessionLinkPeer { id, title, source }`; the badge derives from the CHILD SESSION TITLE: seeded from `agent_type` at the lowest auto rank, so it reads as the agent type while the child runs, and upgrades to the final-message/AI title after SubagentStop (the durable agent_type stays in the SubagentStart event metadata; promoting it to a first-class field is an explicit non-goal for v1); existing `SourceDot`, `selectSession` (navigates `/sessions/:childId` or defers to `props.onSelectSession`)
- Produces: rail DOM contract — `.session-row-block` wrapper, `.session-row-line` row, `.session-expander` button (`aria-expanded`, `aria-label` of the exact form `Toggle ${count} subagent sessions`), `.session-children` group, `.session-row--child` rows, `.agent-type-badge`; local-only expand state (`Set<string>` signal, no persistence); private `SessionChildRows(props: { parentId: string; onSelect: (id: string) => void })` and `agentTypeLabel(link: SessionLink): string`

**Steps:**

- [ ] Extend the SessionsPage test harness so existing tests keep passing once the page calls the new API functions. In `frontend/src/pages/__tests__/SessionsPage.test.tsx` replace lines 5-6 with:

```tsx
import { getProjectSessions, getSession, getSessionChildCounts, getSessionDag, getSessionEvents, getSessionLinks, getSessions, getTaskDag } from "../../lib/api";
import type { AgentEvent, AgentSession, DagResponse, SessionLinksResponse } from "../../lib/api";
```

Add a fixture after the `events` array (after line 98):

```tsx
const childLinks: SessionLinksResponse = {
  parents: [],
  children: [
    {
      linkId: "link-1",
      parentSessionId: "session-1",
      childSessionId: "child-1",
      linkType: "spawned",
      taskId: null,
      createdAt: "2026-06-22T20:05:00Z",
      session: { id: "child-1", title: "code-reviewer", source: "claude" },
    },
  ],
};
```

Inside the `vi.mock("../../lib/api", ...)` return object (after the `getSessionDag` line at 127) add:

```tsx
    getSessionLinks: vi.fn(async () => ({ parents: [], children: [] })),
    getSessionChildCounts: vi.fn(async () => ({})),
```

And in `beforeEach` (after the `getSessionDag` resets at 145-146) add:

```tsx
  vi.mocked(getSessionLinks).mockReset();
  vi.mocked(getSessionLinks).mockResolvedValue({ parents: [], children: [] });
  vi.mocked(getSessionChildCounts).mockReset();
  vi.mocked(getSessionChildCounts).mockResolvedValue({});
```

- [ ] Write the two failing tests, appended inside `describe("SessionsPage", ...)` before its closing brace (after the "reveals and highlights a target event" test at line 549):

```tsx
  it("renders an expander with the batch child count for parent sessions", async () => {
    vi.mocked(getSessionChildCounts).mockResolvedValue({ "session-1": 2 });

    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByRole("button", { name: "Toggle 2 subagent sessions" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(getSessionChildCounts).toHaveBeenCalledWith(["session-1", "session-2"]);
    expect(getSessions).toHaveBeenCalledWith(2_000);
    const cockpitRow = within(rail).getByText("Cockpit cleanup").closest(".session-row-block") as HTMLElement;
    expect(within(cockpitRow).queryByRole("button", { name: /subagent sessions/ })).not.toBeInTheDocument();
    expect(getSessionLinks).not.toHaveBeenCalled();
  });

  it("lazy-loads child rows with agent type badges on expand and collapses locally", async () => {
    vi.mocked(getSessionChildCounts).mockResolvedValue({ "session-1": 1 });
    vi.mocked(getSessionLinks).mockResolvedValue(childLinks);

    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    const expander = await within(rail).findByRole("button", { name: "Toggle 1 subagent sessions" });
    expect(getSessionLinks).not.toHaveBeenCalled();

    fireEvent.click(expander);
    await waitFor(() => expect(getSessionLinks).toHaveBeenCalledWith("session-1"));
    expect(await within(rail).findByText("code-reviewer", { selector: ".agent-type-badge" })).toBeInTheDocument();
    expect(expander).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(within(rail).getByText("code-reviewer", { selector: ".session-row--child strong" }));
    expect(navigate).toHaveBeenCalledWith("/sessions/child-1");

    fireEvent.click(expander);
    expect(within(rail).queryByText("code-reviewer", { selector: ".agent-type-badge" })).not.toBeInTheDocument();
  });
```

(Note the parent-only rail assertion rides here: the frontend must keep calling `getSessions(2_000)` with no `includeChildren` argument — the server filters children by default. The server clamps limit to 250 (EventController.safeLimit); 2_000 is the pre-existing client value, kept to avoid churn — the effective rail size is 250 parents. Raising the clamp is out of scope.)

- [ ] Run it and watch it fail: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/pages/__tests__/SessionsPage.test.tsx` — expected: the two new tests fail with `TestingLibraryElementError: Unable to find an accessible element with the role "button" and name "Toggle 2 subagent sessions"` (findByRole timeout); all pre-existing tests still pass.

- [ ] Implement in `frontend/src/pages/SessionsPage.tsx`. Replace the api import block (lines 8-18) with:

```tsx
import {
  getProjectSessions,
  getSession,
  getSessionChildCounts,
  getSessionDag,
  getSessionEvents,
  getSessionLinks,
  getSessions,
  getTaskDag,
  type AgentEvent,
  type AgentSession,
  type ProjectSummary,
  type SessionLink,
} from "../lib/api";
```

After the `sessionDag` resource (after line 116) add:

```tsx
  const railSessionIds = createMemo(() => sessions().map((session) => session.id));
  const [childCounts, { refetch: refetchChildCounts }] = createResource(
    () => railSessionIds().join(","),
    async () => getSessionChildCounts(railSessionIds()),
    { initialValue: {} as Record<string, number> },
  );
  const [expandedParents, setExpandedParents] = createSignal<ReadonlySet<string>>(new Set<string>());
  const isExpanded = (id: string) => expandedParents().has(id);
  const toggleExpanded = (id: string) => {
    setExpandedParents((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
```

(An empty rail joins to `""`, a falsy source, so the resource never fires; `getSessionChildCounts` also guards the empty batch.) In the live `session.updated` effect, extend the debounced block (lines 147-150) to also refresh counts:

```tsx
      refetchTimer = window.setTimeout(() => {
        void refetchEvents();
        void refetchChildCounts();
        if (searchParams.task) void refetchTaskContext();
      }, 180);
```

Replace the rail `<div class="session-rows">...</div>` block (lines 264-282) with:

```tsx
            <div class="session-rows">
              <For each={filteredSessions()}>
                {(session) => (
                  <div class="session-row-block">
                    <div class="session-row-line">
                      <button
                        type="button"
                        classList={{ "session-row": true, "session-row--active": session.id === selectedId() }}
                        onClick={() => selectSession(session.id)}
                      >
                        <SourceDot source={session.source} />
                        <span class="session-row-main">
                          <strong>{session.title || session.clientSessionId}</strong>
                          <small>
                            {session.eventCount.toLocaleString()} · {truncatePath(session.cwd)} · {timeAgo(session.lastSeenAt)}
                          </small>
                        </span>
                      </button>
                      <Show when={(childCounts()[session.id] ?? 0) > 0}>
                        <button
                          type="button"
                          class="session-expander"
                          aria-expanded={isExpanded(session.id)}
                          aria-label={`Toggle ${childCounts()[session.id]} subagent sessions`}
                          onClick={() => toggleExpanded(session.id)}
                        >
                          <span aria-hidden="true">{isExpanded(session.id) ? "−" : "+"}</span>
                          {childCounts()[session.id]}
                        </button>
                      </Show>
                    </div>
                    <Show when={isExpanded(session.id)}>
                      <SessionChildRows parentId={session.id} onSelect={selectSession} />
                    </Show>
                  </div>
                )}
              </For>
            </div>
```

Append after the `SessionsPage` component's closing brace (after line 381), before `filterSessions`:

```tsx
function SessionChildRows(props: { parentId: string; onSelect: (id: string) => void }) {
  const [links] = createResource(
    () => props.parentId,
    async (parentId) => (await getSessionLinks(parentId)).children,
    { initialValue: [] as SessionLink[] },
  );

  return (
    <div class="session-children" role="group" aria-label="Subagent sessions">
      <Show when={!links.loading} fallback={<p class="session-children-loading">Loading subagents…</p>}>
        <For each={links()}>
          {(link) => (
            <button type="button" class="session-row session-row--child" onClick={() => props.onSelect(link.session.id)}>
              <SourceDot source={link.session.source} />
              <span class="session-row-main">
                <strong>{link.session.title.trim() || link.session.id}</strong>
                <small>
                  <span class="agent-type-badge">{agentTypeLabel(link)}</span>
                </small>
              </span>
            </button>
          )}
        </For>
      </Show>
    </div>
  );
}

function agentTypeLabel(link: SessionLink): string {
  return link.session.title.trim() || "subagent";
}
```

(Mounting the resource inside the `Show` is the lazy-load: nothing fetches until first expand, and collapse state stays local to the page. `createResource`/`Show`/`For`/`createMemo`/`createSignal` are all already imported at line 2. No DagView/SVG work here — and never nest `<a>` inside SVG in any DagView-adjacent change.)

- [ ] Project-rail parity (verifier-flagged gap): `GET /api/projects/{key}/sessions` (ProjectRepository) has no `spawned_by` filter, and children inherit the parent's `cwd`, so the project-scoped rail would show every child as a flat top-level row. Filter client-side in `frontend/src/pages/SessionsPage.tsx` — in the `projectSessions` resource fetcher (line 67-70), change

```ts
      projectKey ? { projectKey, sessions: await getProjectSessions(projectKey, 2_000) } : null,
```

to

```ts
      projectKey
        ? {
            projectKey,
            sessions: (await getProjectSessions(projectKey, 2_000)).filter((s) => !s.spawnedBy),
          }
        : null,
```

(`spawnedBy` lands on the `AgentSession` type in the API-helpers task; a server-side project filter is an explicit non-goal for v1.)

- [ ] Add the rail nesting styles: append to `frontend/src/theme.css` after the `.session-row-main` rule at line 952:

```css
.session-row-block { display: flex; flex-direction: column; }
.session-row-line { display: flex; align-items: stretch; }
.session-row-line .session-row { flex: 1; min-width: 0; }
.session-expander { border: none; background: none; color: var(--text-dim); cursor: pointer; padding: 0 10px; font: inherit; font-size: 12px; display: inline-flex; align-items: center; gap: 4px; }
.session-expander:hover { color: var(--text-bright); background: var(--bg-hover); }
.session-children { display: flex; flex-direction: column; margin-left: 18px; border-left: 1px solid var(--border); }
.session-children-loading { color: var(--text-dim); font-size: 12px; padding: 6px 10px; margin: 0; }
.session-row--child { padding-top: 6px; padding-bottom: 6px; }
.agent-type-badge { display: inline-block; padding: 1px 6px; border: 1px solid var(--border); border-radius: 999px; font-size: 10px; text-transform: uppercase; letter-spacing: 0.04em; color: var(--text-dim); }
```

- [ ] Keep the sibling harness green: `frontend/src/pages/__tests__/ActivityPage.test.tsx` embeds SessionsPage but mocks `../../lib/api` selectively via a hoisted `apiMocks` object — the new eager `childCounts` resource would otherwise call the real `fetch`. Add to the `vi.hoisted` object (after `getSessionEvents: vi.fn(),` at line 43):

```tsx
  getSessionChildCounts: vi.fn(),
  getSessionLinks: vi.fn(),
  getSessionDag: vi.fn(),
```

Add to the `vi.mock("../../lib/api", ...)` return object (after line 81):

```tsx
    getSessionChildCounts: apiMocks.getSessionChildCounts,
    getSessionLinks: apiMocks.getSessionLinks,
    getSessionDag: apiMocks.getSessionDag,
```

Add to `beforeEach` (after the `getSessionEvents` reset at line 116):

```tsx
  apiMocks.getSessionChildCounts.mockReset();
  apiMocks.getSessionChildCounts.mockResolvedValue({});
  apiMocks.getSessionLinks.mockReset();
  apiMocks.getSessionLinks.mockResolvedValue({ parents: [], children: [] });
  apiMocks.getSessionDag.mockReset();
  apiMocks.getSessionDag.mockResolvedValue({ nodes: [], edges: [] });
```

(The `getSessionDag` entry is inert now and required by the next task's un-gated lineage fetch.)

- [ ] Run again: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/pages/__tests__/SessionsPage.test.tsx src/pages/__tests__/ActivityPage.test.tsx` — expected: all pass, including the two new rail tests.

- [ ] Commit: `git add frontend/src/pages/SessionsPage.tsx frontend/src/pages/__tests__/SessionsPage.test.tsx frontend/src/pages/__tests__/ActivityPage.test.tsx frontend/src/theme.css && git commit -m "Nest Subagent Children In The Session Rail"`

### Task 13: Surface The Session Lineage DAG Without The Task Gate

**Files:**
- Modify: `frontend/src/pages/SessionsPage.tsx:113-119` (add lineage resource next to the existing `sessionDag` resource), `:141-156` (live effect), `:297-333` (insert lineage section between `</header>` of `.detail-header` and `<div class="detail-body">`); `frontend/src/theme.css` (one rule after the Task 12 additions)
- Test: `frontend/src/pages/__tests__/SessionsPage.test.tsx:154-160` (amend the first gating test), new tests appended in the same describe

**Interfaces:**
- Consumes: existing `getSessionDag(sessionId): Promise<DagResponse>` (`GET /api/dag?sessionId=`); existing `DagView` component (`dag`, `currentSessionId`, `currentTaskId` props) — untouched, including its no-`<a>`-inside-SVG rule (navigation stays on the `<g>`); the existing `?task=` tendril header block (lines 179-233) stays byte-identical
- Produces: `.session-lineage` section in the detail pane, rendered for any selected session whose `getSessionDag` response has more than 1 node and only when no `?task=` param is present; lineage resource refetches on `session.updated` for the selected session

**Steps:**

- [ ] Amend the now-stale gating assertion and write the failing tests in `frontend/src/pages/__tests__/SessionsPage.test.tsx`. In the first test ("does not render tendril context without a task query parameter", lines 154-160) delete the line `expect(getSessionDag).not.toHaveBeenCalled();` (the lineage fetch is about to run eagerly outside the tendril gate; keep the `.tendril-header` and `getTaskDag` assertions). Then append inside the describe:

```tsx
  it("shows the lineage DAG for a session whose DAG has more than one node", async () => {
    vi.mocked(getSessionDag).mockResolvedValue({
      nodes: [
        { id: "session-1", type: "session", label: "Focused session", ref: "/sessions/session-1" },
        { id: "child-1", type: "session", label: "code-reviewer", ref: "/sessions/child-1" },
      ],
      edges: [{ from: "session-1", to: "child-1", type: "spawned" }],
    });

    render(() => <SessionsPage />);

    expect(await screen.findByText("code-reviewer", { selector: ".dag-label" })).toBeInTheDocument();
    const lineage = document.querySelector(".session-lineage") as HTMLElement;
    expect(lineage).toBeInTheDocument();
    expect(lineage.querySelector('[data-node-id="session-1"]')).toHaveClass("dag-node--current");
    expect(getSessionDag).toHaveBeenCalledWith("session-1");
    expect(document.querySelector(".tendril-header")).not.toBeInTheDocument();
  });

  it("keeps the lineage DAG hidden when the session DAG has a single node", async () => {
    vi.mocked(getSessionDag).mockResolvedValue({
      nodes: [{ id: "session-1", type: "session", label: "Focused session", ref: "/sessions/session-1" }],
      edges: [],
    });

    render(() => <SessionsPage />);

    await waitFor(() => expect(getSessionDag).toHaveBeenCalledWith("session-1"));
    expect(document.querySelector(".session-lineage")).not.toBeInTheDocument();
  });
```

(The existing "lazily fetches and renders the selected session DAG from the tendril header" test at line 189 stays untouched and now also proves the `?task=` path suppresses the eager lineage fetch: it asserts `getSessionDag` is not called before the toggle click.)

- [ ] Run it and watch it fail: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/pages/__tests__/SessionsPage.test.tsx` — expected: "shows the lineage DAG..." fails with `Unable to find an element with the text: code-reviewer` (findByText timeout) and "keeps the lineage DAG hidden..." fails at `waitFor(...getSessionDag...)` timeout because nothing calls `getSessionDag` without a task param yet.

- [ ] Minimal implementation in `frontend/src/pages/SessionsPage.tsx`. Directly after the existing `sessionDag` resource (lines 113-116) add:

```tsx
  const [lineageDag, { refetch: refetchLineageDag }] = createResource(
    () => (!searchParams.task && selectedId() ? selectedId() : undefined),
    (sessionId) => getSessionDag(sessionId),
  );
  const lineageDagData = createMemo(() => {
    if (searchParams.task || lineageDag.error) return null;
    const dag = lineageDag();
    return dag && dag.nodes.length > 1 ? dag : null;
  });
```

In the live effect's debounced block (extended in the previous task) add one line so lineage refreshes when new children ride `session.updated`:

```tsx
        if (!searchParams.task) void refetchLineageDag();
```

In the selected-session detail JSX, insert between the `.detail-header` closing `</header>` (line 331) and `<div class="detail-body">` (line 333):

```tsx
                <Show when={lineageDagData()}>
                  {(dag) => (
                    <section class="session-lineage">
                      <span class="eyebrow">lineage</span>
                      <DagView dag={dag()} currentSessionId={selectedId()} />
                    </section>
                  )}
                </Show>
```

(`DagView` is already imported at line 4; the non-keyed `Show` callback receives an accessor, matching the `{(taskId) => ...}` pattern already used at line 180. `DagView` itself is not modified — its `<g>`-based navigation and the no-`<a>`-inside-SVG rule stand, and `frontend/src/components/DagView.test.tsx` already covers the rendered graph.)

- [ ] Add the panel style: append to `frontend/src/theme.css` after the Task 12 rail rules:

```css
.session-lineage { display: flex; flex-direction: column; gap: 6px; padding: 12px 20px; border-bottom: 1px solid var(--border); }
```

- [ ] Run again: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run src/pages/__tests__/SessionsPage.test.tsx src/components/DagView.test.tsx src/pages/__tests__/ActivityPage.test.tsx` — expected: all pass (ActivityPage's `getSessionDag` mock from the previous task absorbs the new eager fetch).

- [ ] Commit: `git add frontend/src/pages/SessionsPage.tsx frontend/src/pages/__tests__/SessionsPage.test.tsx frontend/src/theme.css && git commit -m "Surface Session Lineage DAG Beyond The Task Gate"`

### Task 14: Verify The Suite And Rebuild The Committed Static Bundle

**Files:**
- Modify: `src/main/resources/static/**` (generated by `vite build`; `outDir: "../src/main/resources/static"` with `emptyOutDir: true` per `frontend/vite.config.ts:12-15` — never hand-edit, only regenerate)
- Test: full frontend suite

**Interfaces:**
- Consumes: `frontend/package.json` scripts — `"test": "vitest run"`, `"build": "tsc --noEmit && vite build"`; existing `frontend/node_modules` (run `npm ci` in `frontend/` first only if the install is missing or stale)
- Produces: refreshed committed static bundle (`src/main/resources/static/index.html` + re-hashed `assets/index-*.js` / `assets/index-*.css`) that the Spring app serves

**Steps:**

- [ ] Run the full frontend suite: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npx vitest run` — expected: every test file passes (`Test Files N passed`, 0 failed), including the extended `api.test.ts`, `SessionsPage.test.tsx`, `ActivityPage.test.tsx`, and the untouched `DagView.test.tsx`.

- [ ] Rebuild the committed bundle: `cd /Users/nathan/Developer/proj/sba-agentic/frontend && npm run build` — expected: `tsc --noEmit` silent, then `vite build` reports the emitted `../src/main/resources/static/assets/index-*.js` and `index-*.css` and exits 0. If it fails on missing dev deps, run `npm ci` in `frontend/` and retry.

- [ ] Inspect exactly what changed: `cd /Users/nathan/Developer/proj/sba-agentic && git status --short src/main/resources/static` — expected: modified `index.html` plus deleted old-hash / added new-hash files under `assets/` (from `emptyOutDir`), and nothing outside that directory.

- [ ] Commit the regenerated bundle: `git add -A src/main/resources/static && git commit -m "Rebuild Static Frontend For Subagent Lineage"`

- [ ] Note for the operator (no action unless directed): the live `:8766` launchd service serves the packaged jar, so a static-only rebuild does not change the running UI. If a local deploy is wanted, run `scripts/deploy-local.sh` — and remember any `mvn package` overwrites the served jar, so restart with `launchctl kickstart -k` afterward.
