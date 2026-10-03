# Shared comparison delivery budgets (NAT-321)

## Selected scope

NAT-319 investigated a compact-search comparison backend. Source inspection showed that the
current endpoint cannot establish complete deterministic retrieval for the accepted corpus and
query domain. Its actual backend and server qualification were not built. NAT-320 owns the
separate additive product API prerequisite. This preparatory slice extracts the existing literal
ranking behind a small trusted, controller-owned backend interface and preserves the shared
delivery contract. No arbitrary backend/plugin loading, model runner, live data or API changes.

## Implementation

- `LiteralBackend` contains the existing ranking unchanged and remains the default.
- `Session` owns handoff delivery, excerpt rendering, envelope, six attempts and exact
  6,000-byte delivery / 24,000-byte session limits. Backend selection affects its name and ranking.
- Backends must provide an exact total and deterministic order or raise `BackendFailure`.
  Infrastructure failure closes the session without further model delivery; details stay in
  host-only accounting. Unknown totals were considered during planning and rejected.
- A trace pinned from the original implementation covers seven scripted sessions: ranking,
  Unicode folding, deep excerpt anchors, clipping, attempt/byte exhaustion, invalid requests,
  no handoff and empty history. Both default and explicit literal backends retain identical bytes.

## Why the compact backend waits

The canonical compact search path retrieves at most 200 candidates and returns at most 50 hits,
with additional byte fitting and no cursor. Counts below the candidate ceiling remain exact;
at the ceiling a complete total cannot be established. Event IDs are random UUIDs, so a tied
match group crossing the window can select different source items after fresh ingestion. Sorting
the returned prefix cannot recover missing items. Quoted terms cannot contain a literal double
quote, and unescaped SQL LIKE makes percent and underscore wildcard characters. Selectively
rejecting valid model queries would bias a paired comparison.

These conclusions came from source inspection of `CompactSearchService`, `EventQuery`,
`MemorySqlQueryAdapter`, `RecordingSqlStore` and `EventIngestService`, plus independent review.
No packaged compact-backend qualification or server-based reproduction is claimed in this slice.
The selected prerequisite is typed literal terms plus exact canonical keyset pagination.

## Verification

The Opus implementation lane passed all 101 benchmark tests on Python 3.9: the original 97
unchanged, plus golden byte parity, backend ordering/name, infrastructure close with no delivery
and unchanged literal accounting. The golden trace is 101,838 bytes and was pinned before the
refactor; mutation probes verify that ordering and output changes are detected.

The coordinator independently passed the same 101 tests on Python 3.9 and 3.14 before integrating
the previously merged chronology fixture. Documentation was reconciled to preserve all three
qualified fixtures and two qualified members of the 17-candidate inventory.

No Git writes, Maven builds, Java processes, servers, provider/model calls, live databases or
transcripts were used by the implementation lane. All results remain infrastructure-only;
NAT-7 is not cleared, and the actual compact backend remains open under NAT-319.

After integration, all 125 combined benchmark tests passed on Python 3.9. Independent review
compared the entire seven-session trace against the pre-seam source and confirmed 101,838
identical bytes (SHA-256 `5478ad77282bc68c205bcadbd11e10b45b0b9d1fc475ed92c0bf154bff6fe2ac`).
The review found no production seam defect and corrected documentation: bounded ungrouped
candidate counts are exact below the 200-candidate ceiling, while the hit window still prevents
complete match-ID traversal. No server qualification is implied.
