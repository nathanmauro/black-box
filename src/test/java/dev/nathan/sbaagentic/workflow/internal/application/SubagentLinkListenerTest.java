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
