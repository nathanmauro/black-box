import type { EventFeedItem } from "./api";
import { kindMarkLabel, kindMarkOf } from "./presenters/marks";

// Time-windowed session stitching (spec §4.1, D2). Pure interpretation over the fetched page —
// zero state, recomputed as a memo — so SSE prepends, pending-merge, dedupe, and cap trimming
// self-heal by recomputation (P6).
export const STITCH_WINDOW_MS = 15 * 60 * 1000;

// The trajectory's idle-gap idiom (lib/trajectory.ts GAP_HOURS): adjacent loaded items further
// apart than this earn a gap annotation on the dateline between them (spec §4.2).
export const GAP_HOURS = 48;

// Chatter folds (spec §4.4): inside a run, this many consecutive same-tool chatter rows — all
// effectively collapsed — collapse to one fold row.
export const FOLD_MIN_STREAK = 4;

// Landmark kinds never fold and always render the colored KindBadge (spec §4.3).
export const LANDMARK_KINDS = new Set(["Decision", "Handoff", "Observation", "UserPromptSubmit"]);

const DAY_MS = 24 * 60 * 60 * 1000;

export type EventRow = { type: "event"; item: EventFeedItem };
// loudestTone: "error" when ANY member's cached presentation tone is error — collapse must
// never bury a failure (P4, D3).
export type FoldRow = { type: "fold"; items: EventFeedItem[]; mark: string; loudestTone: string; key: string };
export type Row = EventRow | FoldRow;

export type RunSegment = {
  type: "run";
  // Keyed by the run's oldest member — stable under SSE head-prepend (new arrivals extend the
  // newest edge; the anchor at the old edge stays fixed). Pagination extending a run backward
  // changes its key; accepted, same residual as fold keys (spec §4.4).
  key: string;
  sessionId: string;
  source: string;
  clientSessionId: string;
  cwd: string | null;
  sessionTitle: string | null;
  // Raw event count before folding — drives the header count and the compact↔sticky threshold.
  eventCount: number;
  newestAt: string;
  oldestAt: string;
  lastSeenAt: string | null;
  rows: Row[];
};

export type DaySegment = {
  type: "day";
  key: string;
  label: string;
  // Filter-honest gap annotation (P4): "quiet 3d" only when the query carries no visible
  // filter — otherwise "no matches for 3d": the system wasn't quiet, the filter was.
  gapLabel: string | null;
};

export type Segment = RunSegment | DaySegment;

// Folding applies only while it is passed (the page omits it in global expanded mode —
// "Expanded" means expanded, §4.4; the §15 DOM budget is defined for collapsed mode).
// isRowExpanded reflects the density override Set: an expanded row breaks the streak and folds
// its neighbors around it — a fold may never swallow a card the user is reading. `unfolded`
// holds fold keys the user opened in place; those streaks emit their event rows normally.
export type FoldOptions = {
  isRowExpanded?: (id: string) => boolean;
  unfolded?: ReadonlySet<string>;
};

export type SegmentOptions = {
  stitchWindowMs?: number;
  hasVisibleFilter?: boolean;
  now?: Date;
  folds?: FoldOptions;
};

type MutableRun = {
  sessionId: string;
  source: string;
  clientSessionId: string;
  cwd: string | null;
  sessionTitle: string | null;
  lastSeenAt: string | null;
  dateKey: string;
  newestMs: number;
  newestAt: string;
  oldestMs: number;
  oldestAt: string;
  oldestId: string;
  items: EventFeedItem[];
};

/**
 * Scanning newest→oldest, an event joins its session's most recently created run when the gap
 * to that run's oldest member is ≤ the stitch window AND both fall on the same local date;
 * otherwise it starts a new run. Runs emit in creation order (= order of their newest members,
 * matching the feed direction); rows inside a run are newest-first. Day segments appear between
 * runs when the local date changes.
 */
export function segmentStream(items: EventFeedItem[], opts: SegmentOptions = {}): Segment[] {
  const windowMs = opts.stitchWindowMs ?? STITCH_WINDOW_MS;
  const runs: MutableRun[] = [];
  const latestRunBySession = new Map<string, MutableRun>();

  for (const item of items) {
    const ms = Date.parse(item.observedAt);
    const dateKey = localDateKey(new Date(ms));
    const candidate = latestRunBySession.get(item.sessionId);
    if (candidate && candidate.dateKey === dateKey && candidate.oldestMs - ms <= windowMs) {
      candidate.items.push(item);
      if (ms < candidate.oldestMs || (ms === candidate.oldestMs && item.id < candidate.oldestId)) {
        candidate.oldestMs = ms;
        candidate.oldestAt = item.observedAt;
        candidate.oldestId = item.id;
      }
      if (ms > candidate.newestMs) {
        candidate.newestMs = ms;
        candidate.newestAt = item.observedAt;
      }
    } else {
      const run: MutableRun = {
        sessionId: item.sessionId,
        source: item.source,
        clientSessionId: item.clientSessionId,
        cwd: item.cwd ?? null,
        sessionTitle: item.sessionTitle ?? null,
        lastSeenAt: item.lastSeenAt ?? null,
        dateKey,
        newestMs: ms,
        newestAt: item.observedAt,
        oldestMs: ms,
        oldestAt: item.observedAt,
        oldestId: item.id,
        items: [item],
      };
      runs.push(run);
      latestRunBySession.set(item.sessionId, run);
    }
  }

  const now = opts.now ?? new Date();
  const segments: Segment[] = [];
  let previous: MutableRun | null = null;
  for (const run of runs) {
    if (previous && previous.dateKey !== run.dateKey) {
      const gapMs = previous.oldestMs - run.newestMs;
      let gapLabel: string | null = null;
      if (gapMs > GAP_HOURS * 60 * 60 * 1000) {
        const days = Math.round(gapMs / DAY_MS);
        gapLabel = opts.hasVisibleFilter ? `no matches for ${days}d` : `quiet ${days}d`;
      }
      segments.push({
        type: "day",
        key: `day:${run.dateKey}`,
        label: dayLabel(new Date(run.newestMs), now),
        gapLabel,
      });
    }
    segments.push({
      type: "run",
      key: `${run.sessionId}:${run.oldestId}`,
      sessionId: run.sessionId,
      source: run.source,
      clientSessionId: run.clientSessionId,
      cwd: run.cwd,
      sessionTitle: run.sessionTitle,
      eventCount: run.items.length,
      newestAt: run.newestAt,
      oldestAt: run.oldestAt,
      lastSeenAt: run.lastSeenAt,
      rows: buildRows(run.items, opts.folds),
    });
    previous = run;
  }
  return segments;
}

// Chatter folds (spec §4.4): a streak of ≥ FOLD_MIN_STREAK consecutive foldable rows with the
// same toolName becomes one fold row keyed by its OLDEST member id — stable under SSE
// head-prepend (new arrivals extend the newest edge; the anchor at the old edge stays fixed).
// Residual: pagination extending a fold backward changes its key and refolds it; accepted.
function buildRows(items: EventFeedItem[], folds?: FoldOptions): Row[] {
  if (!folds) return items.map((item) => ({ type: "event", item }) as EventRow);
  const rows: Row[] = [];
  let index = 0;
  while (index < items.length) {
    const head = items[index];
    if (!foldable(head, folds)) {
      rows.push({ type: "event", item: head });
      index += 1;
      continue;
    }
    let end = index;
    while (end < items.length && foldable(items[end], folds) && items[end].toolName === head.toolName) end += 1;
    const streak = items.slice(index, end);
    if (streak.length >= FOLD_MIN_STREAK) {
      const key = streak[streak.length - 1].id;
      if (folds.unfolded?.has(key)) {
        for (const member of streak) rows.push({ type: "event", item: member });
      } else {
        rows.push({
          type: "fold",
          items: streak,
          mark: kindMarkLabel(head.toolName),
          // kindMarkOf reads the WeakMap-cached presentation, so this never re-parses payloads.
          loudestTone: streak.some((member) => kindMarkOf(member).error) ? "error" : "neutral",
          key,
        });
      }
    } else {
      for (const member of streak) rows.push({ type: "event", item: member });
    }
    index = end;
  }
  return rows;
}

// Only tool-attributed chatter folds: landmarks never fold, and an effectively-expanded row
// breaks the streak (a fold may never swallow a card the user is reading).
function foldable(item: EventFeedItem, folds: FoldOptions): boolean {
  return (
    !LANDMARK_KINDS.has(item.eventType ?? "") &&
    Boolean(item.toolName) &&
    !folds.isRowExpanded?.(item.id)
  );
}

// Reference-stable recomputation: segmentStream builds fresh objects every call, but the page's
// <For> keys DOM on object identity — without reuse, every memo recompute (a row toggle, an
// unfold, a density switch) would rebuild the entire feed DOM and drop focus/scroll state.
// Reusing the previous segment object whenever the new one is structurally identical confines
// DOM churn to the runs that actually changed.
export function reconcileSegments(previous: Segment[], next: Segment[]): Segment[] {
  if (!previous.length) return next;
  const previousByKey = new Map(previous.map((segment) => [segment.key, segment]));
  return next.map((segment) => {
    const match = previousByKey.get(segment.key);
    return match && segmentsEqual(match, segment) ? match : segment;
  });
}

function segmentsEqual(a: Segment, b: Segment): boolean {
  if (a.type === "day" && b.type === "day") return a.label === b.label && a.gapLabel === b.gapLabel;
  if (a.type === "run" && b.type === "run") {
    return (
      a.eventCount === b.eventCount &&
      a.rows.length === b.rows.length &&
      a.rows.every((row, index) => rowsEqual(row, b.rows[index]))
    );
  }
  return false;
}

function rowsEqual(a: Row, b: Row): boolean {
  if (a.type === "event" && b.type === "event") return a.item === b.item;
  if (a.type === "fold" && b.type === "fold") {
    return (
      a.key === b.key &&
      a.loudestTone === b.loudestTone &&
      a.items.length === b.items.length &&
      a.items.every((member, index) => member === b.items[index])
    );
  }
  return false;
}

function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
}

function dayLabel(date: Date, now: Date): string {
  const key = localDateKey(date);
  if (key === localDateKey(now)) return "Today";
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (key === localDateKey(yesterday)) return "Yesterday";
  // "Mon Aug 18" — weekday and month stay short, no comma.
  return date
    .toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric" })
    .replace(",", "");
}
