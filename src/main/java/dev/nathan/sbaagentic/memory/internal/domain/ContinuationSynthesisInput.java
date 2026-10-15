package dev.nathan.sbaagentic.memory.internal.domain;

import java.util.List;

/** The model's user message and the handles it may cite, in the order they were assigned. */
public record ContinuationSynthesisInput(String user, List<ContinuationHandle> handles) {

    public ContinuationSynthesisInput {
        handles = List.copyOf(handles);
    }
}
