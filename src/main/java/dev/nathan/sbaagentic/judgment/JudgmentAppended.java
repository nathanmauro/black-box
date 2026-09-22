package dev.nathan.sbaagentic.judgment;

import java.util.List;
import java.util.Map;

public record JudgmentAppended(
        List<String> eventIds,
        String sessionId,
        String beatId,
        String phase,
        double salience,
        double novelty,
        double human,
        Map<String, Double> kin,
        String judge,
        String model,
        String version,
        String judgedAt) {}
