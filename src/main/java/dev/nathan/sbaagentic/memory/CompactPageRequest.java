package dev.nathan.sbaagentic.memory;

import java.util.List;

/** Typed literal request for canonical compact pages; values are never parsed as query grammar. */
public record CompactPageRequest(
        List<String> terms,
        String projectExact,
        String sessionId,
        String until,
        String before,
        Integer limit,
        Integer maxBytes) {}
