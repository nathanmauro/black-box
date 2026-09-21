package dev.nathan.sbaagentic.memory.internal.adapter.in.mcp;

import java.lang.reflect.Type;
import java.util.function.Supplier;
import dev.nathan.sbaagentic.memory.CompactSearchOperations;
import dev.nathan.sbaagentic.memory.CompactSearchResult;
import dev.nathan.sbaagentic.memory.internal.application.CompactSearchJson;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class CompactSearchMcpTools implements Supplier<ToolCallback[]> {
    private final CompactSearchOperations search;
    public CompactSearchMcpTools(CompactSearchOperations search) { this.search = search; }

    @Tool(description = "Discover captured evidence with bounded excerpts and source references. Prefer this over raw searchSessions. "
            + "Use recallContext first for prior structured intent. Supports source:, kind:, session:, since:, until:, last:. "
            + "until: dates include the entire day in server timezone; before: is unsupported. "
            + "Filtered queries search canonical local storage only. Check status, coverage and truncation before inferring absence. "
            + "Source candidates and matching text do not prove origin or causation.", resultConverter = JsonConverter.class)
    public CompactSearchResult searchContext(
            @ToolParam(description = "Nonblank query, at most 1024 UTF-16 units. Quote a whole operator token to search it literally.") String query,
            @ToolParam(required = false, description = "Global result limit; default 10, maximum 50.") Integer limit,
            @ToolParam(required = false, description = "Serialized application JSON UTF-8 byte budget; default 24000, range 2048–64000. MCP framing is additional.") Integer maxBytes,
            @ToolParam(required = false, description = "Exclude one exact internal or client session ID before selecting candidates.") String excludeSession,
            @ToolParam(required = false, description = "Default true: group identical complete observer text as origin-unknown similarity, never decisions. False lists individual events.") Boolean groupSimilar) {
        return search.search(query, limit, maxBytes, excludeSession, groupSimilar);
    }

    @Override
    public ToolCallback[] get() { return MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks(); }

    public static final class JsonConverter implements ToolCallResultConverter {
        @Override
        public String convert(Object result, Type returnType) { return CompactSearchJson.write(result); }
    }
}
