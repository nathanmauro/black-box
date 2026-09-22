package dev.nathan.sbaagentic.judgment;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record EventJudgment(
        String eventId,
        String sessionId,
        String beatId,
        String judge,
        String model,
        String version,
        JsonNode answers,
        Instant judgedAt) {
}
