package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-project-trajectory-query-plan-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false",
        "sba.memory.embedding.enabled=false"
})
class ProjectTrajectoryQueryPlanTest {

    private static final String OLD_MILESTONE_PREDICATE = """
            (
              lower(coalesce(e.event_type, '')) IN ('decision', 'handoff', 'observation', 'projection')
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"observation"%'
              OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"projection"%'
            )
            """;

    private static final String ROOT = "/tmp/black-box-trajectory-plan-root";
    private static final String ALIAS = "/tmp/black-box-trajectory-plan-alias";
    private static final String OTHER = "/tmp/black-box-trajectory-plan-other";
    private static final String ROOT_SESSION = "trajectory-plan-root";
    private static final String ALIAS_SESSION = "trajectory-plan-alias";
    private static final String OTHER_SESSION = "trajectory-plan-other";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectRepository repository;

    @BeforeEach
    void seedTrajectoryProjects() {
        jdbcTemplate.update("DELETE FROM session_meld_inputs");
        jdbcTemplate.update("DELETE FROM session_melds");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        jdbcTemplate.update("DELETE FROM project_aliases");

        insertSession(ROOT_SESSION, "Root trajectory session", ROOT, 8);
        insertSession(ALIAS_SESSION, "Alias trajectory session", ALIAS, 1);
        insertSession(OTHER_SESSION, "Other trajectory session", OTHER, 1);
        jdbcTemplate.update("""
                INSERT INTO project_aliases (id, alias_key, canonical_key, source, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, "trajectory-plan-alias", ALIAS, ROOT, "manual", "2026-08-05T09:00:00Z");

        insertEvent("typed-decision", ROOT_SESSION, "dEcIsIoN", null, null,
                "2026-08-05T10:00:00Z");
        insertEvent("typed-observation", ROOT_SESSION, "ObSeRvAtIoN", null,
                "{\"payload\":\"typed event\"}", "2026-08-05T10:01:00Z");
        insertEvent("metadata-decision", ROOT_SESSION, "PostToolUse", "sqlite3",
                "{\"Kind\":\"Decision\"}", "2026-08-05T10:02:00Z");
        insertEvent("metadata-handoff", ROOT_SESSION, "AssistantMessage", null,
                "{\"kind\":\"HANDOFF\"}", "2026-08-05T10:03:00Z");
        insertEvent("metadata-observation", ROOT_SESSION, "ToolResult", null,
                "{\"KIND\":\"obSERvation\"}", "2026-08-05T10:05:00Z");
        insertEvent("metadata-projection", ROOT_SESSION, "UserPromptSubmit", null,
                "{\"kind\":\"Projection\"}", "2026-08-05T10:05:00Z");
        insertEvent("large-tool-noise", ROOT_SESSION, "PostToolUse", "shell",
                "{\"payload\":\"" + "x".repeat(256 * 1024) + "\"}", "2026-08-05T10:06:00Z");
        insertEvent("null-metadata-noise", ROOT_SESSION, "UserPromptSubmit", null, null,
                "2026-08-05T10:07:00Z");

        insertEvent("alias-typed-handoff", ALIAS_SESSION, "hAnDoFf", null, null,
                "2026-08-05T10:04:00Z");
        insertEvent("other-typed-projection", OTHER_SESSION, "pRoJeCtIoN", null, null,
                "2026-08-05T10:08:00Z");

        jdbcTemplate.update("""
                INSERT INTO session_melds
                       (id, project_key, title, body, provider, model, prompt_version,
                        execution_mode, saved_from_preview, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "root-saved-meld", ROOT, "Trajectory synthesis", "Saved trajectory synthesis",
                "local", "context-bundle", "project-meld-v1", "export_bundle", 1, null,
                "2026-08-05T10:05:30Z");
    }

    @Test
    void guardedMilestonePredicateSelectsExactlyTheOldPredicateRows() {
        List<String> oldIds = matchingIds(OLD_MILESTONE_PREDICATE);
        List<String> guardedIds = matchingIds(ProjectRepository.MILESTONE_PREDICATE);

        assertThat(guardedIds).containsExactlyElementsOf(oldIds);
        assertThat(guardedIds).containsExactly(
                "alias-typed-handoff",
                "metadata-decision",
                "metadata-handoff",
                "metadata-observation",
                "metadata-projection",
                "other-typed-projection",
                "typed-decision",
                "typed-observation");
        assertThat(ProjectRepository.MILESTONE_PREDICATE).containsSubsequence(
                "WHEN lower(coalesce(e.event_type, '')) IN",
                "WHEN e.metadata_json LIKE '%\"kind\":\"%' THEN (",
                "lower(coalesce(e.metadata_json, '')) LIKE '%\"kind\":\"decision\"%'");
    }

    @Test
    void trajectoryQueriesKeepCountsOrderingJoinsAliasScopesAndSessionIndexPlan() {
        assertThat(repository.totalCaptures(ROOT)).isEqualTo(8);

        List<RecentCapture> recent = jdbcTemplate.query(
                ProjectRepository.recentEventCapturesSql(2),
                (rs, rowNum) -> new RecentCapture(rs.getString("id"), rs.getString("session_title")),
                ROOT, ALIAS, 3);
        assertThat(recent).containsExactly(
                new RecentCapture("metadata-projection", "Root trajectory session"),
                new RecentCapture("metadata-observation", "Root trajectory session"),
                new RecentCapture("alias-typed-handoff", "Alias trajectory session"));

        assertUsesSessionEventIndex(explain(
                ProjectRepository.totalCapturesSql(2), ROOT, ALIAS, ROOT, ALIAS));
        assertUsesSessionEventIndex(explain(
                ProjectRepository.recentEventCapturesSql(2), ROOT, ALIAS, 3));
    }

    private void insertSession(String sessionId, String title, String cwd, int eventCount) {
        jdbcTemplate.update("""
                INSERT INTO agent_sessions
                       (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, "codex", sessionId, title, cwd,
                "2026-08-05T10:00:00Z", "2026-08-05T10:10:00Z", eventCount);
    }

    private void insertEvent(
            String eventId,
            String sessionId,
            String eventType,
            String toolName,
            String metadataJson,
            String observedAt) {
        jdbcTemplate.update("""
                INSERT INTO agent_events
                       (id, session_id, source, client_session_id, event_type, role, text,
                        tool_name, metadata_json, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, sessionId, "codex", sessionId, eventType, "assistant", eventId,
                toolName, metadataJson, observedAt);
    }

    private List<String> matchingIds(String predicate) {
        return jdbcTemplate.query(
                "SELECT e.id FROM agent_events e WHERE " + predicate + " ORDER BY e.id",
                (rs, rowNum) -> rs.getString("id"));
    }

    private List<String> explain(String sql, Object... args) {
        return jdbcTemplate.query("EXPLAIN QUERY PLAN " + sql,
                (rs, rowNum) -> rs.getString("detail"), args);
    }

    private static void assertUsesSessionEventIndex(List<String> plan) {
        assertThat(plan)
                .anyMatch(detail -> detail.contains(
                        "SEARCH e USING INDEX idx_agent_events_session_observed"))
                .noneMatch(detail -> detail.startsWith("SCAN e"));
    }

    private record RecentCapture(String id, String sessionTitle) {
    }
}
