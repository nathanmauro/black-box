package dev.nathan.sbaagentic.summary.internal.application.port;

import dev.nathan.sbaagentic.summary.AiHealth;

public interface LocalSummaryModel {

    AiHealth health();

    String summarize(String text);

    String title(String sourceText);
}
