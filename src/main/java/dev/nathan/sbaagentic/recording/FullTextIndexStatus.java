package dev.nathan.sbaagentic.recording;

/**
 * Parity between {@code agent_events} and its FTS5 index.
 *
 * @param status {@code in_sync}, {@code backfilling}, {@code drift}, {@code unavailable}, or
 *     {@code not_applicable} (a non-SQLite backend)
 * @param eventRows canonical event rows
 * @param indexedRows FTS rows, or {@code null} when the index cannot be read
 * @param backfillLastRowid last rowid the resumable backfill has covered, when known
 * @param backfillTargetRowid rowid the backfill must reach, when known
 * @param backfillComplete whether MATCH may serve queries
 */
public record FullTextIndexStatus(
        String status,
        long eventRows,
        Long indexedRows,
        Long backfillLastRowid,
        Long backfillTargetRowid,
        boolean backfillComplete) {}
