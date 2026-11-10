package dev.nathan.sbaagentic.recording;

/** Session identity and display metadata without the transcript, summary, or first human turn. */
public record SessionHeader(String id, String title, String source) {}
