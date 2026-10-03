# NAT-306: bounded saved-braid discovery for agents

## Contract

Add one project-owned `findBraids(id?, query?, sessionId?, limit?, before?, maxBytes?)` MCP tool.
Only persisted `artifact_kind='braid'` artifacts qualify, across both project-owned and unassigned
storage. Ordinary melds and captured fallback Braid events are excluded. Recent discovery needs no
selector. Query is a trimmed, nonblank, case-sensitive literal title/body substring, at most 1024
UTF-16 units; U+0000 and isolated surrogate selectors are rejected before SQL for portability.
Decoded cursor IDs also reject U+0000. `%`, `_`, quotes and Unicode have no special query syntax. Exact internal session
membership combines with query using AND/EXISTS, including historical orphan membership. Exact
artifact ID excludes query/session/cursor combinations and returns not_found for missing/non-braids.
Limit defaults to 10, accepted 1–20. Existing REST APIs and their unassigned cursor stay unchanged.

Use a separate versioned precise createdAt+ID cursor bound to normalized selectors and fixed
all-saved-braid coverage. Limit and byte budget may vary across pages. Filters apply before LIMIT.
Add only required chronology/membership indexes after existing schema initialization/upgrade.

## Export and budget safety

Return artifact identity/kind/source type, save-time createdAt, project/unassigned ownership with
nullable projectKey/canonicalKey, title/body, caller-declared provider/model, ordered internal session
references with save_snapshot/current_session/
unavailable provenance basis, and the existing REST detailPath. Omit opaque caller metadata.
Use the public recording ExportRedactor for all exported free text/provenance independently of
capture settings. Do not rewrite persistence. Redacted or clipped ownership text must not leak its
original through an encoded project key; retain explicit project/unassigned ownership classification.

Budget the exact JSON bytes produced by the tool converter: default 24000, range 2048–64000 UTF-8
bytes; MCP framing is additional. Preserve IDs and ordering, clip only complete Unicode code points,
and disclose redactor transformations/scan limits separately from additional byte-budget truncation.
Clip body first, retaining title/ownership/provenance when their envelope fits; otherwise clip other
free text too. Prefer a reference-only first hit to skipping it. Continuation
always anchors the last returned artifact. If even the first identity/ordered-reference envelope
cannot fit, return a bounded budget_exceeded error with no advancement. Existing cursor behavior,
semantic retrieval, embeddings, gateway, UI and provider calls are out of scope.

## Verification and ownership

First reproduce the absent tool through real HTTP MCP initialize/tools-list in private SQLite.
Then REST seed/save→MCP list/call on SQLite and a guarded own-random-schema PostgreSQL fixture.
Cover kinds/ownership, exact mode, literal query/session filters, cursor binding/ties/nanoseconds,
legacy/deleted provenance, oversized content/identities, UTF-8 budgets, Unicode boundaries,
mandatory outbound secret redaction, and unchanged raw storage. Regenerate MCP/wire contracts
with contracts.update; REST mappings stay unchanged. Run focused then full backend/module tests,
scoped Palantir/diff checks, and the new PostgreSQL no-skip CI assertion.

This isolated checkout is the worker's sole writable source scope. Root owns all Git/publication,
issue updates and fixture cleanup. No user database, service 8766, port 8799, live hook/configuration,
provider or external system is touched. A fresh helper review could not start because the agent
thread limit was reached; coordinator review remains required before publication.

## Observed development evidence

- Actual MCP initialize/tools-list reproduced the missing tool before implementation.
- Both databases now pass 14 real HTTP/MCP cases each; two cursor tests also pass. The fixtures
  exercise REST seed/save, recent/exact/filtered discovery, limits, literal case-sensitive search,
  orphan/snapshot provenance, restart/index initialization, full Instant range and nanosecond ties,
  stable continuation while budgets vary, reference-only and irreducible outputs, Unicode-safe
  clipping, redaction, scan-limit disclosure and unchanged persisted bytes.
- The ingestion-disabled fixture setting is explicit and asserted on IngestionProperties, so the
  proof remains valid after independent ingestion redaction changes. No real settings changed.
- A real NUL-selector probe returned dialect-inconsistent results (SQLite ok, PostgreSQL SQL error).
  Approved input validation now rejects NUL and isolated surrogates before SQL; all nine selector
  variants run through real MCP on both databases without echoing the raw input.
- A crafted valid cursor containing a NUL artifact ID reproduced the same dialect mismatch. The
  parser now rejects that decoded identity, covered by real MCP on both databases and a unit control.
- Review identified avoidable provenance loss under uniform clipping. A 4096-byte fixture reproduced
  a lost projectKey even though the full non-body envelope fitted. Body-first clipping now preserves
  that envelope on both dialects; mandatory redaction still runs before any clipping.
- MCP/wire fixtures were regenerated by contracts.update. Existing REST mappings/cursors remain
  unchanged. Final native `mvn test` passed 1071 tests with zero failures/errors and four unrelated
  skips, including 14 discovery cases on each database and all architecture/module checks. The
  PostgreSQL consumer, restore and migration suites all ran without skips. Scoped Palantir and
  `git diff --check` passed; the exact CI no-skip report assertion was also exercised locally.
- Coordinator/Opus source review found the cursor and budget improvements described above; both
  are reproduced and verified. This is a frozen source handoff for coordinator acceptance and Git.
  No frontend, restricted gateway or provider behavior was added or verified. All owned application
  contexts and random PostgreSQL schemas were closed by fixtures; the shared disposable server
  remains coordinator-owned for the other independent lane. No live deployment occurred.

## Coordinator integration acceptance

- Integrated the independently merged saved-artifact ingestion-redaction change. The combined
  backend suite passed 1,091 tests with zero failures/errors and four unrelated optional skips;
  both discovery suites and both ingestion-redaction HTTP suites ran without skips. The exact CI
  report guard passed with both new PostgreSQL suite requirements preserved.
- Independent native contract review corrected two wire descriptions: ownership is explicitly
  project/unassigned, and transformed/clipped project keys are JSON null. Root and Opus accepted
  production behavior after the reproduced cursor and budget fixes.
- Updated the packaged MCP regression to thirteen tools, including findBraids. Frontend lint,
  formatting and types passed with zero errors and the existing 68 warnings. Normal packaging
  passed; eight packaged journeys passed with zero retries, covering the MCP surface and retired
  API absence, saved-braid reading/navigation, and numeric transcript search/pagination.
- The isolated browser project/database were removed and port 8799 released. The protected 8766
  listener remained unchanged; its database identity was unavailable, so no production database
  identity or row-count claim is made. No installed service was deployed or restarted.
