#!/usr/bin/env node
// Stream grouping measurement gate (spec §4.1 / §15): run the meaningful-predicate feed query
// for the newest 500 events against a DB copy, stitch them with the same parameters as
// frontend/src/lib/streamGroups.ts, and report mounted-row counts vs the <500 / ≤~400 budget.
//
// Usage:  node scripts/measure-stream-grouping.mjs <path-to-db-copy>
//
// NEVER point this at the live sba-agentic.db — take a fresh copy first:
//   sqlite3 <live-db> ".backup /tmp/measure-copy.db"
//
// The stitching parameters here mirror streamGroups.ts (STITCH_WINDOW_MS, local-date bound) and
// the fold rule mirrors spec §4.4 (≥4 consecutive same-tool chatter rows → one fold row). Keep
// them in sync by hand — this script exists to measure the live corpus, not to be the library.

import { DatabaseSync } from "node:sqlite";

const STITCH_WINDOW_MS = 15 * 60 * 1000;
const FEED_CAP = 500;
const FOLD_MIN_STREAK = 4;
const LANDMARK_KINDS = new Set(["decision", "handoff", "observation", "userpromptsubmit"]);

const dbPath = process.argv[2];
if (!dbPath) {
  console.error("Usage: node scripts/measure-stream-grouping.mjs <path-to-db-copy>");
  process.exit(2);
}

// SQL shape copied from RecordingSqlStore (MEANINGFUL_EVENT_PREDICATE + the feed's join/order).
const MEANINGFUL_EVENT_PREDICATE = `(
  lower(coalesce(e.event_type, '')) IN ('decision', 'handoff')
  OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"decision"%'
  OR lower(coalesce(e.metadata_json, '')) LIKE '%"kind":"handoff"%'
  OR (lower(coalesce(e.role, '')) = 'assistant' AND trim(coalesce(e.text, '')) <> '')
  OR e.tool_name IS NOT NULL
  OR lower(coalesce(e.event_type, '')) LIKE '%tool%'
  OR lower(coalesce(e.event_type, '')) LIKE '%error%'
  OR lower(coalesce(e.event_type, '')) LIKE '%fail%'
)`;

const db = new DatabaseSync(dbPath, { readOnly: true });
const rows = db
  .prepare(
    `SELECT e.id, e.session_id AS sessionId, e.event_type AS eventType, e.tool_name AS toolName,
            e.observed_at AS observedAt
       FROM agent_events e
       JOIN agent_sessions s ON s.id = e.session_id
      WHERE ${MEANINGFUL_EVENT_PREDICATE}
      ORDER BY e.observed_at DESC, e.id DESC
      LIMIT ${FEED_CAP}`,
  )
  .all();
db.close();

// --- stitching (mirror of streamGroups.segmentStream) -------------------------------------
function localDateKey(ms) {
  const date = new Date(ms);
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
}

const runs = [];
const latestRunBySession = new Map();
for (const row of rows) {
  const ms = Date.parse(row.observedAt);
  const dateKey = localDateKey(ms);
  const candidate = latestRunBySession.get(row.sessionId);
  if (candidate && candidate.dateKey === dateKey && candidate.oldestMs - ms <= STITCH_WINDOW_MS) {
    candidate.events.push(row);
    if (ms < candidate.oldestMs) candidate.oldestMs = ms;
  } else {
    const run = { sessionId: row.sessionId, dateKey, oldestMs: ms, events: [row] };
    runs.push(run);
    latestRunBySession.set(row.sessionId, run);
  }
}

let daybreaks = 0;
for (let i = 1; i < runs.length; i++) {
  if (runs[i].dateKey !== runs[i - 1].dateKey) daybreaks += 1;
}

// --- folds (mirror of streamGroups.buildRows: ≥4 consecutive non-landmark rows with the same
// non-null toolName fold to one row; collapsed mode, no overrides/unfolds — the §15 budget's
// definition) -------------------------------------------------------------------------------
function isChatter(row) {
  return !LANDMARK_KINDS.has(String(row.eventType ?? "").toLowerCase());
}

let foldedEventRows = 0;
let foldRows = 0;
for (const run of runs) {
  let i = 0;
  while (i < run.events.length) {
    const row = run.events[i];
    if (!isChatter(row) || !row.toolName) {
      foldedEventRows += 1;
      i += 1;
      continue;
    }
    let j = i;
    while (j < run.events.length && isChatter(run.events[j]) && run.events[j].toolName === row.toolName) j += 1;
    const streak = j - i;
    if (streak >= FOLD_MIN_STREAK) foldRows += 1;
    else foldedEventRows += streak;
    i = j;
  }
}

// --- report --------------------------------------------------------------------------------
const histogram = new Map();
for (const run of runs) {
  histogram.set(run.events.length, (histogram.get(run.events.length) ?? 0) + 1);
}
const histogramLine = [...histogram.entries()]
  .sort((a, b) => a[0] - b[0])
  .map(([length, count]) => `len ${length}: ${count}`)
  .join("  ");

const eventRows = rows.length;
const headerRows = runs.length;
const slice4Total = eventRows + headerRows + daybreaks;
const slice5Total = foldedEventRows + foldRows + headerRows + daybreaks;

console.log(`events fetched (newest meaningful): ${eventRows}`);
console.log(`runs (stitched, ${STITCH_WINDOW_MS / 60000}m window, date-bounded): ${headerRows}`);
console.log(`run-length histogram: ${histogramLine}`);
console.log(`daybreak rows: ${daybreaks}`);
console.log(`header rows: ${headerRows}`);
console.log(`event rows: ${eventRows}`);
console.log("");
console.log(`total mounted rows without folds (expanded-mode shape): ${slice4Total}`);
console.log(
  `total mounted rows, collapsed mode with §4.4 folds: ${slice5Total}` +
    ` (${foldRows} folds absorbing ${eventRows - foldedEventRows} chatter rows)`,
);
console.log("");
const verdict = (total) => (total < 500 ? (total <= 400 ? "PASS (≤400 target)" : "PASS (<500)") : "FAIL (≥500)");
console.log(`verdict without folds:      ${verdict(slice4Total)}`);
console.log(`verdict collapsed (gate):   ${verdict(slice5Total)}`);
