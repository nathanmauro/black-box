package dev.nathan.sbaagentic.ask;

import java.util.List;

public record AskResponse(
        String question,
        String answer,
        String retrievalMode,
        boolean degraded,
        List<AskCitation> citations) {
}
