package dev.nathan.sbaagentic.ai;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.springframework.stereotype.Component;

@Component
public class SummaryBackend {

    private final SbaProperties.Summary properties;
    private final LocalAiClient localAiClient;
    private final ExternalSummaryClient externalSummaryClient;

    public SummaryBackend(
            SbaProperties properties,
            LocalAiClient localAiClient,
            ExternalSummaryClient externalSummaryClient) {
        this.properties = properties.getSummary();
        this.localAiClient = localAiClient;
        this.externalSummaryClient = externalSummaryClient;
    }

    public String summarize(String transcript) {
        if ("external".equalsIgnoreCase(properties.getBackend())) {
            return externalSummaryClient.summarize(transcript).orElseGet(() -> localAiClient.summarize(transcript));
        }
        return localAiClient.summarize(transcript);
    }

    public String title(String sourceText) {
        return localAiClient.title(sourceText);
    }
}
