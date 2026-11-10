# Dashboard Activity Range

NAT-624 avoids scanning historical events for the SQLite dashboard's recent-activity chart.
The other dashboard counts and the PostgreSQL query are unchanged.

## Change

The original query wraps `observed_at` in `date()` in its filters, preventing a bounded search of
`idx_agent_events_observed`. Add coarse raw timestamp bounds while retaining the exact date filters.
The coarse range includes one extra day at each end so timezone offsets and fractional seconds
near midnight retain SQLite's original date buckets. Recorded timestamps use ISO UTC text; no
schema change or additional index is needed.

On the bundled SQLite 3.45.3 driver, `EXPLAIN QUERY PLAN` changes from a covering index scan to:

```text
SEARCH agent_events USING COVERING INDEX idx_agent_events_observed (observed_at>? AND observed_at<?)
```

## Measurement

A disposable database contains 300,000 events spread over 3,000 days, with 1,400 events in the
14-day chart. The benchmark runs the actual production daily query and the previous SQL against
the same database. After two warmups, median times across 11 samples on Java 21 were:

| Daily-activity query                  |    Median |
| ------------------------------------- | --------: |
| Previous date-only filter             | 18.794 ms |
| Indexed coarse range plus date filter |  0.885 ms |

These are local warm-query measurements, not whole-dashboard request latency. Other total and
breakdown queries still inspect the wider history. Timing assertions are deliberately absent.

## Verification

- The query-plan assertion fails on the previous implementation and passes with the bounds.
- Results match the previous SQL across both chart edges, including nanosecond text, midnight,
  SQLite's `24:00:00` form, space-separated dates and offsets through `+14:00` and `-14:00`.
- Dashboard/API regression tests pass with Java 21.
- A packaged application on a random loopback port with disposable SQLite storage accepts an old,
  recent and future event. `/api/stats` reports all three in totals and only the recent event in
  the chart. The process and temporary database are removed afterward.
- Independent review found no actionable issues. Production storage and services were untouched.

Run the focused tests and optional measurement with:

```bash
mvn -Dtest=DashboardActivityRangeTest,AgenticControllerTest test
mvn -Dtest=DashboardActivityRangeTest -Dsba.performance.benchmark=true test
```

Use Java 21 as required by the project.
