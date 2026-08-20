import type { EventFeedItem } from "./api";

// Time-windowed session stitching (spec §4.1, D2). Pure interpretation over the fetched page —
// zero state, recomputed as a memo — so SSE prepends, pending-merge, dedupe, and cap trimming
// self-heal by recomputation (P6).
export const STITCH_WINDOW_MS = 15 * 60 * 1000;

// The trajectory's idle-gap idiom (lib/trajectory.ts GAP_HOURS): adjacent loaded items further
// apart than this earn a gap annotation on the dateline between them (spec §4.2).
export const GAP_HOURS = 48;

const DAY_MS = 24 * 60 * 60 * 1000;

// The Row union already reserves the fold shape for slice 5 (chatter folds, spec §4.4);
// segmentStream emits only event rows today.
export type EventRow = { type: "event"; item: EventFeedItem };
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

export type SegmentOptions = {
  stitchWindowMs?: number;
  hasVisibleFilter?: boolean;
  now?: Date;
};

type MutableRun = {
  sessionId: string;
  source: string;
  clientSessionId: string;
  cwd: string | null;
  sessionTitle: string | null;
  dateKey: string;
  newestMs: number;
  newestAt: string;
  oldestMs: number;
  oldestAt: string;
  oldestId: string;
  rows: Row[];
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
      candidate.rows.push({ type: "event", item });
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
        dateKey,
        newestMs: ms,
        newestAt: item.observedAt,
        oldestMs: ms,
        oldestAt: item.observedAt,
        oldestId: item.id,
        rows: [{ type: "event", item }],
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
      eventCount: run.rows.length,
      newestAt: run.newestAt,
      oldestAt: run.oldestAt,
      rows: run.rows,
    });
    previous = run;
  }
  return segments;
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
