package dev.nathan.sbaagentic.platform.internal.adapter.in.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class McpToolConfiguration {

    @Bean
    ToolCallbackProvider agenticToolCallbacks(List<Supplier<ToolCallback[]>> toolGroups) {
        ToolCallback[] callbacks = toolGroups.stream()
                .flatMap(group -> Arrays.stream(group.get()))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(callbacks);
    }
}
