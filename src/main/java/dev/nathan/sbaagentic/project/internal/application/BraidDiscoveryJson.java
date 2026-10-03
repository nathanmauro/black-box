package dev.nathan.sbaagentic.project.internal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;

/** The converter and byte-budget calculation must use this exact serializer. */
public final class BraidDiscoveryJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private BraidDiscoveryJson() {}

    public static String write(Object value) {
        try {

            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize braid discovery", ex);
        }
    }

    public static int bytes(Object value) {

        return write(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
