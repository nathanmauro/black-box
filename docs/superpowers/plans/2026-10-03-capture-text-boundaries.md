# Preserve complete characters at capture limits

## Reproduction and scope

With the default 20,000 UTF-16-unit text budget, capturing 19,999 ASCII characters followed
by an emoji and a tail splits the emoji. An isolated real SQLite recorder returned `?` from the
saved boundary followed by the existing truncation marker. No live store or provider was used.
The redaction scalar scan ceiling uses the same unsafe cut at 50,000 units.

Preserve both budgets and existing markers. Move a truncation boundary back one UTF-16 unit only
when it splits a valid surrogate pair. Do not normalize other text, redesign limits, change
redaction rules, or touch optional event publication. Root owns Git and integration.

## Verification

- Reproduce the current failure through real HTTP capture plus canonical SQLite reads.
- Cover supplementary characters immediately before, across and after each boundary, exact-limit
  text, nested metadata/tool input/tool output scalars, and export redaction.
- Run focused ingestion/redaction and capture HTTP tests, scoped Java formatting, and diff checks.
- Record completed verification before freezing the checkout for root review.

## Completed verification and handoff

The initial focused run failed exactly the three straddling-boundary regressions: canonical event
text read through HTTP/SQLite, nested scalar HTTP/SQLite content, and direct redaction/export.
Adjacent boundaries and exact-limit controls passed before the fix.

After the narrow correction, all 57 tests across `RedactionServiceTest`,
`CaptureTextBoundaryHttpTest`, `EventIngestServiceTest`, `StructuredCaptureServiceTest`,
`IdempotentCaptureHttpTest` and `StructuredCaptureHttpTest` passed. This includes seven actual HTTP
capture/read journeys using disposable SQLite storage. Nested metadata, tool input and tool output
retain the exact expected marker and whole-character prefix. The scoped Palantir/AST formatter and
its check passed; whitespace validation passed. The connected IDE was on a different project, so
verification used Maven with offline dependencies, an allowlisted environment, temporary home and
storage, and disabled providers/judge.

Codex changed only event-text truncation, redaction scalar clipping, dedicated regression tests,
and these docs. The optional publication and structured-key redaction changes belong to separate
lanes. No live database, running product service, provider, Git ref or remote was changed. Root owns
review, commit and integration; next action is to combine the independent hunks and run the normal
integration gate. This correction does not rewrite previously stored text.

Coordinator acceptance: reviewed the exact boundary predicates, stored/HTTP assertions and limit
contract. After integrating Stream recovery, the 21 direct HTTP/redaction boundary cases passed
without skips. Integrated main through PR63 before publication; CI supplies the combined suite.
