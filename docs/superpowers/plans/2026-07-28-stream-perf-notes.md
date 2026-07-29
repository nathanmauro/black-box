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
at 500 rows initially measured ~164 ms against the < 100 ms budget. The measurement includes
Playwright click dispatch + polling overhead, so the felt interaction was somewhat faster,
but the number as measured missed.

## Containment follow-up, 2026-07-29

The expanded panel now uses `content-visibility: auto` with a 266 px intrinsic block-size
estimate. This keeps every expanded row in the DOM and accessible while deferring offscreen
layout and paint work.

Live verification after deployment, using the same conservative Playwright click-through-last-row
method at 1280×800 with exactly 500 rows:

- raw expand-all runs: **83 / 85 / 91 ms**
- median: **85 ms** (budget: < 100 ms — **met**)
- expanded rows: **500 / 500** on every run
- final row: scrollable and visibly rendered on every run

The containment fix resolves the finding without lowering `MAX_ROWS`.
