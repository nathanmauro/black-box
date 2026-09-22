package dev.nathan.sbaagentic.judgment.internal.application.port;

public record JudgeStats(long calls, long failures, Long lastLatencyMs) {

    public static JudgeStats empty() {
        return new JudgeStats(0, 0, null);
    }
}
