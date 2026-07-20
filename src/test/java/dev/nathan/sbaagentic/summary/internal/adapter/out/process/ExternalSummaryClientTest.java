package dev.nathan.sbaagentic.summary.internal.adapter.out.process;

import java.util.Optional;

import dev.nathan.sbaagentic.summary.SummaryProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSummaryClientTest {

    @Test
    void sendsTranscriptToConfiguredCommandOnStdin() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("cat");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        Optional<String> summary = client.summarize("Session: test\n\n[UserPromptSubmit] explain the delay");

        assertThat(summary).contains("Session: test\n\n[UserPromptSubmit] explain the delay");
    }

    @Test
    void returnsEmptyWhenCommandOutputsNoUsableSummary() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("printf '   '");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        assertThat(client.summarize("anything")).isEmpty();
    }

    @Test
    void posixShellCommandWorks() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("printf 'posix-ok'");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        Optional<String> summary = client.summarize("ignored");

        assertThat(summary).contains("posix-ok");
    }

    @Test
    void missingCommandFailsCleanly() {
        SummaryProperties properties = new SummaryProperties();
        properties.setExternalCommand("/definitely/not/a/real/sba-summary-command");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        assertThat(client.summarize("anything")).isEmpty();
    }
}
