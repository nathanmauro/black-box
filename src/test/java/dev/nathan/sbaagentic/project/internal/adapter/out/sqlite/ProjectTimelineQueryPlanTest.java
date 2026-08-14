package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

import java.util.List;
import java.util.stream.Stream;

import dev.nathan.sbaagentic.project.ProjectTimelineBlock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-project-timeline-query-plan-test-${random.uuid}.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false",
        "sba.memory.embedding.enabled=false"
})
class ProjectTimelineQueryPlanTest {

    private static final String ALPHA = "/tmp/black-box-timeline-plan-alpha";
    private static final String BETA = "/tmp/black-box-timeline-plan-beta";
    private static final String ALPHA_SESSION_ONE = "timeline-plan-alpha-one";
    private static final String ALPHA_SESSION_TWO = "timeline-plan-alpha-two";
    private static final String BETA_SESSION = "timeline-plan-beta-one";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectRepository repository;

    @BeforeEach
    void seedTimelineProjects() {
        jdbcTemplate.update("DELETE FROM session_meld_inputs");
        jdbcTemplate.update("DELETE FROM session_melds");
        jdbcTemplate.update("DELETE FROM agent_events");
        jdbcTemplate.update("DELETE FROM agent_sessions");
        jdbcTemplate.update("DELETE FROM project_aliases");

        insertSession(ALPHA_SESSION_ONE, ALPHA, 3);
        insertSession(ALPHA_SESSION_TWO, ALPHA, 2);
        insertSession(BETA_SESSION, BETA, 2);

        insertEvent("alpha-decision", ALPHA_SESSION_ONE, "Decision", "assistant",
                "Choose the session-index timeline path", null, "2026-08-05T10:00:00Z");
        insertEvent("alpha-assistant", ALPHA_SESSION_ONE, "AssistantMessage", "assistant",
                "Implemented the indexed timeline query", null, "2026-08-05T10:01:00Z");
        insertEvent("alpha-noise", ALPHA_SESSION_ONE, "UserPromptSubmit", "user",
                "This prompt is not storyline evidence", null, "2026-08-05T10:01:30Z");
        insertEvent("alpha-tool", ALPHA_SESSION_TWO, "PostToolUse", "assistant",
                "Verified the query plan", "sqlite3", "2026-08-05T10:03:00Z");
        insertEvent("alpha-handoff", ALPHA_SESSION_TWO, "Handoff", "assistant",
                "Timeline fast path is ready", null, "2026-08-05T10:04:00Z");

        insertEvent("beta-decision", BETA_SESSION, "Decision", "assistant",
                "Keep beta isolated", null, "2026-08-05T10:00:30Z");
        insertEvent("beta-assistant", BETA_SESSION, "AssistantMessage", "assistant",
                "Beta remains separate", null, "2026-08-05T10:02:30Z");

        jdbcTemplate.update("""
                INSERT INTO session_melds
                       (id, project_key, title, body, provider, model, prompt_version,
                        execution_mode, saved_from_preview, metadata_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "alpha-meld", ALPHA, "Alpha synthesis", "Saved alpha-only synthesis", "local",
                "context-bundle", "project-meld-v1", "export_bundle", 1, null,
                "2026-08-05T10:02:00Z");
        jdbcTemplate.update("""
                INSERT INTO session_meld_inputs
                       (meld_id, session_id, input_order, included_summary, metadata_json)
                VALUES (?, ?, ?, ?, ?)
                """, "alpha-meld", ALPHA_SESSION_ONE, 0, 0, null);
    }

    @Test
    void timelineQueriesStayScopedPageStablyAndProbeTheSessionEventIndex() {
        List<ProjectTimelineBlock> fullTimeline = repository.timelineBlocks(ALPHA, 20, 0);
        assertThat(repository.countTimelineBlocks(ALPHA)).isEqualTo(5);
        assertThat(ids(fullTimeline)).containsExactly(
                "alpha-decision",
                "alpha-assistant",
                "alpha-meld",
                "alpha-tool",
                "alpha-handoff");
        assertThat(fullTimeline).extracting(ProjectTimelineBlock::id)
                .doesNotContain("alpha-noise", "beta-decision", "beta-assistant");
        assertThat(fullTimeline.getFirst().sessionTitle()).isEqualTo(ALPHA_SESSION_ONE);
        assertThat(fullTimeline.getFirst().cwd()).isEqualTo(ALPHA);
        assertThat(fullTimeline.get(2).sourceType()).isEqualTo("saved_meld");
        assertThat(fullTimeline.get(2).sourceSessions())
                .extracting(sourceSession -> sourceSession.id())
                .containsExactly(ALPHA_SESSION_ONE);

        List<String> pagedIds = Stream.of(
                        repository.timelineBlocks(ALPHA, 2, 0),
                        repository.timelineBlocks(ALPHA, 2, 2),
                        repository.timelineBlocks(ALPHA, 2, 4))
                .flatMap(List::stream)
                .map(ProjectTimelineBlock::id)
                .toList();
        assertThat(pagedIds).containsExactlyElementsOf(ids(fullTimeline)).doesNotHaveDuplicates();

        assertThat(repository.countTimelineBlocks(BETA)).isEqualTo(2);
        assertThat(ids(repository.timelineBlocks(BETA, 20, 0)))
                .containsExactly("beta-decision", "beta-assistant");
        assertThat(ids(repository.timelineBlocksForSession(ALPHA, ALPHA_SESSION_ONE, 20)))
                .containsExactly("alpha-decision", "alpha-assistant");
        assertThat(repository.timelineBlocksForSession(ALPHA, BETA_SESSION, 20)).isEmpty();
        assertThat(repository.timelineBlocksForSession(BETA, ALPHA_SESSION_ONE, 20)).isEmpty();

        assertUsesSessionEventIndex(explain(
                ProjectRepository.countTimelineBlocksSql(1), ALPHA, ALPHA));
        assertUsesSessionEventIndex(explain(
                ProjectRepository.timelineBlocksSql(1), ALPHA, ALPHA, 20, 0));
    }

    private void insertSession(String sessionId, String cwd, int eventCount) {
        jdbcTemplate.update("""
                INSERT INTO agent_sessions
                       (id, source, client_session_id, title, cwd, started_at, last_seen_at, event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, "codex", sessionId, sessionId, cwd,
                "2026-08-05T10:00:00Z", "2026-08-05T10:05:00Z", eventCount);
    }

    private void insertEvent(
            String eventId,
            String sessionId,
            String eventType,
            String role,
            String text,
            String toolName,
            String observedAt) {
        jdbcTemplate.update("""
                INSERT INTO agent_events
                       (id, session_id, source, client_session_id, event_type, role, text,
                        tool_name, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, sessionId, "codex", sessionId, eventType, role, text, toolName, observedAt);
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

    private static List<String> ids(List<ProjectTimelineBlock> blocks) {
        return blocks.stream().map(ProjectTimelineBlock::id).toList();
    }
}
