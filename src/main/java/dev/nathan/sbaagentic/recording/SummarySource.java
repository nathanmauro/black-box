package dev.nathan.sbaagentic.recording;

/**
 * What produced a session's stored summary, kept in {@code agent_sessions.summary_source} so the two
 * can be compared later. A {@code NULL} source predates this column and always means a model.
 */
public final class SummarySource {

    /** A configured summary backend read the transcript and wrote the summary. */
    public static final String MODEL = "model";

    /** Built from the session's latest handoff with no model call. Provisional: see the finalizer. */
    public static final String HANDOFF = "handoff";

    private SummarySource() {}
}
