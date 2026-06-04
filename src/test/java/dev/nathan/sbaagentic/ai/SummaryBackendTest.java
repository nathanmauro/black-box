package dev.nathan.sbaagentic.ai;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryBackendTest {

    @Test
    void externalBackendUsesConfiguredCommand() {
        SbaProperties properties = propertiesWithExternalCommand("sed 's/^/external: /'");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("agent transcript")).isEqualTo("external: agent transcript");
    }

    @Test
    void externalBackendFallsBackToLocalSummaryWhenCommandFails() {
        SbaProperties properties = propertiesWithExternalCommand("exit 42");
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("fallback transcript")).isEqualTo("fallback transcript");
    }

    @Test
    void localBackendKeepsUsingLocalAiClient() {
        SbaProperties properties = new SbaProperties();
        properties.getLocalAi().setEnabled(false);
        SummaryBackend backend = new SummaryBackend(
                properties, new LocalAiClient(properties), new ExternalSummaryClient(properties));

        assertThat(backend.summarize("local transcript")).isEqualTo("local transcript");
    }

    private static SbaProperties propertiesWithExternalCommand(String command) {
        SbaProperties properties = new SbaProperties();
        properties.getLocalAi().setEnabled(false);
        properties.getSummary().setBackend("external");
        properties.getSummary().setExternalCommand(command);
        return properties;
    }
}
