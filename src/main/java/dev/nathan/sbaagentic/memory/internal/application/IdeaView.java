package dev.nathan.sbaagentic.memory.internal.application;

import java.time.Instant;
import java.util.List;

/**
 * The latest state of one idea, collapsed from every {@code Idea} event that shares its
 * {@code ideaKey}. Capture fields come from the newest event; {@code firstCapturedAt} and
 * {@code revisions} describe the whole key.
 */
public record IdeaView(
        String eventId,
        String sessionId,
        String source,
        String clientSessionId,
        String repo,
        String title,
        String oneLiner,
        String origin,
        String quote,
        String sourceRef,
        Integer legs,
        String status,
        List<String> connects,
        String resumeStep,
        String link,
        String notes,
        String ideaKey,
        Instant capturedAt,
        Instant firstCapturedAt,
        int revisions,
        String migratedFrom) {}
