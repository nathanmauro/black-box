package dev.nathan.sbaagentic.memory.internal.application;

import java.util.List;

/** {@code GET /api/ideas}: collapsed ideas, newest first; {@code count} is the number returned. */
public record IdeaListResponse(List<IdeaView> items, int count) {}
