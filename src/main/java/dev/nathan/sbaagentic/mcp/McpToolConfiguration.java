package dev.nathan.sbaagentic.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider agenticToolCallbacks(AgenticTools agenticTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(agenticTools)
                .build();
    }
}
