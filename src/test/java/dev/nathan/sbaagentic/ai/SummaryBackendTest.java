package dev.nathan.sbaagentic.ai;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryBackendTest {

    @Test
    void externalBackendUsesConfiguredCommand() {
        SbaProperties properties = propertiesWithExternalCommand("sed 's/^/external: /'");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("agent transcript")).isEqualTo("external: agent transcript");
    }

    @Test
    void externalBackendFailsClosedWhenCommandFails() {
        SbaProperties properties = propertiesWithExternalCommand("exit 42");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThatThrownBy(() -> backend.summarize("fallback transcript"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("External summary command failed");
    }

    @Test
    void localBackendKeepsUsingLocalAiClient() {
        SbaProperties properties = new SbaProperties();
        properties.getLocalAi().setEnabled(false);
        properties.getSummary().setBackend("local");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("local transcript")).isEqualTo("local transcript");
    }

    @Test
    void externalBackendDerivesTitleWithoutLocalAi() {
        SbaProperties properties = propertiesWithExternalCommand("cat");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.title("""
                # Session Summary

                ## Search Query Bar Shipped

                User-facing details.
                """)).isEqualTo("Search Query Bar Shipped");
    }

    private static SbaProperties propertiesWithExternalCommand(String command) {
        SbaProperties properties = new SbaProperties();
        properties.getLocalAi().setEnabled(false);
        properties.getSummary().setBackend("external");
        properties.getSummary().setExternalCommand(command);
        return properties;
    }
}
