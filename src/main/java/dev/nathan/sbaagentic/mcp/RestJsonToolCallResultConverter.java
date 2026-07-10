package dev.nathan.sbaagentic.mcp;

import java.lang.reflect.Type;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

/** Serializes task tool results with the same ISO-8601 JSON wire types as the REST API. */
public final class RestJsonToolCallResultConverter implements ToolCallResultConverter {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Override
    public String convert(Object result, Type returnType) {
        try {
            return OBJECT_MAPPER.writeValueAsString(result);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize task tool result", ex);
        }
    }
}
