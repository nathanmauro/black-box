package dev.nathan.sbaagentic.summary.internal.application;

import dev.nathan.sbaagentic.summary.internal.adapter.out.http.LocalAiClient;
import dev.nathan.sbaagentic.summary.internal.adapter.out.process.ExternalSummaryClient;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import dev.nathan.sbaagentic.summary.SummaryProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryBackendTest {

    @Test
    void externalBackendUsesConfiguredCommand() {
        SummaryProperties properties = propertiesWithExternalCommand("sed 's/^/external: /'");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(disabledLocalModel()), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("agent transcript")).isEqualTo("external: agent transcript");
    }

    @Test
    void externalBackendFailsClosedWhenCommandFails() {
        SummaryProperties properties = propertiesWithExternalCommand("exit 42");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(disabledLocalModel()), new ExternalSummaryClient(properties));

        assertThatThrownBy(() -> backend.summarize("fallback transcript"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("External summary command failed");
    }

    @Test
    void localBackendKeepsUsingLocalAiClient() {
        SummaryProperties properties = new SummaryProperties();
        properties.setBackend("local");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(disabledLocalModel()), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("local transcript")).isEqualTo("local transcript");
    }

    @Test
    void externalBackendDerivesTitleWithoutLocalAi() {
        SummaryProperties properties = propertiesWithExternalCommand("cat");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(disabledLocalModel()), new ExternalSummaryClient(properties));

        assertThat(backend.title("""
                # Session Summary

                ## Search Query Bar Shipped

                User-facing details.
                """)).isEqualTo("Search Query Bar Shipped");
    }

    private static SummaryProperties propertiesWithExternalCommand(String command) {
        SummaryProperties properties = new SummaryProperties();
        properties.setBackend("external");
        properties.setExternalCommand(command);
        return properties;
    }

    private static SummaryModelProperties disabledLocalModel() {
        SummaryModelProperties properties = new SummaryModelProperties();
        properties.setEnabled(false);
        return properties;
    }
}
