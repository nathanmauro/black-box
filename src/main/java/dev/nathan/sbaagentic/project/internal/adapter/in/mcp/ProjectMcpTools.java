package dev.nathan.sbaagentic.project.internal.adapter.in.mcp;

import dev.nathan.sbaagentic.project.BraidDiscoveryOperations;
import dev.nathan.sbaagentic.project.BraidDiscoveryResult;
import dev.nathan.sbaagentic.project.internal.application.BraidDiscoveryJson;
import java.lang.reflect.Type;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class ProjectMcpTools implements Supplier<ToolCallback[]> {
    private final BraidDiscoveryOperations discovery;

    public ProjectMcpTools(BraidDiscoveryOperations discovery) {
        this.discovery = discovery;
    }

    @Tool(
            description =
                    "Discover persisted saved braids across assigned and unassigned projects; no selectors gives recent artifacts. Excludes ordinary melds and captured Braid events. Artifacts are not event citations; createdAt is save time and provider/model are caller-declared. Content is export-redacted and may be clipped. Check status, textComplete, transformation and truncation and follow nextBefore with the same query/session filters; REST detailPath addresses stored content.",
            resultConverter = JsonConverter.class)
    public BraidDiscoveryResult findBraids(
            @ToolParam(
                            required = false,
                            description =
                                    "Exact artifact ID. Cannot combine with query, sessionId or before; missing/ordinary artifacts return not_found.")
                    String id,
            @ToolParam(
                            required = false,
                            description =
                                    "Literal case-sensitive title/body substring, trimmed and nonblank, at most 1024 UTF-16 units. No operators or wildcards.")
                    String query,
            @ToolParam(
                            required = false,
                            description =
                                    "Exact internal Black Box session ID membership, AND with query. Client session IDs are not accepted as aliases.")
                    String sessionId,
            @ToolParam(required = false, description = "Default 10, accepted 1–20; out-of-range rejected.")
                    Integer limit,
            @ToolParam(
                            required = false,
                            description =
                                    "Opaque continuation from this tool. Bound to normalized query/session and saved-braid coverage; limit/budget may vary.")
                    String before,
            @ToolParam(
                            required = false,
                            description =
                                    "Exact returned application JSON UTF-8 budget: default 24000, accepted 2048–64000. MCP framing is additional. Irreducible identities return budget_exceeded without advancement.")
                    Integer maxBytes) {

        return discovery.find(id, query, sessionId, limit, before, maxBytes);
    }

    @Override
    public ToolCallback[] get() {

        return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks();
    }

    public static final class JsonConverter implements ToolCallResultConverter {
        @Override
        public String convert(Object result, Type returnType) {

            return BraidDiscoveryJson.write(result);
        }
    }
}
