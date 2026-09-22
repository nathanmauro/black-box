package dev.nathan.sbaagentic.judgment.internal.application;

import java.time.Instant;
import java.util.Map;

import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-judgment-disabled-test-${random.uuid}.db",
        "sba.judge.enabled=false",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.memory.embedding.enabled=false"
})
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class JudgmentDisabledIntegrationTest {

    @Autowired
    EventRecorder recorder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectProvider<JudgmentListener> listener;

    @Autowired
    JudgmentHealthService health;

    @Test
    void disabledByDefaultDoesNotCreateListenerOrRows() {
        recorder.ingest(new EventIngestRequest(
                "codex",
                "disabled-judge",
                "turn-1",
                "Decision",
                "assistant",
                "No judgment should run.",
                "/repo",
                null,
                null,
                null,
                Map.of("title", "disabled"),
                Instant.parse("2026-09-21T12:00:00Z")));

        assertThat(listener.getIfAvailable()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_judgments", Integer.class)).isZero();
        assertThat(health.health().enabled()).isFalse();
        assertThat(health.health().calls()).isZero();
    }
}
