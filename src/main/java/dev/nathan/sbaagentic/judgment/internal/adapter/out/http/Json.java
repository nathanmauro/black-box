package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class Json {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private Json() {
    }

    static ObjectNode object() {
        return FACTORY.objectNode();
    }

    static ArrayNode array() {
        return FACTORY.arrayNode();
    }

    static boolean num01(JsonNode node) {
        return node != null
                && node.isNumber()
                && Double.isFinite(node.asDouble())
                && node.asDouble() >= 0.0
                && node.asDouble() <= 1.0;
    }
}
