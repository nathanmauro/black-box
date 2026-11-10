# Transcript duplicate lookup

The transcript endpoint merges recorded events with optional local JSONL messages. Before this
change, each eligible file message scanned the complete recorded conversation and repeatedly
normalized both texts. A request with N recorded messages and M file messages could perform N × M
comparisons, even when the file reader had already cached the JSONL.

`RecordedConversationIndex` now groups recorded messages by normalized role and text once per
request. Each group indexes its non-null turn IDs and ordered timestamps. A file message matches
the same turn regardless of time, or the nearest timestamp within the inclusive two-minute window.
Recorded role fallback, text normalization, exact transcript-role comparison, pagination, and
preference for canonical events remain unchanged. The index is discarded after the request.

Human-only requests still read JSONL availability/completeness, but no longer fetch the complete
recorded conversation solely for deduplication: that view never includes file messages.

## Synthetic service measurement

Measured November 10, 2026 using Java 21 and a 512 MiB heap. The opt-in
`SessionTranscriptBenchmark` invokes the actual service with an in-memory catalog fixture and
already-loaded transcript messages. Each shape has N recorded and N file messages, with short
texts containing case/whitespace normalization work. Each measurement is the median of three
calls after one warmup call. The duplicate-heavy shape matches 80% of file messages to recorded
events; the other shape has no duplicates.

| Messages per source | Shape          |  Before (ms) | After (ms) |
| ------------------- | -------------- | -----------: | ---------: |
| 500                 | Unique         |      125.031 |      2.864 |
| 500                 | 80% duplicates |       55.616 |      2.890 |
| 2,000               | Unique         |    2,021.247 |     10.233 |
| 2,000               | 80% duplicates |    1,230.655 |      6.230 |
| 10,000              | Unique         | Not measured |     26.669 |
| 10,000              | 80% duplicates | Not measured |     16.374 |

These numbers measure service-side merge work, not database, file parsing, network, or browser
latency. Other local development work was active, so small timing differences are not significant.
The important change is avoiding repeated normalization across the Cartesian comparison. The
index adds O(N) retained entries/text per request; repeated text groups incur ordered-set work.
There is no cross-request cache or invalidation requirement.

## Reproduce

Run with a Java 21 `JAVA_HOME`:

```bash
mvn -Dtest=SessionTranscriptBenchmark \
  -Dsba.transcript.benchmark=true \
  -Dsba.transcript.benchmark.sizes=500,2000,10000 \
  -DargLine=-Xmx512m test
```

The benchmark is opt-in and makes no model calls. To repeat the before measurement, create a
disposable worktree at `9c042984`, copy the benchmark source into the same test package there, and
run the command with `-Dsba.transcript.benchmark.sizes=500,2000`. The 10,000-message baseline was
not measured; no before/after claim is made for that size.

Correctness checks are separate from timing:

```bash
mvn -Dtest=SessionTranscriptServiceTest,RecordedConversationIndexTest test
```

They cover same-turn matches, inclusive time boundaries and nanoseconds just outside them,
repeated text across a session, legacy role fallback, null/blank fields, instant bounds, and a
deterministic comparison with the previous pairwise rule. A catalog interaction assertion verifies
that a human-only request with available JSONL does not load all recorded conversation events.

A packaged HTTP smoke used a temporary SQLite database and synthetic Claude JSONL: canonical
duplicates appeared once, a message one nanosecond outside the two-minute window remained visible,
single-item cursor pages matched the full response, and the human-only response retained only the
recorded human turn. The disposable server was stopped after verification.
