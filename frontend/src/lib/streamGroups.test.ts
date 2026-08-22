import { describe, expect, it } from "vitest";
import type { EventFeedItem } from "./api";
import { reconcileSegments, segmentStream, STITCH_WINDOW_MS, type FoldRow, type RunSegment } from "./streamGroups";

// Local-time constructors keep the date-boundary cases timezone-independent: the lib groups by
// LOCAL date, so tests build instants from local wall-clock positions, never fixed UTC strings.
function at(day: number, hour: number, minute: number, month = 8): string {
  return new Date(2026, month - 1, day, hour, minute, 0).toISOString();
}

function item(id: string, sessionId: string, observedAt: string, overrides: Partial<EventFeedItem> = {}): EventFeedItem {
  return {
    id,
    sessionId,
    source: "codex",
    clientSessionId: `client-${sessionId}`,
    eventType: "PostToolUse",
    observedAt,
    cwd: "/Users/nathan/Developer/proj/sba-agentic",
    sessionTitle: `Title ${sessionId}`,
    ...overrides,
  };
}

function runs(segments: ReturnType<typeof segmentStream>): RunSegment[] {
  return segments.filter((segment): segment is RunSegment => segment.type === "run");
}

function rowIds(run: RunSegment): string[] {
  return run.rows.map((row) => (row.type === "event" ? row.item.id : row.key));
}

const NOW = new Date(2026, 7, 20, 15, 0, 0); // Thu Aug 20 2026, 15:00 local

describe("segmentStream", () => {
  it("returns no segments for an empty feed", () => {
    expect(segmentStream([])).toEqual([]);
  });

  it("wraps a single event in a single run keyed by its oldest (only) member", () => {
    const segments = segmentStream([item("e1", "s1", at(20, 12, 0))]);
    expect(segments).toHaveLength(1);
    const run = segments[0] as RunSegment;
    expect(run.type).toBe("run");
    expect(run.key).toBe("s1:e1");
    expect(run.sessionId).toBe("s1");
    expect(run.eventCount).toBe(1);
    expect(rowIds(run)).toEqual(["e1"]);
  });

  it("stitches same-session events within the window into one run, rows newest-first", () => {
    const segments = segmentStream([
      item("e1", "s1", at(20, 12, 0)),
      item("e2", "s1", at(20, 11, 50)),
    ]);
    expect(runs(segments)).toHaveLength(1);
    const run = runs(segments)[0];
    expect(rowIds(run)).toEqual(["e1", "e2"]);
    expect(run.eventCount).toBe(2);
    expect(run.newestAt).toBe(at(20, 12, 0));
    expect(run.oldestAt).toBe(at(20, 11, 50));
    expect(run.key).toBe("s1:e2");
  });

  it("starts a new run beyond the stitch window", () => {
    const segments = segmentStream([
      item("e1", "s1", at(20, 12, 0)),
      item("e2", "s1", at(20, 11, 40)), // 20m > 15m window
    ]);
    expect(runs(segments).map((run) => run.key)).toEqual(["s1:e1", "s1:e2"]);
  });

  it("measures the gap to the run's oldest member, so a chain can stretch past the window", () => {
    const segments = segmentStream([
      item("e1", "s1", at(20, 12, 0)),
      item("e2", "s1", at(20, 11, 50)),
      item("e3", "s1", at(20, 11, 41)),
      item("e4", "s1", at(20, 11, 30)), // 30m from the newest, but 11m from the oldest — joins
    ]);
    expect(runs(segments)).toHaveLength(1);
    expect(rowIds(runs(segments)[0])).toEqual(["e1", "e2", "e3", "e4"]);
  });

  it("stitches through cross-session interleave — the dominant measured case", () => {
    const segments = segmentStream([
      item("a1", "sA", at(20, 12, 0)),
      item("b1", "sB", at(20, 11, 59)),
      item("a2", "sA", at(20, 11, 58)),
      item("b2", "sB", at(20, 11, 57)),
    ]);
    const all = runs(segments);
    expect(all).toHaveLength(2);
    expect(all[0].sessionId).toBe("sA");
    expect(rowIds(all[0])).toEqual(["a1", "a2"]);
    expect(all[1].sessionId).toBe("sB");
    expect(rowIds(all[1])).toEqual(["b1", "b2"]);
  });

  it("emits runs in creation order — the order of their newest members", () => {
    const segments = segmentStream([
      item("a1", "sA", at(20, 12, 0)),
      item("b1", "sB", at(20, 11, 30)),
      item("a2", "sA", at(20, 10, 0)), // second sA run: 2h from the first run's oldest member
    ]);
    expect(runs(segments).map((run) => run.key)).toEqual(["sA:a1", "sB:b1", "sA:a2"]);
  });

  it("splits a run at a local-date boundary even inside the window and emits a dateline", () => {
    const segments = segmentStream(
      [
        item("e1", "s1", at(19, 0, 5)),
        item("e2", "s1", at(18, 23, 55)), // 10m gap, but the previous local day
      ],
      { now: NOW },
    );
    expect(segments.map((segment) => segment.type)).toEqual(["run", "day", "run"]);
    expect(runs(segments).map((run) => run.key)).toEqual(["s1:e1", "s1:e2"]);
    const day = segments[1];
    expect(day.type === "day" && day.gapLabel).toBeNull();
  });

  it("labels datelines Today / Yesterday / weekday-month-day", () => {
    const segments = segmentStream(
      [
        item("e1", "s1", at(20, 12, 0)),
        item("e2", "s2", at(19, 12, 0)),
        item("e3", "s3", at(18, 12, 0)),
      ],
      { now: NOW },
    );
    const labels = segments.filter((segment) => segment.type === "day").map((segment) => segment.label);
    expect(labels).toEqual(["Yesterday", "Tue Aug 18"]);
  });

  it("annotates a >48h gap as quiet when no visible filter is active", () => {
    const segments = segmentStream(
      [item("e1", "s1", at(20, 12, 0)), item("e2", "s2", at(16, 12, 0))],
      { now: NOW, hasVisibleFilter: false },
    );
    const day = segments.find((segment) => segment.type === "day");
    expect(day?.type === "day" && day.gapLabel).toBe("quiet 4d");
  });

  it("annotates the same gap as no-matches when a visible filter is active — filter-honest", () => {
    const segments = segmentStream(
      [item("e1", "s1", at(20, 12, 0)), item("e2", "s2", at(16, 12, 0))],
      { now: NOW, hasVisibleFilter: true },
    );
    const day = segments.find((segment) => segment.type === "day");
    expect(day?.type === "day" && day.gapLabel).toBe("no matches for 4d");
  });

  it("leaves gaps of 48h or less unannotated across a date change", () => {
    const segments = segmentStream(
      [item("e1", "s1", at(20, 12, 0)), item("e2", "s2", at(19, 12, 0))],
      { now: NOW },
    );
    const day = segments.find((segment) => segment.type === "day");
    expect(day?.type === "day" && day.gapLabel).toBeNull();
  });

  it("keeps existing run identities stable when an SSE prepend extends the head run", () => {
    const base = [
      item("a1", "sA", at(20, 12, 0)),
      item("b1", "sB", at(20, 11, 30)),
    ];
    const before = runs(segmentStream(base)).map((run) => run.key);

    const prepended = [item("a0", "sA", at(20, 12, 5)), ...base];
    const after = runs(segmentStream(prepended));
    expect(after.map((run) => run.key)).toEqual(before);
    expect(rowIds(after[0])).toEqual(["a0", "a1"]);
    expect(after[0].eventCount).toBe(2);
  });

  it("keeps existing run identities stable when an SSE prepend opens a new run", () => {
    const base = [item("a1", "sA", at(20, 12, 0)), item("b1", "sB", at(20, 11, 30))];
    const before = runs(segmentStream(base)).map((run) => run.key);

    const prepended = [item("c1", "sC", at(20, 12, 10)), ...base];
    const after = runs(segmentStream(prepended)).map((run) => run.key);
    expect(after).toEqual(["sC:c1", ...before]);
  });

  it("honors a custom stitch window", () => {
    const items = [item("e1", "s1", at(20, 12, 0)), item("e2", "s1", at(20, 11, 58))];
    expect(runs(segmentStream(items, { stitchWindowMs: 60 * 1000 }))).toHaveLength(2);
    expect(runs(segmentStream(items, { stitchWindowMs: STITCH_WINDOW_MS }))).toHaveLength(1);
  });
});

// Chatter fixture: PostToolUse rows with a toolName, one minute apart, one session.
function chatter(id: string, minute: number, toolName = "Read", overrides: Partial<EventFeedItem> = {}): EventFeedItem {
  return item(id, "s1", at(20, 12, minute), {
    toolName,
    toolInputJson: '{"file_path":"/tmp/file.ts"}',
    ...overrides,
  });
}

function soleRun(segments: ReturnType<typeof segmentStream>): RunSegment {
  const all = runs(segments);
  expect(all).toHaveLength(1);
  return all[0];
}

describe("segmentStream folds", () => {
  it("folds ≥4 consecutive same-tool collapsed chatter rows into one fold keyed by the oldest member", () => {
    const items = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const run = soleRun(segmentStream(items, { folds: {} }));

    expect(run.rows).toHaveLength(1);
    const fold = run.rows[0] as FoldRow;
    expect(fold.type).toBe("fold");
    expect(fold.key).toBe("e4");
    expect(fold.items.map((member) => member.id)).toEqual(["e1", "e2", "e3", "e4"]);
    expect(fold.mark).toBe("read");
    expect(fold.loudestTone).toBe("neutral");
    // eventCount stays the raw pre-fold count (header count + sticky threshold, spec §4.1).
    expect(run.eventCount).toBe(4);
  });

  it("leaves streaks below the threshold as plain event rows", () => {
    const items = [chatter("e1", 2), chatter("e2", 1), chatter("e3", 0)];
    const run = soleRun(segmentStream(items, { folds: {} }));
    expect(run.rows.map((row) => row.type)).toEqual(["event", "event", "event"]);
  });

  it("requires the same toolName — a tool change breaks the streak", () => {
    const items = [chatter("e1", 4), chatter("e2", 3), chatter("e3", 2, "Bash"), chatter("e4", 1), chatter("e5", 0)];
    const run = soleRun(segmentStream(items, { folds: {} }));
    expect(run.rows.map((row) => row.type)).toEqual(["event", "event", "event", "event", "event"]);
  });

  it("folds adjacent same-tool streaks separately per tool", () => {
    const items = [
      chatter("r1", 7), chatter("r2", 6), chatter("r3", 5), chatter("r4", 4),
      chatter("b1", 3, "Bash"), chatter("b2", 2, "Bash"), chatter("b3", 1, "Bash"), chatter("b4", 0, "Bash"),
    ];
    const run = soleRun(segmentStream(items, { folds: {} }));
    expect(run.rows.map((row) => row.type)).toEqual(["fold", "fold"]);
    expect((run.rows[0] as FoldRow).key).toBe("r4");
    expect((run.rows[1] as FoldRow).key).toBe("b4");
  });

  it("never folds landmarks — a landmark breaks the streak", () => {
    const items = [
      chatter("e1", 4), chatter("e2", 3),
      item("d1", "s1", at(20, 12, 2), { eventType: "Decision", text: "Chose folds" }),
      chatter("e3", 1), chatter("e4", 0),
    ];
    const run = soleRun(segmentStream(items, { folds: {} }));
    expect(run.rows.every((row) => row.type === "event")).toBe(true);
  });

  it("breaks the streak at an effectively-expanded row and folds its neighbors around it", () => {
    const items = Array.from({ length: 9 }, (_, index) => chatter(`e${index + 1}`, 8 - index));
    const run = soleRun(segmentStream(items, { folds: { isRowExpanded: (id) => id === "e5" } }));

    expect(run.rows.map((row) => row.type)).toEqual(["fold", "event", "fold"]);
    expect((run.rows[0] as FoldRow).key).toBe("e4");
    expect(run.rows[1].type === "event" && run.rows[1].item.id).toBe("e5");
    expect((run.rows[2] as FoldRow).key).toBe("e9");
  });

  it("emits no folds when the folds option is absent — Expanded means expanded", () => {
    const items = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const run = soleRun(segmentStream(items));
    expect(run.rows.map((row) => row.type)).toEqual(["event", "event", "event", "event"]);
  });

  it("propagates a swallowed failure as the loudest tone", () => {
    const items = [
      chatter("e1", 3, "Bash", { toolInputJson: '{"command":"npm test"}' }),
      chatter("e2", 2, "Bash", { toolInputJson: '{"command":"false"}', toolOutputJson: '{"exit_code":1,"output":"boom"}' }),
      chatter("e3", 1, "Bash", { toolInputJson: '{"command":"ls"}' }),
      chatter("e4", 0, "Bash", { toolInputJson: '{"command":"pwd"}' }),
    ];
    const fold = soleRun(segmentStream(items, { folds: {} })).rows[0] as FoldRow;
    expect(fold.type).toBe("fold");
    expect(fold.loudestTone).toBe("error");
    expect(fold.mark).toBe("run");
  });

  it("keeps the fold key stable when an SSE prepend extends the streak's newest edge", () => {
    const base = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const before = soleRun(segmentStream(base, { folds: {} })).rows[0] as FoldRow;

    const prepended = [chatter("e0", 4), ...base];
    const after = soleRun(segmentStream(prepended, { folds: {} })).rows[0] as FoldRow;
    expect(after.key).toBe(before.key);
    expect(after.items).toHaveLength(5);
  });

  it("changes the fold key when pagination extends the streak backward — accepted residual", () => {
    const base = [chatter("e1", 4), chatter("e2", 3), chatter("e3", 2), chatter("e4", 1)];
    const extended = [...base, chatter("e5", 0)];
    const before = soleRun(segmentStream(base, { folds: {} })).rows[0] as FoldRow;
    const after = soleRun(segmentStream(extended, { folds: {} })).rows[0] as FoldRow;
    // The anchor moved to the new oldest member: the fold refolds under a new key (spec §4.4).
    expect(before.key).toBe("e4");
    expect(after.key).toBe("e5");
  });

  it("emits event rows in place of a fold the user unfolded", () => {
    const items = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const run = soleRun(segmentStream(items, { folds: { unfolded: new Set(["e4"]) } }));
    expect(run.rows.map((row) => (row.type === "event" ? row.item.id : row.key))).toEqual(["e1", "e2", "e3", "e4"]);
  });
});

describe("reconcileSegments", () => {
  it("reuses previous segment objects when a recompute changes nothing structurally", () => {
    const items = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const first = segmentStream(items, { folds: {} });
    const second = segmentStream(items, { folds: {} });
    const reconciled = reconcileSegments(first, second);
    expect(reconciled[0]).toBe(first[0]);
  });

  it("replaces only the segments that structurally changed", () => {
    const chatterRun = [chatter("e1", 3), chatter("e2", 2), chatter("e3", 1), chatter("e4", 0)];
    const otherRun = [item("x1", "s2", at(20, 12, 4))];
    const all = [...otherRun, ...chatterRun];

    const folded = reconcileSegments([], segmentStream(all, { folds: {} }));
    const unfolded = reconcileSegments(folded, segmentStream(all, { folds: { unfolded: new Set(["e4"]) } }));

    // The untouched s2 run keeps its identity; the unfolded s1 run is a new object.
    expect(unfolded[0]).toBe(folded[0]);
    expect(unfolded[1]).not.toBe(folded[1]);
    expect((unfolded[1] as RunSegment).rows).toHaveLength(4);
  });
});
