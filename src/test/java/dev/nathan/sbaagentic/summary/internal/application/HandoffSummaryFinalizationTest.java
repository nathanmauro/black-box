package dev.nathan.sbaagentic.summary.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.CaptureHandoffRequest;
import dev.nathan.sbaagentic.recording.EventIngestRequest;
import dev.nathan.sbaagentic.recording.EventRecorder;
import dev.nathan.sbaagentic.recording.RecordingCaptureOperations;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.recording.SessionSummaryRecorded;
import dev.nathan.sbaagentic.recording.SummarySource;
import dev.nathan.sbaagentic.summary.SummaryOperations;
import dev.nathan.sbaagentic.summary.internal.application.port.ExternalSummaryModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;

/**
 * Finalization through the real capture and ingest paths, against a real database. Only the model
 * is faked, so "no Codex call" is a count of real calls that would have left the process.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-handoff-summary-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false",
            "sba.summary.backend=external"
        })
@Import(HandoffSummaryFinalizationTest.Fakes.class)
class HandoffSummaryFinalizationTest {

    private static final String DRAIN_MARKER = "drain-the-finalizer-queue";
    private static final String CONTEXT =
            "Shipped the nightly backup as bcaa7822 on main and pushed it. launchd runs it at 03:00 and the"
                    + " Drive folder holds the first snapshot. Verified a restore into a scratch database, which"
                    + " matched the live row counts. The retention policy keeps seven daily snapshots and four"
                    + " weekly ones, and a failed upload posts to Slack.";

    @Autowired
    EventRecorder events;

    @Autowired
    RecordingCaptureOperations capture;

    @Autowired
    RecordingCatalog catalog;

    @Autowired
    SummaryOperations summaries;

    @Autowired
    ScriptedModel model;

    @Autowired
    SummaryRecordings recordings;

    private String client;

    @BeforeEach
    void reset() {
        model.reset();
        recordings.clear();
        client = "handoff-" + UUID.randomUUID();
    }

    @Test
    void sessionEndingOnAHandoffGetsItAsItsSummaryWithNoModelCall() {
        prompt("Back up the live database every night");
        handoff(CONTEXT, List.of("Rotate the Drive token by December"), "Watch the first failure alert");

        end();

        assertThat(model.calls()).isZero();
        assertThat(summary())
                .isEqualTo(CONTEXT
                        + "\n\nOpen loops:\n- Rotate the Drive token by December"
                        + "\n\nNext: Watch the first failure alert"
                        + "\n\nAsked: Back up the live database every night");
        assertThat(source()).contains(SummarySource.HANDOFF);
        assertThat(session().title()).startsWith("Shipped the nightly backup");
        assertThat(recordings.summariesOf(session().id())).containsExactly(summary());
    }

    @Test
    void sessionWithNoHandoffFallsBackToTheModel() {
        prompt("Back up the live database every night");

        end();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(summary()).isEqualTo(ScriptedModel.SUMMARY);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    @Test
    void theLatestHandoffIsTheOneThatCounts() {
        handoff("Early plan, soon replaced. " + CONTEXT, List.of(), null);
        handoff(CONTEXT, List.of(), null);

        end();

        assertThat(model.calls()).isZero();
        assertThat(summary()).isEqualTo(CONTEXT);
    }

    @Test
    void thinHandoffFallsBackToTheModel() {
        handoff("See the plan file.", List.of(), null);

        end();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    @Test
    void aHumanTurnAfterTheHandoffMakesItStale() {
        handoff(CONTEXT, List.of(), null);
        prompt("Now also add a restore drill");

        end();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    @Test
    void windingDownAfterTheHandoffKeepsIt() {
        handoff(CONTEXT, List.of(), null);
        prompt("<task-notification><status>completed</status></task-notification>");
        tools(19);

        end();

        assertThat(model.calls()).isZero();
        assertThat(source()).contains(SummarySource.HANDOFF);
    }

    @Test
    void tooMuchWorkAfterTheHandoffMakesItStale() {
        handoff(CONTEXT, List.of(), null);
        tools(21);

        end();

        assertThat(model.calls()).isEqualTo(1);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    @Test
    void aSessionThatKeepsGoingAfterAHandoffSummaryGetsItReplacedByTheModelOnce() {
        handoff(CONTEXT, List.of(), null);
        stop();
        assertThat(source()).contains(SummarySource.HANDOFF);

        prompt("Now also add a restore drill");
        stop();

        assertThat(summary()).isEqualTo(ScriptedModel.SUMMARY);
        assertThat(source()).contains(SummarySource.MODEL);

        prompt("One more thing");
        stop();

        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void aLaterHandoffReplacesAnEarlierOneWithNoModelCall() {
        handoff(CONTEXT, List.of(), null);
        stop();
        String newer = "Superseded the earlier state. " + CONTEXT;

        handoff(newer, List.of(), null);
        stop();

        assertThat(model.calls()).isZero();
        assertThat(summary()).isEqualTo(newer);
        assertThat(recordings.summariesOf(session().id())).containsExactly(CONTEXT, newer);
    }

    @Test
    void repeatedStopsOnTheSameHandoffRecordItOnce() {
        handoff(CONTEXT, List.of(), null);
        stop();
        stop();
        stop();

        assertThat(model.calls()).isZero();
        assertThat(recordings.summariesOf(session().id())).containsExactly(CONTEXT);
    }

    @Test
    void aFailedModelKeepsTheHandoffSummaryAndTriesAgainAtTheNextStop() {
        handoff(CONTEXT, List.of(), null);
        stop();
        model.failing(true);
        prompt("Now also add a restore drill");
        stop();

        assertThat(summary()).isEqualTo(CONTEXT);
        assertThat(source()).contains(SummarySource.HANDOFF);

        model.failing(false);
        stop();

        assertThat(summary()).isEqualTo(ScriptedModel.SUMMARY);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    @Test
    void backfillUsesAHandoffWithNoModelCall() {
        handoff(CONTEXT, List.of(), null);

        summaries.summarizeMissing(1);

        assertThat(model.calls()).isZero();
        assertThat(source()).contains(SummarySource.HANDOFF);
    }

    @Test
    void anExplicitSummarizeStillAsksTheModel() {
        handoff(CONTEXT, List.of(), null);
        end();

        summaries.summarize(session().id());

        assertThat(model.calls()).isEqualTo(1);
        assertThat(source()).contains(SummarySource.MODEL);
    }

    private void prompt(String text) {
        ingest("UserPromptSubmit", "user", text);
    }

    private void handoff(String context, List<String> openLoops, String next) {
        capture.captureHandoff(
                new CaptureHandoffRequest("claude", client, "/repos/demo", "next-session", context, openLoops, next));
    }

    private void tools(int count) {
        for (int i = 0; i < count; i++) {
            ingest("PostToolUse", "tool", "ran a tool");
        }
    }

    private void end() {
        ingest("SessionEnd", "system", null);
        drain();
    }

    private void stop() {
        ingest("Stop", "assistant", null);
        drain();
    }

    private void ingest(String type, String role, String text) {
        events.ingest(new EventIngestRequest(
                "claude", client, null, type, role, text, "/repos/demo", null, null, null, Map.of(), Instant.now()));
    }

    /** The finalizer is first-in, first-out, so a later session finishing proves this one ran. */
    private void drain() {
        String marker = "drain-" + UUID.randomUUID();
        events.ingest(new EventIngestRequest(
                "claude",
                marker,
                null,
                "SessionEnd",
                "system",
                DRAIN_MARKER,
                null,
                null,
                null,
                null,
                Map.of(),
                Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> catalog.findSession("claude", marker)
                        .map(AgentSession::summary)
                        .isPresent());
    }

    private AgentSession session() {

        return catalog.findSession("claude", client).orElseThrow();
    }

    private String summary() {

        return session().summary();
    }

    private Optional<String> source() {

        return catalog.summarySource(session().id());
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        @Primary
        ScriptedModel scriptedModel() {

            return new ScriptedModel();
        }

        @Bean
        SummaryRecordings summaryRecordings() {

            return new SummaryRecordings();
        }
    }

    /** Stands in for the Codex wrapper and counts what would have reached it. */
    static class ScriptedModel implements ExternalSummaryModel {

        static final String SUMMARY = "Model summary of the whole session.";

        private final List<String> transcripts = new CopyOnWriteArrayList<>();
        private volatile boolean failing;

        @Override
        public Optional<String> summarize(String transcript) {
            if (transcript.contains(DRAIN_MARKER)) {

                return Optional.of("drained");
            }
            transcripts.add(transcript);

            return failing ? Optional.empty() : Optional.of(SUMMARY);
        }

        int calls() {

            return transcripts.size();
        }

        void failing(boolean failing) {
            this.failing = failing;
        }

        void reset() {
            transcripts.clear();
            failing = false;
        }
    }

    /** Sees what {@code EmbeddingIndexer} would be handed. */
    static class SummaryRecordings {

        private final List<SessionSummaryRecorded> recorded = new CopyOnWriteArrayList<>();

        @EventListener
        void record(SessionSummaryRecorded event) {
            recorded.add(event);
        }

        List<String> summariesOf(String sessionId) {

            return recorded.stream()
                    .filter(event -> event.session().id().equals(sessionId))
                    .map(event -> event.session().summary())
                    .toList();
        }

        void clear() {
            recorded.clear();
        }
    }
}
