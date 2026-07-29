# Stream perf notes — slice 1 (spec §9 budget)

Method: Playwright chromium against the live :8766 service, `domcontentloaded` +
first `.stream-row` visible; feed grown to MAX_ROWS via Load more; median of 3 runs.

## Baseline (before presenters/expand-toggle), 2026-07-28
- first stream row visible: 6081 ms
- rows loaded: 500
- single-row expand: 29 ms

## After slice 1 (filled in by Task 16)
- first stream row visible: <N> ms (budget: no regression)
- expand-all toggle at 500 rows: <N> ms (budget: < 100ms)
- single-row expand: <N> ms
