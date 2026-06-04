package dev.nathan.sbaagentic.ai;

import java.util.Optional;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSummaryClientTest {

    @Test
    void sendsTranscriptToConfiguredCommandOnStdin() {
        SbaProperties properties = new SbaProperties();
        properties.getSummary().setExternalCommand("cat");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        Optional<String> summary = client.summarize("Session: test\n\n[UserPromptSubmit] explain the delay");

        assertThat(summary).contains("Session: test\n\n[UserPromptSubmit] explain the delay");
    }

    @Test
    void returnsEmptyWhenCommandOutputsNoUsableSummary() {
        SbaProperties properties = new SbaProperties();
        properties.getSummary().setExternalCommand("printf '   '");
        ExternalSummaryClient client = new ExternalSummaryClient(properties);

        assertThat(client.summarize("anything")).isEmpty();
    }
}
