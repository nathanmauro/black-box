package dev.nathan.sbaagentic.summary;

import org.springframework.stereotype.Component;

/** Transitional implementation for the project/summary MCP group. */
@Component
public class ProjectSummaryTools {

    private final SummaryModelOperations localAiClient;

    public ProjectSummaryTools(SummaryModelOperations localAiClient) {
        this.localAiClient = localAiClient;
    }

    public Object localModelStatus() {
        return localAiClient.health();
    }
}
