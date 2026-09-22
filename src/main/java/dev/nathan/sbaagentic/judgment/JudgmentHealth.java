package dev.nathan.sbaagentic.judgment;

public record JudgmentHealth(
        boolean enabled,
        String provider,
        String model,
        long calls,
        long failures,
        Long lastLatencyMs,
        int queued,
        long dropped) {}
