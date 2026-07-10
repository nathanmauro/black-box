package dev.nathan.sbaagentic.mcp;

import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.task.TaskDomainException;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider agenticToolCallbacks(AgenticTools agenticTools, ObjectMapper objectMapper) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(agenticTools)
                .build()
                .getToolCallbacks();
        return ToolCallbackProvider.from(Arrays.stream(callbacks)
                .map(callback -> withStructuredTaskErrors(callback, objectMapper))
                .toArray(ToolCallback[]::new));
    }

    private static ToolCallback withStructuredTaskErrors(ToolCallback delegate, ObjectMapper objectMapper) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                try {
                    return delegate.call(toolInput);
                }
                catch (RuntimeException ex) {
                    throw structuredTaskError(delegate, objectMapper, ex);
                }
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    return delegate.call(toolInput, toolContext);
                }
                catch (RuntimeException ex) {
                    throw structuredTaskError(delegate, objectMapper, ex);
                }
            }
        };
    }

    private static RuntimeException structuredTaskError(
            ToolCallback delegate,
            ObjectMapper objectMapper,
            RuntimeException failure) {
        TaskDomainException taskError = findTaskError(failure);
        if (taskError == null) {
            return failure;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new TaskToolErrorEnvelope(new TaskToolError(
                    taskError.code().name().toLowerCase(Locale.ROOT),
                    taskError.code().name(),
                    taskError.getMessage(),
                    taskError.taskId(),
                    taskError.currentStatus() == null ? null : taskError.currentStatus().value(),
                    taskError.targetStatus() == null ? null : taskError.targetStatus().value())));
        }
        catch (JsonProcessingException ex) {
            return new IllegalStateException("Unable to serialize task tool error", ex);
        }
        return new ToolExecutionException(
                delegate.getToolDefinition(),
                new StructuredTaskToolException(payload, failure));
    }

    private static TaskDomainException findTaskError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TaskDomainException taskError) {
                return taskError;
            }
            current = current.getCause();
        }
        return null;
    }

    private record TaskToolErrorEnvelope(TaskToolError error) {
    }

    private record TaskToolError(
            String type,
            String code,
            String message,
            String taskId,
            String currentStatus,
            String targetStatus) {
    }

    private static final class StructuredTaskToolException extends RuntimeException {

        private StructuredTaskToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
