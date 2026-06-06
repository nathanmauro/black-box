package dev.nathan.sbaagentic.ai;

import dev.nathan.sbaagentic.config.SbaProperties;
import dev.nathan.sbaagentic.session.Titles;

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
            return externalSummaryClient.summarize(transcript)
                    .orElseThrow(() -> new IllegalStateException(
                            "External summary command failed or produced no summary"));
        }
        return localAiClient.summarize(transcript);
    }

    public String title(String sourceText) {
        if ("external".equalsIgnoreCase(properties.getBackend())) {
            return titleFromSummary(sourceText);
        }
        return localAiClient.title(sourceText);
    }

    private static String titleFromSummary(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return Titles.sanitize(null);
        }
        for (String line : sourceText.split("\\R")) {
            String candidate = cleanTitleCandidate(line);
            if (candidate.isBlank() || isGenericHeading(candidate)) {
                continue;
            }
            return Titles.sanitize(candidate);
        }
        return Titles.sanitize(Titles.firstLine(sourceText));
    }

    private static String cleanTitleCandidate(String line) {
        String candidate = line == null ? "" : line.strip();
        candidate = candidate.replaceFirst("^#{1,6}\\s+", "");
        candidate = candidate.replaceFirst("^[-*]\\s+", "");
        candidate = candidate.replaceAll("^\\*\\*(.+)\\*\\*$", "$1");
        candidate = candidate.replaceFirst("(?i)^(title|summary|recap)\\s*[:\\-]\\s*", "");
        return candidate.strip();
    }

    private static boolean isGenericHeading(String candidate) {
        String normalized = candidate.toLowerCase().replaceAll("[^a-z0-9 ]", "").strip();
        return normalized.isBlank()
                || normalized.equals("summary")
                || normalized.equals("session summary")
                || normalized.equals("recap")
                || normalized.equals("session recap");
    }
}
