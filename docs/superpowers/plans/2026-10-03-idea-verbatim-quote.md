# Preserve nonblank Idea quotation whitespace

## Reproduced loss

Actual HTTP and MCP captures submitted an indented, multiline quote ending in spaces and a
newline. Both metadata and rendered event text stripped the leading indentation and trailing
whitespace. Event reads, Idea listing and MCP recallIdea returned that altered value. A later
nonblank quote was stripped the same way. Omitted and blank-only revisions correctly inherited
the preceding quote, and the original event remained unchanged. The packaged fixture's owning
sources matched current main; it used private SQLite, an ephemeral loopback server, disabled
providers and no transcript files.

## Narrow contract

Preserve raw nonblank Idea quote strings in capture metadata and inside the rendered quotation.
Keep blank/omitted values absent, so existing revision inheritance remains intact. New nonblank
quotes still replace the collapsed value without rewriting earlier events. Do not alter shared
formatting helpers, other capture kinds, redaction, scalar limits or canonical text limits.
Previously stripped records cannot be recovered from stored data.

## Verification

Add actual HTTP/MCP regression coverage for indentation, trailing newlines, event/list/detail
round trips, omitted/null/empty/blank revision inheritance, replacement and original immutability.
Check that secret redaction and the distinct canonical-text/metadata-scalar limits remain active.
Run those cases before the repair, then the focused capture/Idea suite, full relevant backend
suite/module checks, scoped Palantir and whitespace checks. No frontend changes are needed for
a loss that occurs before storage. The coordinator owns Git, publication and deployment; worker
writes and tests remain confined to this checkout and disposable fixtures.

## Verified result

The four new real HTTP/MCP cases failed before the repair because indentation and trailing
whitespace were removed. After the two quote-only expression changes, the focused capture/Idea
suite passed all 53 tests. The same cases verified omitted, null, empty and blank-only revision
inheritance, a new nonblank replacement, exact original-event immutability, secret redaction and
the separate 20,000-character event-text and 50,000-character metadata-scalar limits.

Full `mvn test` passed: 956 tests, zero failures/errors, 42 optional skips (38 PostgreSQL cases
without a configured fixture and four other optional checks). All nine architecture/module tests
passed. Scoped Palantir apply/check and `git diff --check` passed. Independent source/test/doc
review found no actionable issue. No live service, provider, private configuration, frontend or
existing stored records changed. PostgreSQL behavior was not separately exercised for this
shared pre-persistence formatting change. The coordinator owns the final Git and publication steps.
