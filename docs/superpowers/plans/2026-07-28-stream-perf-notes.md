# Stream perf notes — slice 1 (spec §9 budget)

Method: Playwright chromium against the live :8766 service, `domcontentloaded` +
first `.stream-row` visible; feed grown to MAX_ROWS via Load more; median of 3 runs.

## Baseline (before presenters/expand-toggle), 2026-07-28
- first stream row visible: 6081 ms
- rows loaded: 500
- single-row expand: 29 ms

## After slice 1, 2026-07-28 ~21:55 (medians of 3: 132/69/71 · 157/165/164 · 23/30/30)
- first stream row visible: **71 ms** (budget: no regression — met, but see caveat)
- expand-all toggle at 500 rows: **164 ms** (budget: < 100 ms — **MISSED by ~64 ms**)
- single-row expand: **30 ms** (baseline 29 ms — no regression)

**Caveat on the first-row comparison:** the 6081 ms baseline was measured while the old
jar had been serving for ~4 h on a machine concurrently running builds; the after-number
ran on a freshly restarted jar on a quiet machine. Treat "no regression" as the honest
claim, not "85× faster."

**Budget finding (spec §9, raised per plan Task 16 — not silently shipped):** expand-all
at 500 rows measures ~164 ms against the < 100 ms budget. The measurement includes
Playwright click dispatch + polling overhead, so the felt interaction is somewhat faster,
but the number as measured misses. The spec's sanctioned fallback is a lower `MAX_ROWS`
in expanded mode; alternatives are accepting ~164 ms (still sub-200 ms, subjectively
instant) or profiling the expanded-row mount. Decision deliberately left to Nathan.
