package dev.nathan.sbaagentic.recording.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nathan.sbaagentic.recording.IngestionProperties;
import dev.nathan.sbaagentic.recording.RedactionStatistics;
import dev.nathan.sbaagentic.recording.RedactionStatistics.Channel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Redaction counters count passes per channel without recording content")
class RedactionStatisticsTest {

    private static final String SECRET = "Vx8Qa3Lp7Rk2Nm5Tz9Bc4Wd";
    private static final String LEAK = "deploy with password=" + "Hunter2-not-real-at-all";

    private RedactionService redactor(IngestionProperties properties) {

        return new RedactionService(properties, ConfiguredSecretMasker.ofValues(List.of(SECRET)));
    }

    @Test
    void freshServiceReportsConfigurationAndZeroCounts() {
        RedactionStatistics stats = redactor(new IngestionProperties()).statistics();

        assertThat(stats.ingestionRedactionEnabled()).isTrue();
        assertThat(stats.defaultRules()).isTrue();
        assertThat(stats.configuredSecretValues()).isEqualTo(1);
        assertThat(stats.ingest()).isEqualTo(new Channel(0, 0, 0));
        assertThat(stats.export()).isEqualTo(new Channel(0, 0, 0));
        assertThat(stats.summary()).isEqualTo(new Channel(0, 0, 0));
    }

    @Test
    void ingestCountsInspectedRedactedAndCharactersButNotNulls() {
        RedactionService redactor = redactor(new IngestionProperties());

        redactor.redact("nothing to see");
        redactor.redact(LEAK);
        redactor.redact("the value " + SECRET + " leaked");
        redactor.redact(null);

        Channel ingest = redactor.statistics().ingest();
        assertThat(ingest.inspected()).isEqualTo(3);
        assertThat(ingest.redacted()).isEqualTo(2);
        assertThat(ingest.charsInspected())
                .isEqualTo("nothing to see".length() + LEAK.length() + ("the value " + SECRET + " leaked").length());
    }

    @Test
    void redactDeepCountsEveryScalarOnTheIngestChannel() {
        RedactionService redactor = redactor(new IngestionProperties());

        redactor.redactDeep(Map.of("note", "clean", "nested", List.of(LEAK, "also clean")));

        Channel ingest = redactor.statistics().ingest();
        assertThat(ingest.inspected()).isGreaterThanOrEqualTo(3);
        assertThat(ingest.redacted()).isEqualTo(1);
    }

    @Test
    void exportAndSummaryChannelsAreSeparate() {
        RedactionService redactor = redactor(new IngestionProperties());

        redactor.redactForExport(LEAK);
        redactor.redactForSummary("clean summary text");
        redactor.redactJsonForSummary("{\"command\":\"echo " + SECRET + "\",\"n\":1}");
        // Not one JSON value: falls back to text redaction but still counts once.
        redactor.redactJsonForSummary("plain " + SECRET);

        RedactionStatistics stats = redactor.statistics();
        assertThat(stats.ingest().inspected()).isZero();
        assertThat(stats.export()).extracting(Channel::inspected, Channel::redacted).containsExactly(1L, 1L);
        assertThat(stats.summary()).extracting(Channel::inspected, Channel::redacted).containsExactly(3L, 2L);
    }

    @Test
    void disabledRulesStillCountAndStillMaskOwnSecrets() {
        IngestionProperties properties = new IngestionProperties();
        properties.setRedactEnabled(false);
        RedactionService redactor = redactor(properties);

        assertThat(redactor.redact(LEAK)).isEqualTo(LEAK);
        assertThat(redactor.redact("x " + SECRET)).doesNotContain(SECRET);

        RedactionStatistics stats = redactor.statistics();
        assertThat(stats.ingestionRedactionEnabled()).isFalse();
        assertThat(stats.ingest()).extracting(Channel::inspected, Channel::redacted).containsExactly(2L, 1L);
    }

    @Test
    void countersAreExactUnderConcurrentWriters() throws InterruptedException {
        RedactionService redactor = redactor(new IngestionProperties());
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    redactor.redact(i % 2 == 0 ? LEAK : "clean line " + i);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        Channel ingest = redactor.statistics().ingest();
        assertThat(ingest.inspected()).isEqualTo((long) threads * perThread);
        assertThat(ingest.redacted()).isEqualTo((long) threads * perThread / 2);
    }

    @Test
    void statisticsNeverExposeTheConfiguredValue() {
        RedactionService redactor = redactor(new IngestionProperties());
        redactor.redact("x " + SECRET);

        assertThat(redactor.statistics().toString()).doesNotContain(SECRET);
    }
}
