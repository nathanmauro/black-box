# Accept canonical UTC fractional instants on Python 3.9

## Reproduction and compatibility contract

The actual normalized enqueue CLI on supported Python 3.9.6 accepts only three or six
fractional digits. Canonical UTC observedAt values with 1, 2, 4, 5, 7, 8 or 9 digits exit zero
with `invalid_capture` before queue creation. This was discovered while refreshing NAT-6's
actual hook-to-Java outage/lost-acknowledgement/restart proof; the hook itself emits whole seconds.

Repair only canonical UTC `YYYY-MM-DDTHH:MM:SS[.fraction]Z` validation for 1–9 digits. Pad or
truncate a validation copy to six fractional digits for Python 3.9, validate calendar/time with
the existing parser, and preserve the original observedAt string in the event and every retry.
Reject canonical UTC precision above nine digits, malformed or impossible dates/times, and
missing timezones. Preserve already accepted legacy timezone formats on their existing path.
The existing Python calendar range (years 1–9999) stays unchanged; ISO/Java year 0000 is
unsupported here rather than an impossible ISO date. Do not change date ranges, wire bytes,
capture UUIDs, sanitizer version or hook normalization;
do not import evaluation code into the standalone hook. The analogous evaluation parser confirms
the compatibility approach, but no precision arithmetic is needed for this validation-only use.

Only the outbox, its tests, durable-capture guide and this plan are owned. No live queue,
provider, configuration, installed hook, Linear or Git changes. The coordinator owns review and
Git/publication. All proof uses temporary queues, fake loopback endpoints and an isolated
provider-disabled Java application built in scratch, never the shared checkout's target.

## Verification plan

Reproduce through the actual CLI first. Cover UTC fractions 1–9 (plus whole seconds), invalid
calendar/time/overprecision and missing timezone, unchanged legacy offsets, and immutable
nanosecond payloads across outage and committed-response-loss retries. Reuse the scratch real
Java fault-proxy fixture to prove restart returns the same canonical IDs, timestamp and one
receipt/event. Run focused and full outbox suites, actual hook smoke, and diff checks; freeze
results for coordinator review.

## Results and frozen handoff

Before the repair, the actual Python 3.9.6 CLI accepted only whole seconds and 3/6-digit
fractions; all other valid UTC precisions failed before acceptance. The new CLI test reproduced
seven failing fractional cases and its missing-row aggregate. Testing also demonstrated that
Python accepted an empty UTC fraction (`...00.Z`); the narrow UTC fractional shape now rejects
that malformed input. Independent review clarified that year 0000 is unsupported by the existing
Python calendar boundary, not an invalid ISO/Java instant; no year-range expansion was made.

Four focused regression methods now pass: actual CLI precisions 1–9 and whole seconds;
invalid/unsupported calendar/time/precision/timezone inputs; unchanged accepted legacy offsets;
and immutable nanosecond outage/lost-acknowledgement retries. The required hook script passes its
legacy normalization/lineage and fail-soft smoke plus the full 57-test outbox suite (30.961 seconds).
This includes all prior queue-contention, crash, privacy, TLS, replay and concurrency coverage.

A copied current-source Java application was built offline entirely in scratch with isolated
Maven/runtime configuration and providers disabled. Two fresh real integration scenarios passed:
actual opted-in Bash hook capture, and normalized CLI capture at
`2026-10-03T12:00:00.123456789Z`. Each queued during an outage, then a loopback fault proxy forwarded
the request to real Java and discarded the committed acknowledgement. After Java restart on the
same temporary SQLite database, retry returned the same capture/event/session IDs with
`replayed=true`, emptied the queue and left exactly one event, one receipt and session event count
one. Complete canonical event/session/receipt rows were unchanged by restart and retry; original
wire bytes were identical, and SQL plus HTTP readback retained the exact nanosecond instant.

The original hook path still generated and preserved a whole-second timestamp. Outage invocations
returned zero in 3.204 seconds (hook) and 3.147 seconds (CLI) with the approximate three-second
budget intact. Every owned Java process and fault-proxy thread was stopped. No production data,
provider, installed hook, configuration, Linear, shared target or Git state was changed. Source
and tests are frozen in the four owned paths for coordinator review, Git finish and publication.
