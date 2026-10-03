# Preserve Projection evidence in Recall

## Problem and safety contract

An explicit Projection recall currently returns only the first path's title/confidence and the
shared basis, omitting alternative futures and their descriptions while reporting no truncation.
The canonical event and trajectory retain the paths. Reproduce through actual REST and MCP recall
against a disposable SQLite fixture before changing the projection.

Reuse the existing optional `RecalledItem.body` for the exact canonical stored Projection text.
That rendered text identifies projected futures and includes each path/description/confidence plus
basis, subject to the existing ingest redaction and text-length limit. Do not reconstruct it from
metadata or alter canonical event bytes. The canonical event metadata may retain longer path
content than its capped text; test and document that distinction.

Keep the established headline/confidence fields compatible: they summarize the first listed path,
not a selected decision or an aggregate confidence across futures. Consumers must use the body and
kind rather than treating that summary as the whole projection. Preserve source IDs, timestamps,
mode, default kinds, and lexical-only Projection retrieval. No new UI kind/filter or semantic
indexing, schema migration, provider/live database/service mutation, or worker Git publication.

## Acceptance

- Capture two contrasting futures and reproduce missing body independently via real HTTP and MCP.
- Preserve exact ordinary text, descriptions, path confidences, basis and provenance in both APIs.
- Retain single-path/missing-basis compatibility and default kind exclusion.
- Oversized MCP body is budgeted with visible omission and truncation, without changing canonical
  text/metadata or HTTP evidence. Verify surrogate-safe cuts and unchanged source identity.
- A capture exceeding the ingest text cap still exposes its exact capped text, with stored
  metadata reachable through the source event; presentation truncation is a separate signal.
- Run focused integration/budget and REST/MCP/wire contracts, relevant full backend suite, scoped
  Palantir formatting and diff checks. Coordinator reviews and owns all Git/publication.

## Verified result

- Before the production change, two independent actual REST/MCP cases failed because the body was
  absent while the source event held both contrasting futures and their conditions.
- A single ContextService expression now includes exact Projection text in the existing body
  field. First-path headline/confidence compatibility remains explicit in DTO and consumer docs.
- The six focused integration/budget/REST/MCP/wire contract classes passed 46 tests. Full backend
  `mvn -q test` passed 620 tests with zero failures/errors and 27 skips: 23 opt-in PostgreSQL cases
  (fixture stopped) and four existing optional checks.
- Review corrected stale Observation-only budget/type comments and the plan's metadata wording.
  The affected real HTTP/MCP, callback-budget and MCP-schema classes then passed all 31 tests.
  No schema shape or generated fixture changed; no UI behavior or bundle changed.
- Tests prove normal full-body delivery, missing-basis behavior, default-kind exclusion,
  surrogate-safe MCP clipping with precise omission count/source identity, unchanged canonical
  evidence, and capture-time text clipping distinct from fuller stored metadata. Stored metadata
  also remains subject to its existing scalar redaction/length limits.
- Scoped Palantir formatting, the API-comment Prettier check and `git diff --check` passed.

Codex handoff: `codex/projection-recall-evidence`, nine owned source/test/doc paths left uncommitted
for coordinator review and Git publication. All fixture test processes finished; no live recorder,
database, provider or runtime was changed. No new semantic indexing, default-kind expansion or
Projection UI filter was introduced. The coordinator owns integration with newer main and the
next exact-path commit/PR/merge.

Coordinator acceptance: preserved both capture-acknowledgement and Projection documentation
while integrating main through PR65. All 40 actual structured HTTP/MCP, context-loop and wire
contract tests passed together, with no skips. Fresh source review found no remaining issues.
