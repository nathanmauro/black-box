package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;
import java.util.regex.Pattern;

public class JevRequestBuilder {

    private static final Pattern HOME_PREFIX = Pattern.compile("^/Users/[^/]+/");

    private final JudgeQuestionSet questions;

    public JevRequestBuilder(JudgeQuestionSet questions) {
        this.questions = questions;
    }

    public ObjectNode build(BeatState state) {
        ObjectNode body = Json.object();
        body.put("model", questions.model());
        body.set("state", state(state));
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
