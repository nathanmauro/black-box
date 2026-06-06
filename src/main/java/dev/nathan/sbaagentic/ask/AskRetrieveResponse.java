package dev.nathan.sbaagentic.ask;

import java.util.List;

public record AskRetrieveResponse(
        String query,
        String retrievalMode,
        boolean degraded,
        String statusMessage,
        List<AskCitation> results) {
}
