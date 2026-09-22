package dev.nathan.sbaagentic.judgment;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record EventJudgment(
        String eventId,
        String sessionId,
        String beatId,
        String judge,
        String model,
        String version,
        JsonNode answers,
        Instant judgedAt) {}
