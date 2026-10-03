# Offline handoff and literal-search comparator (NAT-315)

## Authorized scope and completion contract

Implement one bounded, deterministic, offline adapter for the proposed ordinary
latest-handoff/literal-search arm in
[the continuation comparison protocol](../../continuation-comparison-protocol.md). Standard
library only, Python 3.9 compatible, synthetic fixtures only.

Owned paths: `scripts/benchmarks/blackbox_memory/history_search.py`,
`scripts/benchmarks/blackbox_memory/test_history_search.py`,
`docs/continuation-comparison-protocol.md`, `docs/memory-benchmark.md` and this plan.
Existing runners (`benchmark.py`, `continuation.py`), fixture qualifiers, CI, dependencies,
model tools and historical results/gates are not changed. Nothing is wired into a model loop.
No live history, transcripts, databases, credentials, servers, providers or network are read.
Git, review, CI, PR and issue updates belong to the coordinating root session.

Out of scope and still outstanding: the Black Box backend for the same envelope, an authentic
corpus builder, registration/adjudication, difficulty and accepted-action studies. The adapter
establishes infrastructure only and supports no efficacy claim.

## Design frozen before verification

- **Corpus input.** The controller selects one manifest file and supplies its exact SHA-256.
  The manifest names a sibling items file and its SHA-256, the item count, a project plus session
  allowlist, an inclusive cutoff, declared exclusions with reasons and a provenance label.
  Strict UTF-8, strict JSON (duplicate keys and non-finite numbers rejected), closed schemas,
  size bounds, unique IDs, valid metadata characters and timestamp syntax are validated before
  any delivery. Tampered hashes, count drift, out-of-scope sessions/projects, post-cutoff dates
  or an item that appears in the declared exclusions reject the whole corpus fail-closed.
  A matching hash and in-range declared dates do not prove the item was authentically available
  at the cutoff; chronology stays a registration requirement.
- **Timestamps.** `YYYY-MM-DDTHH:MM:SS[.fraction]` with `Z` or `±HH:MM`, 1–9 fractional
  digits. Converted to exact integer UTC nanoseconds (pre-1970 dates included) so fractional
  ordering is never rounded. `recorded_at` is compared with the cutoff only when present.
  Original strings are delivered verbatim.
- **Handoff.** Delivered once by `start()` before any search: the eligible `handoff` item with the
  greatest `observed_at`, ties broken by smallest item ID. At most 6,000 bytes, charged to the
  24,000-byte total, not a search attempt. Status `no_history` for an empty corpus,
  `no_handoff` for a corpus with items but no handoff, otherwise `ok`.
- **Literal search.** Per-character `str.casefold()`; whitespace (`str.isspace`) runs collapse to
  one space. Query terms are the whitespace-separated folded tokens (punctuation retained, so
  filenames and identifiers stay whole), de-duplicated in first-seen order. A term matches when
  it is a substring of the folded text or folded source reference. The exact full query is the
  folded token sequence joined by single spaces. Rank: exact full-query match first, then number
  of distinct matched terms, then newer `observed_at`, then smaller ID. At most 20 ranked results
  are rendered; matches beyond that ceiling and results dropped for bytes are both disclosed in
  `omitted_results` (results + omitted = total matches). Items are never merged:
  identical text from distinct items yields distinct results. Stale or contradictory items stay
  in the ranking with their dates.
- **Excerpts.** Offsets are original-text code-point offsets. A fold-to-original index map widens
  any match to whole original characters, so expansions such as `ß`→`ss` or `İ`→`i̇` keep
  correct offsets. Anchor: first exact match in text, else earliest term occurrence in text,
  else none (source-reference-only match, excerpt from offset 0). Window: 100 code points
  before the anchor, 640 code points total (extended to cover the anchor).
- **Envelope.** One response schema shared by handoff, search and terminal deliveries
  (`blackbox.history-delivery/v1`) with backend, corpus, sequence, type, status, attempt
  counters, pre-delivery budget, echoed folded terms, total matches, results, omissions,
  truncation and error. Payloads never contain their own byte count; exact sizes are host-only
  accounting.
- **Budget.** Six search attempts, invalid requests included. Each delivery is at most
  `min(6000, remaining)` bytes of compact UTF-8 JSON plus newline; the session total is at most
  24,000 bytes. Results are added in rank order; the first result that does not fit has its
  excerpt clipped at a code-point boundary (binary search), later results are omitted and counted,
  and `truncated` is disclosed. Requests after the sixth attempt receive one metered terminal
  `attempt_limit` response, then delivery closes. A request whose minimal response cannot fit gets
  a metered terminal `byte_budget` response if that fits, else delivery closes silently. Closed
  sessions emit nothing further. Corpus validation failures happen before any session and emit no
  evidence.
- **Input bounds.** Manifest 64 KiB, items file 8 MiB, 10,000 items, 64 KiB text, 1 KiB source
  reference; request line 4 KiB, query 512 bytes, 16 terms.
- **CLI.** `history_search.py validate` (host summary) and `history_search.py serve` (initial
  handoff line, then one JSONL request per line in the same process; host accounting on stderr).
  An offline demonstration, not a deployed tool or tamper-resistant sandbox. A future model runner
  must own the single session, prevent restart/reset and deny any other history access.

## Verification plan

1. Unit tests: schema/hash/scope/cutoff/exclusion/UTF-8/duplicate/bound failures, fractional
   ordering, no-history vs no-handoff, ranking/ties, filename/identifier queries, casefold offset
   expansion, identical text with distinct provenance, budget clipping at Unicode boundaries,
   repeat-query charging, attempt exhaustion, byte exhaustion and silent close.
2. Subprocess CLI journeys reading real stdout bytes: every line is valid JSON, per-line and
   cumulative byte ceilings hold, and stdout totals equal host accounting.
3. All nearby benchmark tests on Python 3.9, `py_compile` and `git diff --check`; confirm existing
   runners/results are byte-for-byte unchanged.

## Observed results

- Preflight: a private temporary file was written and deleted in the checkout;
  `/usr/bin/python3` is Python 3.9.6.
- `test_history_search.py`: 22 tests pass on Python 3.9.6, including five subprocess CLI
  journeys that compare actual stdout bytes with host accounting.
- Two first-run failures were wrong test expectations, not adapter defects: `08:00:00-04:00` equals
  the cutoff second (before its `.5` fraction), and `trass` correctly maps to original `traß`.
- All `scripts/benchmarks/blackbox_memory` tests (91) and `scripts/evaluation` tests (20) pass on
  Python 3.9.6. Existing runners, fixtures, CI and results are unchanged.
- Root review (round 2) found two defects, both fixed with regressions: a pre-epoch item without
  `recorded_at` was rejected as `after_cutoff` because a missing `recorded_at` was treated as epoch
  zero; and matches beyond the 20-result ceiling were not counted in `omitted_results` (31 matches
  reported 19 results, 1 omitted). Now: pre-epoch, explicit epoch-zero and negative fractional
  dates are covered; the 31-match CLI repro reports 19 results and 12 omitted, and a compact 25-match
  fixture delivers all 20 capped results unclipped with 5 omitted. Every successful CLI search delivery checks
  results + omitted = total matches. 26 focused tests, 95 benchmark tests and 20 evaluation tests
  pass on Python 3.9.6.
- A manual synthetic CLI journey delivered 8 lines / 5,940 bytes (equal to host accounting), charged
  a repeated query twice, closed on `attempt_limit`, and a wrong manifest hash exited 2 with
  empty stdout.

## Coordinator acceptance

Root independently passed all 95 benchmark tests and all 20 evaluation tests on Python 3.9,
then exercised both reported corrections through the actual CLI. The pre-epoch corpus delivered
its matching evidence, and the 31-match case disclosed 19 returned plus 12 omitted results.
Actual stdout bytes matched host accounting and stayed within both caps. The evaluation suite
needed a scoped rerun after the sandbox blocked its private loopback fixtures; it passed without
source or assertion changes. Fresh review accepted the final code and 78 additional synthetic
budget boundary checks. Only two prose qualifiers were tightened after the frozen handoff.
No model comparison, live corpus, provider, deployment or usefulness result is claimed.
