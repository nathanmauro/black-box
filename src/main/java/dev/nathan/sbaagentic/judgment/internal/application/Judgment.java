package dev.nathan.sbaagentic.judgment.internal.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public record Judgment(
        String phase,
        double salience,
        double novelty,
        double human,
        Map<String, Double> kin,
        String judge,
        String model,
        String version,
        JsonNode answers,
        Instant judgedAt,
        Long latencyMs) {}
