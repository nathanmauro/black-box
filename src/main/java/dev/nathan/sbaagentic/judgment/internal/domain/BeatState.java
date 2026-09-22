package dev.nathan.sbaagentic.judgment.internal.domain;

import java.util.List;

public record BeatState(
        Beat beat,
        SessionState session,
        List<String> trail,
        List<OtherSessionState> others,
        boolean askHuman,
        double ruleHuman) {

    public record SessionState(String source, String repo, String title) {
    }

    public record OtherSessionState(String sessionId, int k, String source, String repo, String title, String latest) {
    }
}
