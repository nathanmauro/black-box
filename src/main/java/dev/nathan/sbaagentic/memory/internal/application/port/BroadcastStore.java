package dev.nathan.sbaagentic.memory.internal.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for the per-project broadcast set. Rows are soft-deleted, never removed. */
public interface BroadcastStore {

    /** Which rows a read may return, relative to the caller's {@code now}. */
    enum Visibility {
        /** Uncleared and unexpired: what recall shows. */
        ACTIVE,
        /** Uncleared, plus expired tentacles, which only an explicit clear closes. */
        OPEN,
        /** Every row, cleared history included. */
        ALL
    }

    /**
     * Inserts the broadcast, or replaces the mutable fields of the uncleared row with the same
     * project scope and key. The existing row keeps its id and {@code createdAt}.
     */
    StoredBroadcast upsert(StoredBroadcast candidate);

    /** Clears the uncleared row for the scope and key; empty when nothing was open. */
    Optional<StoredBroadcast> clear(
            String projectScope, String key, Instant clearedAt, String clearedBy, String reason);

    /** The most recently cleared row for the scope and key, for idempotent clear responses. */
    Optional<StoredBroadcast> latestCleared(String projectScope, String key);

    /**
     * Rows newest-updated first. A null {@code projectScopes} means every project; a null
     * {@code kind} means every kind.
     */
    List<StoredBroadcast> find(
            Collection<String> projectScopes, String kind, Visibility visibility, Instant now, int limit);

    record StoredBroadcast(
            String id,
            String projectScope,
            String key,
            String kind,
            String message,
            String severity,
            String source,
            String clientSessionId,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            Instant followUpAt,
            Instant clearedAt,
            String clearedBy,
            String clearReason) {}
}
