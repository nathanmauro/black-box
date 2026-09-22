package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.nathan.sbaagentic.judgment.internal.application.Judgment;
import dev.nathan.sbaagentic.judgment.internal.domain.BeatState;

public class JevAnswerValidator {

    private final JudgeQuestionSet questions;

    public JevAnswerValidator(JudgeQuestionSet questions) {
        this.questions = questions;
    }

    public Judgment validate(JsonNode response, BeatState state, Instant judgedAt, Long latencyMs) {
        JsonNode answers = response == null ? null : response.path("answers");
        if (answers == null || !answers.isObject()) {
            throw malformed("missing answers");
        }
        String phase = choice(answers.path("phase"), "phase");
        if (!questions.phaseChoices().contains(phase)) {
            throw malformed("unknown phase");
        }
        double salience = score(answers.path("salience"), "salience", questions.scoreLevelCount("salience"));
        double novelty = score(answers.path("novelty"), "novelty", questions.scoreLevelCount("novelty"));

        double human = state.ruleHuman();
        JsonNode humanAnswer = answers.path("human");
        if (state.askHuman()) {
            human = noul(humanAnswer, "human");
        }
        else if (!humanAnswer.isMissingNode()) {
            noul(humanAnswer, "human");
        }

        Map<String, Double> kin = new LinkedHashMap<>();
        for (BeatState.OtherSessionState other : state.others()) {
            kin.put(other.sessionId(), noul(answers.path("kin_" + other.k()), "kin_" + other.k()));
        }

        return new Judgment(
                phase,
                salience,
                novelty,
                human,
                Map.copyOf(kin),
                "jev",
                response.path("model").asText(questions.model()),
                questions.version(),
                answers.deepCopy(),
                judgedAt,
                latencyMs);
    }

    private String choice(JsonNode node, String id) {
        requireType(node, id, "choice");
        if (!Json.num01(node.path("confidence"))) {
            throw malformed("bad " + id + " confidence");
        }
        validateProbabilities(node.path("probabilities"), id);
        String choice = node.path("choice").asText(null);
        if (choice == null || choice.isBlank()) {
            throw malformed("bad " + id + " choice");
        }
        return choice;
    }

    private double score(JsonNode node, String id, int levelCount) {
        requireType(node, id, "score");
        JsonNode score = node.path("score");
        if (!score.isNumber() || !Double.isFinite(score.asDouble())
                || score.asDouble() < 0.0
                || score.asDouble() > Math.max(0, levelCount - 1)) {
            throw malformed("bad " + id + " score");
        }
        if (!Json.num01(node.path("confidence"))) {
            throw malformed("bad " + id + " confidence");
        }
        validateProbabilities(node.path("probabilities"), id);
        return score.asDouble();
    }

    private double noul(JsonNode node, String id) {
        requireType(node, id, "noul");
        if (!Json.num01(node.path("noul"))) {
            throw malformed("bad " + id + " noul");
        }
        return node.path("noul").asDouble();
    }

    private void requireType(JsonNode node, String id, String type) {
        if (node == null || !node.isObject() || !type.equals(node.path("type").asText())) {
            throw malformed("bad " + id);
        }
    }

    private void validateProbabilities(JsonNode node, String id) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw malformed("bad " + id + " probabilities");
        }
        node.fields().forEachRemaining(entry -> {
            if (!Json.num01(entry.getValue())) {
                throw malformed("bad " + id + " probability");
            }
        });
    }

    private static MalformedJudgmentException malformed(String message) {
        return new MalformedJudgmentException(message);
    }
}
