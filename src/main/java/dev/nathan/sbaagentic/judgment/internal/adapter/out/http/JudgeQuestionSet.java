package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.core.io.Resource;

public final class JudgeQuestionSet {

    private final JsonNode root;

    private JudgeQuestionSet(JsonNode root) {
        this.root = root;
    }

    public static JudgeQuestionSet load(ObjectMapper objectMapper, Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new JudgeQuestionSet(objectMapper.readTree(input));
        }
    }

    public String version() {
        return root.path("version").asText();
    }

    public String model() {
        return root.path("model").asText("jev-latest");
    }

    public int maxOthers() {
        return root.path("maxOthers").asInt(8);
    }

    public ObjectNode baseQuestions() {
        return root.path("questions").deepCopy();
    }

    public ObjectNode kinQuestionFor(int k) {
        ObjectNode question = root.path("kin").deepCopy();
        String instructions = question.path("instructions").asText().replace("[K]", "[" + k + "]");
        question.put("instructions", instructions);
        return question;
    }

    public Set<String> phaseChoices() {
        Set<String> choices = new LinkedHashSet<>();
        Iterator<String> names = root.at("/questions/phase/criteria").fieldNames();
        names.forEachRemaining(choices::add);
        return choices;
    }

    public int scoreLevelCount(String questionId) {
        JsonNode criteria = root.at("/questions/" + questionId + "/criteria");
        return criteria.isArray() ? criteria.size() : 0;
    }
}
