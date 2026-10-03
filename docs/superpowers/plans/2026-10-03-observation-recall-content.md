# Preserve Observation content in Recall

## Reproduction and contract

A multiline Observation is captured intact, but the Recall projection currently returns only its
first line with `truncated: false`. REST, MCP and copied resume context therefore omit substantive
lines without an omission notice. The reproduction used actual capture/recall adapters with an
isolated in-memory recorder; no live database or provider was accessed.

Add an optional `body` field only for Observation recall items, preserving the existing short
headline, source identity, kind semantics and canonical capture. Absent bodies remain omitted from
JSON, and legacy callers/items remain compatible. No data migration is needed.

MCP counts the body in its existing character budget and trims the body before rationale/headline,
using the existing visible omission suffix and result-level truncation flag. Frontend Recall uses
its existing expandable reader, and copied context uses body instead of duplicating the headline.
The clipboard budget and complete evidence links remain intact.

## Verification

- Reproduce with a multiline real HTTP/MCP capture and recall fixture before implementing.
- Test exact ordinary body round-trip, omission for other kinds and legacy items, oversized MCP
  body truncation/count/provenance, and copied-context truncation accounting.
- Test accessible reader expansion, full clipboard text and exact source navigation through the
  packaged application with providers disabled and disposable storage.
- Regenerate wire/MCP contracts through the existing generators and rebuild committed UI assets.
- Run focused/backend contracts, full frontend/check/build, scoped Java formatting and diff checks.

Coordinator owns review and all Git/publication. Only this isolated checkout is modified.

## Completed verification

- The new real HTTP/MCP regression failed against the original projection: the returned body was
  absent while the captured multiline evidence remained intact. The corrected round-trip passes.
- 69 relevant backend tests passed across the real HTTP/MCP fixture, callback budget tests,
  context loop, hybrid recall, project continuity and REST/MCP/wire contracts. Contract snapshots
  were regenerated with `-Dcontracts.update=true`; only the optional wire body changed.
- 21 focused UI tests and the full 653-test frontend suite passed. Frontend lint/format/type checks,
  scoped Palantir formatting, and the production frontend build passed.
- Two packaged Chromium journeys passed at 1440px and 390px: keyboard expansion/collapse, hidden
  preview bytes, preserved line breaks, complete ordinary clipboard text, explicit oversized-copy
  truncation, exact source navigation and no horizontal overflow. Both screenshots were reviewed.
- Fresh read-only review caught missing whitespace preservation in the new Recall wrapper; a
  scoped style and computed-style browser assertion now cover it. No remaining review findings.
- The packaged runtime used only disposable fixture storage with model/provider paths disabled.
  Fixtures were removed, port 8799 released, and the protected port 8766 listener stayed unchanged.
  The production database was not discovered or queried, so no production-row-count claim is made.

`body` contains the canonical stored Observation text after existing ingest redaction/length limits.
Those limits, capture storage, other kind projections and legacy omitted-body behavior are unchanged.
MCP/copy character bounds count UTF-16 units and preserve surrogate pairs when cutting text.

Codex handoff: dirty verified unit on `codex/observation-recall-content`; source, focused regressions,
contract fixture, docs and generated assets only. No Git commit, publication, live capture or provider
call was made. The coordinator's next action is final review and integration onto current main.

## Combined acceptance

The coordinator integrated main through PR60 and regenerated the combined assets. All 667
frontend tests and check/build gates passed. Ten packaged Chromium journeys passed: Observation
read/copy/source navigation and project continuity at desktop/narrow widths, plus all six native
Stream recovery paths. Fixture storage and the port 8799 listener were cleaned; the protected
local service PID stayed unchanged. No deployment was performed.
