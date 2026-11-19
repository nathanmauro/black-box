package dev.nathan.sbaagentic.project.internal.application;

import java.util.List;

/**
 * A project continuity graph: sessions, the decisions and handoffs they captured, and the braids
 * that weave them. Node ids are generated ({@code s1}, {@code d1}, ...); {@code ref} carries the
 * stored id. Every stored string has passed through export redaction.
 */
public record BraidGraph(
        String generatedAt,
        String project,
        int maxSessions,
        Counts counts,
        boolean truncated,
        List<Node> nodes,
        List<Edge> edges,
        List<String> notes) {

    public static final String SESSION = "session";
    public static final String DECISION = "decision";
    public static final String HANDOFF = "handoff";
    public static final String BRAID = "braid";
    public static final String MISSING_SESSION = "missing_session";

    public record Counts(int sessions, int decisions, int handoffs, int braids, int missingSessions, int edges) {}

    public record Node(
            String id, String kind, String ref, String label, String at, String project, List<String> flags) {}

    /** {@code relation} is one of decided, handed_off, carried_by, picked_up_by, woven_into. */
    public record Edge(String from, String to, String relation) {}
}
