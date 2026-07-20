package dev.nathan.sbaagentic.project;

import dev.nathan.sbaagentic.ai.LocalAiClient;

import org.springframework.stereotype.Component;

/** Transitional implementation for the project/summary MCP group. */
@Component
public class ProjectSummaryTools {

    private final LocalAiClient localAiClient;

    public ProjectSummaryTools(LocalAiClient localAiClient) {
        this.localAiClient = localAiClient;
    }

    public Object localModelStatus() {
        return localAiClient.health();
    }
}
