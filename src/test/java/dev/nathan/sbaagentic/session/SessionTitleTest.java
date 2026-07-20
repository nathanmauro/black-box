package dev.nathan.sbaagentic.recording;

import java.time.Instant;
import java.util.Map;

import dev.nathan.sbaagentic.ai.SessionSummaryService;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite.RecordingSqlStore;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:session-title-test?mode=memory&cache=shared",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false"
})
class SessionTitleTest {

    @Autowired
    EventRecorder ingestService;

    @Autowired
    RecordingSqlStore repository;

    @Autowired
    SessionSummaryService summaryService;

    @Test
    void higherQualityEventUpgradesTitleWhileLowerQualityDoesNot() {
        // First event is a bare tool call: the only title available is "<tool> via <type>".
        ingestService.ingest(event("upgrade-session", "PostToolUse", null, "Read",
                Instant.parse("2026-05-21T12:00:00Z")));
        assertThat(titleOf("upgrade-session")).isEqualTo("Read via PostToolUse");

        // A real prompt outranks the tool title and renames the session.
        ingestService.ingest(event("upgrade-session", "UserPromptSubmit", "Investigate the failing ingest test", null,
                Instant.parse("2026-05-21T12:01:00Z")));
        assertThat(titleOf("upgrade-session")).isEqualTo("Investigate the failing ingest test");

        // A later, lower-quality tool event must NOT clobber the prompt-derived title.
        ingestService.ingest(event("upgrade-session", "PostToolUse", null, "Edit",
                Instant.parse("2026-05-21T12:02:00Z")));
        assertThat(titleOf("upgrade-session")).isEqualTo("Investigate the failing ingest test");
    }

    @Test
    void summarizeRenamesSessionAndLocksTheTitle() {
        ingestService.ingest(event("summary-session", "UserPromptSubmit", "Draft the handoff doc", null,
                Instant.parse("2026-05-21T12:00:00Z")));
        String beforeTitle = titleOf("summary-session");

        AgentSession summarized = summaryService.summarize(sessionId("summary-session"));
        assertThat(summarized.summary()).isNotBlank();
        assertThat(summarized.title()).isNotBlank();
        assertThat(summarized.title()).isNotEqualTo(beforeTitle);

        // The AI title locks above every ingest rank: a later tool event leaves it untouched.
        ingestService.ingest(event("summary-session", "PostToolUse", null, "Bash",
                Instant.parse("2026-05-21T12:05:00Z")));
        assertThat(titleOf("summary-session")).isEqualTo(summarized.title());
    }

    private EventIngestRequest event(String session, String type, String text, String toolName, Instant at) {
        return new EventIngestRequest(
                "claude", session, null, type, "agent", text, "/tmp/project", toolName, null, null, Map.of(), at);
    }

    private String titleOf(String clientSessionId) {
        return repository.findSession("claude", clientSessionId).orElseThrow().title();
    }

    private String sessionId(String clientSessionId) {
        return repository.findSession("claude", clientSessionId).orElseThrow().id();
    }
}
