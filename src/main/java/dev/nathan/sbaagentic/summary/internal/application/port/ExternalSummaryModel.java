package dev.nathan.sbaagentic.summary.internal.application.port;

import java.util.Optional;

public interface ExternalSummaryModel {

    Optional<String> summarize(String transcript);
}
