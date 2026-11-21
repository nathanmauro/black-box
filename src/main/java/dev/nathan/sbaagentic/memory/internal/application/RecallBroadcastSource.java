package dev.nathan.sbaagentic.memory.internal.application;

import dev.nathan.sbaagentic.memory.internal.domain.BroadcastDigest;
import java.util.List;

/** Supplies the bounded live-broadcast digest that recall attaches for a resolved project. */
@FunctionalInterface
public interface RecallBroadcastSource {

    BroadcastDigest digestFor(List<String> projectScopes);

    static RecallBroadcastSource none() {

        return projectScopes -> BroadcastDigest.EMPTY;
    }
}
