package dev.nathan.sbaagentic.memory.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** One serializer for budgeting and REST/MCP payloads; transport framing is outside this budget. */
public final class CompactSearchJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CompactSearchJson() {}

    public static String write(Object value) {
        try {

            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize compact search", ex);
        }
    }
}
