package dev.nathan.sbaagentic.recording;

/**
 * Quality ranks for session titles. A stored title is only replaced when a
 * strictly higher-ranked candidate arrives, so a session keeps its best name
 * instead of being clobbered by whatever event happens to land next.
 */
public final class TitleRank {

    /** Last-resort title built from {@code source + eventType}. */
    public static final int FALLBACK = 1;

    /** Title built from a tool event: {@code toolName + " via " + eventType}. */
    public static final int TOOL = 2;

    /** Title derived from the first line of an event's text. */
    public static final int TEXT = 3;

    /** Title a client explicitly supplied via {@code metadata.title}. */
    public static final int EXPLICIT = 4;

    /** Pre-ranking sessions backfilled on migration; kept until an AI retitle. */
    public static final int LEGACY = 50;

    /** AI-generated title produced at summarize time. Locks above all ingest ranks. */
    public static final int AI = 100;

    private TitleRank() {
    }
}
