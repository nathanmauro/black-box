package dev.nathan.sbaagentic.summary.internal.adapter.in.mcp;

import java.util.function.Supplier;

import dev.nathan.sbaagentic.summary.SummaryModelOperations;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/** MCP adapter for summary model status. */
@Component
public class SummaryMcpTools implements Supplier<ToolCallback[]> {

    private final SummaryModelOperations summaryModel;

    public SummaryMcpTools(SummaryModelOperations summaryModel) {
        this.summaryModel = summaryModel;
    }

    @Override
    public ToolCallback[] get() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks();
    }

    @Tool(description = "Check the local AI backend status for LM Studio or another OpenAI-compatible local model server.")
    public Object localModelStatus() {
        return summaryModel.health();
    }
}
