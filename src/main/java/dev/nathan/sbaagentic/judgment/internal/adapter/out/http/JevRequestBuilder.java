package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public class JevRequestBuilder {

    private static final Pattern HOME_PREFIX = Pattern.compile("^/Users/[^/]+/");

    private final JudgeQuestionSet questions;
    private final UnaryOperator<String> sanitize;

    public JevRequestBuilder(JudgeQuestionSet questions) {
        this(questions, UnaryOperator.identity());
    }

    public JevRequestBuilder(JudgeQuestionSet questions, UnaryOperator<String> sanitize) {
        this.questions = questions;
        this.sanitize = sanitize;
    }

    public ObjectNode build(BeatState state) {
        ObjectNode body = Json.object();
        body.put("model", questions.model());
        body.set("state", sanitizeTree(state(state)));
        ObjectNode requested = questions.baseQuestions();
        if (!state.askHuman()) {
            requested.remove("human");
        }
        for (BeatState.OtherSessionState other : state.others()) {
            requested.set("kin_" + other.k(), questions.kinQuestionFor(other.k()));
        }
        body.set("questions", requested);

        return body;
    }

    private JsonNode sanitizeTree(JsonNode node) {
        if (node.isTextual())

            return TextNode.valueOf(sanitize.apply(node.textValue()));

        if (node.isObject()) {
            ObjectNode copy = node.deepCopy();
            node.fields().forEachRemaining(entry -> copy.set(entry.getKey(), sanitizeTree(entry.getValue())));

            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = Json.array();
            node.forEach(value -> copy.add(sanitizeTree(value)));

            return copy;
        }

        return node;
    }

    private ObjectNode state(BeatState state) {
        ObjectNode root = Json.object();
        ObjectNode beat = Json.object();
        ArrayNode events = Json.array();
        for (String line : state.beat().lines()) {
            ObjectNode event = Json.object();
            event.put("line", line);
            events.add(event);
        }
        beat.set("events", events);
        root.set("beat", beat);

        ObjectNode session = Json.object();
        session.put("source", state.session().source());
        putNullable(session, "repo", shortRepo(state.session().repo()));
        putNullable(session, "title", clip(state.session().title(), 120));
        root.set("session", session);

        ArrayNode trail = Json.array();
        state.trail().stream()
                .skip(Math.max(0, state.trail().size() - 5))
                .map(title -> clip(title, 200))
                .forEach(trail::add);
        root.set("trail", trail);

        ArrayNode others = Json.array();
        for (BeatState.OtherSessionState other : state.others()) {
            ObjectNode node = Json.object();
            node.put("k", other.k());
            node.put("source", other.source());
            putNullable(node, "repo", shortRepo(other.repo()));
            putNullable(node, "title", clip(other.title(), 120));
            node.put("latest", clip(other.latest(), 400));
            others.add(node);
        }
        root.set("others", others);

        return root;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String shortRepo(String repo) {

        return repo == null ? null : HOME_PREFIX.matcher(repo).replaceFirst("~/");
    }

    private static String clip(String value, int max) {
        if (value == null) {

            return "";
        }

        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
