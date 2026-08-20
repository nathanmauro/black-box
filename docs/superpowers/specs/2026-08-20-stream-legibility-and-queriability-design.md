# Stream Legibility And Queriability — calm at density, answers on demand

**Date:** 2026-08-20
**Status:** Design, decision-locked (adversarially reviewed against the live corpus; measured
claims below come from a `.backup` copy of the production DB, 286k events / 5.7k sessions)
**Scope:** the Stream surface (`/` stream mode), its query grammar end-to-end, and the minimum
backend work the grammar needs. Everything else (Browse, Ask, Board, the trajectory graph itself)
is touched only where a decision here reaches into it, and each such reach is called out.

---

## 1. Problem

The Stream is Black Box's front page and its worst page at the one job it now has: watching many
agents work at density. A live screenshot of `/` today shows the failure concretely:

1. **Color spent on sameness.** Every visible row carries the same colored `PostToolUse` badge.
   When every row is loud, nothing is; the Decision/Handoff/Observation landmarks the product
   exists to capture are indistinguishable from `Read` chatter.
2. **Repetition instead of structure.** The same cwd renders on nearly every row; sessions
   interleave with no grouping; the eye has no sections, only lines. Five signals per row
   (dot, badge, cwd, headline, time) × 500 rows is thousands of competing marks.
3. **Filters that lie or hide.** A client-side `sourceFilter` (frontend/src/lib/stores.ts:4-29)
   silently post-filters the fetched page outside the URL, can make "Load more" vanish
   (`canLoadMore` checks unfiltered `items()`, StreamPage.tsx:83), and desyncs from the visible
   `source:` facet. `meaningfulOnly` is invisible state that resets on reload.
4. **Questions the stream cannot answer.** No time scoping of any kind, no `session:` facet, no
   OR, free text is an unindexable `LIKE '%x%'` scan, and `tool_input_json`/`tool_output_json` —
   where "when did that test start failing?" actually lives — are searched by nothing.
5. **No addresses.** `/stream` is not a real route; there are no saved views; the query grammar
   exists in three drifting copies (recording `EventFeedQuery`, memory `QueryFacets`, frontend
   `lib/query.ts`).

## 2. What the dig found (load-bearing facts)

- The base is better than a firehose: `?q=` already round-trips a real facet DSL
  (source/kind/tool/project, `-`/`NOT` negation, quoted values — frontend/src/lib/query.ts),
  keyset pagination (`nextBefore`, cursor `<ISO>|<id>` pinned by EventFeedTest), and an
  SSE-wake-hint → debounced `since` head-refetch with scroll-anchored merge (StreamPage.tsx:155-255).
- Hidden project scoping appends `project_group:<canonicalKey>` to the API query and strips it
  from the visible text (StreamPage.tsx:76-79, 408-414). Contract to preserve.
- Density = a global collapsed/expanded default plus a per-row override `Set` documented as
  "exceptions to the mode" so SSE rows need zero bookkeeping (StreamPage.tsx:52-72). Invariant to
  preserve.
- `GET /api/events` (EventController.java:48-60) accepts `q, limit, before, since, meaningful`;
  `since` is tested as an inclusive SSE-reconnect head window (EventFeedTest), not a user time
  bound. No upper time bound exists.
- Free text today is **one joined phrase**: `EventFeedQuery.freeTextPhrase()` joins all free
  tokens with spaces and RecordingSqlStore compiles a single `%phrase%` LIKE over
  text/tool_name/metadata_json (EventFeedQuery.java:241-243, RecordingSqlStore.feed ~:357-365).
  `foo bar` matches only the contiguous substring. Zero FTS5 anywhere in the repo.
- **Measured on the live corpus:** the newest 500 meaningful events span 18 minutes and fragment
  into **309 contiguous same-session runs** — 225 of length 1, 52 of length 2; the busiest hour
  ever still averages 2.3 events per contiguous run. Naive contiguous grouping therefore *adds*
  chrome (§4.1 fixes this with stitching).
- **Measured on the live corpus:** zero of 286k events have an `event_type` matching
  error/fail. The real failure signal lives inside `tool_output_json` (`is_error`, `exit_code`);
  today only the bash presenter surfaces it (presenters/bash.ts:19, tone `error`), and only in
  expanded cards. `MEANINGFUL_EVENT_PREDICATE`'s `%error%/%fail%` arms are dead in practice.
- The memory module already owns `/api/search/values` + `/api/search/fields` autocomplete
  primitives and an optional ES arm that fails soft (SearchService.java:98-105). Semantic
  embeddings cover Decision/Handoff/Observation + session summaries **only** (AGENTS.md honesty
  constraint).
- The trajectory graph (lib/trajectory.ts + TrajectoryView.tsx) established the house grammar
  this spec extends to the feed: color earned, honest collapse ("+N earlier captures"), 48h
  idle-gap epochs, pure-lib interpretation + thin SVG view. Its selection is not URL-synced — a
  known flaw fixed here.

## 3. Design principles (locked)

| # | Principle | Meaning here |
|---|-----------|--------------|
| P1 | **Color is earned** | Saturated color only for: intent landmarks (Decision/Handoff/Observation/UserPromptSubmit), failures, live-now affordances, active filter chips. Chatter is ink on a four-step grayscale ramp (`--text-bright/--text/--text-dim/--text-faint`). |
| P2 | **Repeat nothing** | Shared context (source, session, cwd, date) renders once per group, not per row. Rows show only what varies. Deviations render; repetition doesn't. |
| P3 | **One truth in the URL** | Everything that filters the stream is in `?q=`, except two named defaults surfaced in standing chrome: the ProjectPicker's hidden `project_group` (pinned chip) and the meaningful default (named in the result-header scope phrase). No client-side filter may act without visible representation. |
| P4 | **Honest compression** | Anything folded says what it folded ("+N quieter events", "500 of many shown"). A fold inherits the loudest mark of its members — collapse must never bury a failure. Labels never attribute filter artifacts to the world (§4.2). |
| P5 | **Deterministic feed** | Every visible row provably matched the filter. No ranked/semantic rows blended in; semantic recall is linked, not mixed (honesty constraint). |
| P6 | **Interpretation is client, facts are server** | Grouping/folding/datelines are pure frontend libs over the fetched page (like lib/trajectory.ts). The backend grows only facts: grammar, time bounds, FTS, counts. |

## 4. The feed, redesigned

### 4.1 Session sections via stitched runs

Naive contiguous grouping is falsified by the measured corpus (§2: 309 runs / 772 DOM rows on
the newest-500 — *worse* than today). Sections therefore use **time-windowed session
stitching** in a new pure lib `frontend/src/lib/streamGroups.ts`:

```ts
segmentStream(items: EventFeedItem[], opts?: {stitchWindowMs?}): Segment[]
// Segment = { type:'day', label, noMatchLabel? }
//         | { type:'run', sessionId, source, cwd, sessionTitle, clientSessionId, rows: Row[] }
// Row    = { type:'event', item } | { type:'fold', items, mark, loudestTone, key }
```

Scanning newest→oldest, an event joins its session's most recently created run when the gap to
that run's oldest member is ≤ `STITCH_WINDOW` (15 min, a named constant) **and** both are on the
same local date; otherwise it starts a new run. Runs render in creation order (= order of their
newest members); rows inside a run render newest-first, matching the feed direction. The feed
becomes a sequence of session blocks ordered by latest activity — sessions are sections even
when agents interleave at sub-minute granularity, which the measurement shows is the dominant
case, not an edge case.

Segmentation is a `createMemo` over the already-filtered items — zero state, so SSE prepends,
pending-merge, dedupe, and cap trimming self-heal by recomputation. On live merge, a session's
new event joins its existing head-area run (moving that block to the top) or opens a new run;
block reordering happens only at merge time (when the user is at top or clicked the pill).

**Run header** (`components/events/RunHeader.tsx`, `.stream-run-head`): SourceDot · session
title or clientSessionId (12px, 500) · `truncatePath(cwd)` (mono 11px `--text-dim`) · event
count (`--text-faint`) · timespan of the run · a session-level "View session →" link.
`position: sticky; top: 0` with an opaque `--bg` background and a deliberate z-index scale
(header < N-new pill < popover) so the current session is always identified while scrolling.
This is the **only** place SourceDot and cwd render; rows beneath drop both (P2). A row whose
cwd differs from its run's shows its own cwd inline, dim. Runs of ≤2 raw events (counted
before folding) render a compact non-sticky inline variant **with the same context-zone
actions** (§7). A head run growing past 2 events flips compact→sticky; accepted, pinned as a
recompute test case.

**Run body** (`.stream-run-body`): 1px `--border` left thread line binding rows to their session.

**Acceptance gate (measure, don't assume):** the grouping slice re-runs the §2 measurement
script against a fresh `.backup` copy — newest-500 meaningful events must materialize as fewer
mounted rows than today's 500 flat rows, target ≤ ~400 collapsed (§15).

### 4.2 Datelines

Runs never span a local-date boundary (§4.1). Between runs, when the date changes:
`.stream-daybreak` — "Today" / "Yesterday" / "Mon Aug 18", `--text-faint` mono 10px uppercase
over a 1px rule. Gap annotation is **filter-honest** (P4): when `q` carries no visible filter
(hidden project scope alone is fine) and adjacent loaded items are >48h apart (the trajectory's
`GAP_HOURS` idiom), the dateline appends "· quiet 3d"; when any visible filter is active, the
phrasing is "· no matches for 3d" — the system wasn't quiet, the filter was. Per-row time goes
quiet: `<time>` stays (a11y, `dateTime`, ISO on `title`) at `--text-faint`, brightening on row
hover.

### 4.3 Row anatomy

All rows share one grid: `92px minmax(0,1fr) auto` — a fixed first column sized to the longest
KindBadge so headlines x-align across every row, the scan path unbroken.

**Chatter rows** (everything that isn't a landmark): the first column holds a `.kind-mark` —
right-aligned IBM Plex Mono 10px ink label, `--text-faint`, no pill, no color: `run`, `read`,
`edit`, `write`, `net`, `plan`, `mem`, `·` for generic. Labels derive from tool semantics in
the presenter layer (the tone system's vocabulary — theme.css:4905-4913 — extended with `edit`;
marks are ink-only, tone *color* remains the expanded card's business). **Failure exception:**
a row whose presentation tone is `error` (derived from payload — nonzero `exit_code`,
`is_error: true` — as presenters/bash.ts:19 already computes; extended to the other tool
presenters as a per-source adapter in the presenter layer) renders its mark in `--red`.
Failures are the only chatter that earns color (P1). This is client-derived interpretation
(P6); the collapsed row already invokes the presenter path for its headline, so the marginal
cost is parsing the tool payload once per row — measured in the ink slice, cached on the
presentation object. Headline: 13px Inter `--text`, with the machine-literal argument
(command/path/url from the presenter/`eventHeadline` path) in a `.headline-arg` mono span.
Rule: **bold = intent, mono = machine artifact, dim = context, faint = rhythm.**

**Landmark rows** (Decision/Handoff/Observation/UserPromptSubmit): the colored KindBadge in the
shared first column, headline `--text-bright` 600 13.5px, plus
`.stream-row--landmark { box-shadow: inset 2px 0 0 var(--kind-color) }` — a left rail crossing
the run thread line so the left margin is a strafe-scannable landmark column, the exact grammar
of trajectory nodes.

Expanded cards, EventRenderer, presenters, and ReaderText are unchanged, with two deliberate
exceptions: the expanded head drops its session *title* line (now on RunHeader), and it keeps a
precise **"Open at this event →"** link carrying `session=<sid>&event=<id>` — the §9 position
vocabulary. (The RunHeader link is session-level; the per-event link is contextual chrome on an
open card, not standing noise, and on the measured corpus most runs are short with non-sticky
headers, so the card must carry its own way out.)

### 4.4 Chatter folds

Inside a run, ≥4 consecutive chatter rows with the same `toolName`, **all effectively collapsed**
(global mode + override Set — an expanded row breaks the streak and folds its neighbors around
it; a fold may never swallow a card the user is reading), render as one `.stream-fold` row:
kind-mark · "Read ×12 — EventRow.tsx, theme.css, +10 more" · "over 3m". The fold is a
`button[aria-expanded]`; clicking unfolds in place and moves focus to the first revealed row.
Unfold state is a `Set<string>` keyed by the fold's **oldest member id** — stable under SSE
head-prepend (new arrivals extend the newest edge; the anchor at the old edge stays fixed).
Residual: pagination extending a fold backward changes its key and refolds it; accepted and
noted. The fold shows the **loudest tone** of its members: if it swallowed a failure, the mark
is red (P4). In global expanded mode chatter rows are effectively expanded, so folds don't
apply — "Expanded" means expanded; the §15 DOM budget is defined for collapsed mode.

### 4.5 Cap honesty, performance

No virtualization (decision). Stitched runs + folds must beat today's flat 500 (§4.1 gate);
collapsed rows additionally get `content-visibility: auto; contain-intrinsic-block-size: auto
34px` (today only expanded rows have it). At `MAX_ROWS` the silently-vanishing "Load more" is
replaced by an honest `.stream-endcap` terminal row: *"500 of many shown — refine the filter to
go deeper."*

### 4.6 Filter chrome

Standing chrome is **two quiet lines** (stated honestly): the search input line, and a
result-header line. Active-facet chips render only when set (chrome is earned like color):
facet chips, struck NOT-chips, clock-glyph chips for time tokens ("Past 2 hours ✕"), and a
**pinned project chip** from ProjectPicker state ("Project: sba-agentic ✕") so the hidden
`project_group` injection stays hidden from the editable text but never from awareness (P3).
The permanent QUICK_VALUES rails move into the `.suggest-popover`: typing `kind:` with an empty
prefix shows the static values; `tool:`/`project:`/`session:` suggest live values (§6.4). The
popover gains ArrowUp/Down/Enter keyboard support. An "Options" disclosure holds the density
toggle and the meaningful toggle (§5 `is:all`).

The result-header line reads `1,204 matches · meaningful · past 2 hours` — count from §6.5,
the standing "meaningful" word whenever the default filter is active (its visible
representation under P3), scope phrase from parsed time tokens. When counts are unavailable
(backfill, abort, error) the number is **omitted** — scope phrase only, never a stale count for
a different q. When `until:` bounds the query in the past, a quiet "live paused — historical
scope" badge appears and the N-new pill is suppressed (new events cannot match; never let live
behavior contradict the query). When q has free text, one quiet affordance under the header:
*"Ask memory about «text» →"* (§6.3).

Live machinery is unchanged (SSE wake hint → 500ms debounce → `since` refetch → near-top merge
/ N-new pill). A **persistent visually-hidden `aria-live="polite"` region** announces new-event
counts (the pill itself mounts conditionally and cannot be the live region). Feed semantics:
rows stay `<article>`s directly owned by the `role="feed"` container; run headers are their own
articles labeled by session; `aria-busy` is set during load-more.

## 5. Query grammar v2 (locked)

One grammar, specified once, implemented twice (Java + TS), locked by a shared fixture (§6.1).
Existing single-facet tokens keep their semantics: `source:|agent:`, `kind:|event_type:` (exact,
case-insensitive, on event_type), `tool:|tool_name:`, `project:|cwd:` (substring),
`project_exact:`, `project_group:` (hidden), `-`/`NOT` negation, quoted values.

New tokens:

| Token | Value forms | Semantics |
|-------|-------------|-----------|
| `session:<v>` | id string | exact match on session id **or** client session id, compiled as `e.session_id IN (SELECT id FROM agent_sessions WHERE id = ? OR client_session_id = ?)` — resolves through agent_sessions (which is small and uniquely keyed) so the event scan genuinely rides idx_agent_events_session_observed. The naive `OR` on agent_events provably table-scans (EXPLAIN: 208ms vs 0.1ms). |
| `since:<v>` | ISO date, ISO datetime, `today`, `yesterday`, `Nd/Nh/Nm/Nw` | `observed_at >= resolve(v)`; dates/keywords resolve to **start** of the named period |
| `until:<v>` | same forms | `observed_at <= resolve(v)`; dates/keywords resolve to **end** of the named period (`until:yesterday` includes all of yesterday; `until:2026-08-18` means `< 08-19T00:00`). Durations symmetric. Asymmetry pinned per-keyword in the fixture. |
| `last:<v>` | durations only | sugar for `since:now−v`, no upper bound |
| `is:all` | literal | disables the meaningful predicate. **Precedence: `is:all` in q always wins over `meaningful=true` on the wire** (pinned by an EventFeedTest case). Only `all` is recognized; other `is:` values stay free text |
| comma-OR | `source:codex,claude` | IN-list within one facet; repeated facet tokens union. Quoting suppresses splitting (`"a,b"` is one value), which requires the tokenizer to carry quoted-ness through to facet parsing (§6.1) |

**Free text** becomes per-term AND (each term an independent match, quoted phrases exact) —
a deliberate change from today's joined-single-phrase LIKE, implemented identically on both
the FTS path and the LIKE fallback (AND of per-term LIKEs over the same columns) so fallback
never changes semantics.

**Relative time resolves server-side at execution** against the server clock/zone (single-machine
tool; definitionally correct). A saved `last:2h` view is permanently alive. Pagination drift
(the window slides between "Load more" clicks) is accepted and documented; historical precision
is what absolute `since:`/`until:` are for. Explicitly **no** parentheses, no cross-field OR,
no regex, no other `is:` sugar.

Grammar tokens are independent of the wire params: `before` (keyset cursor) and `since` (SSE
head-window) keep their tested meanings; grammar time bounds compile into the WHERE clause.
`meaningful=` stays a raw API param defaulting false; the UI keeps default-true and expresses
opt-out as `is:all` in `q` — deep links reproduce what the sender saw.

**Example questions (acceptance targets, §16.2):**

| Question | q |
|----------|---|
| What did codex do in this repo yesterday? | `source:codex since:yesterday until:yesterday` + project chip |
| All Decisions this week | `kind:Decision last:7d` |
| When did that test start failing? | `"AssertionError" is:all` (FTS reaches tool output) |
| What happened in session Y? | `session:472c691b-…` |
| Everything Claude and Codex wrote to disk today | `source:claude,codex tool:Write,Edit since:today is:all` |

### 5.1 Compatibility with existing q strings

Grammar v2 changes the meaning of three existing shapes, deliberately (each gains legacy-shape
cases in the fixture so both parsers flip in the same commit):

1. Repeated facet tokens: last-one-wins (documented in EventFeedQuery.java:17-18) → **union**.
2. Unquoted comma inside a facet value: one literal value → **IN-list**.
3. `session:` / `since:` / `until:` / `last:` / `is:` prefixed tokens: free text → **operators**.

The UI never emitted any of these; the blast radius is hand-typed or agent-crafted strings,
accepted knowingly.

## 6. Backend work

### 6.1 One parser: the `query` module

New top-level Modulith module `dev.nathan.sbaagentic.query` — pure, dependency-free, root-API
only: `EventQuery.parse(String)` → typed accessors (`values(Field)`, `excluded(Field)`,
`sessionRef()`, `sinceSpec()/untilSpec()` returning a `TimeSpec` of absolute|duration|keyword
resolved later with an injected `Clock`, `includeAll()`, `freeTerms()`, `projectGroups()`).
The tokenizer emits `{raw, wasQuoted}` so quoted-ness survives to facet parsing (comma rule,
§5) — mirrored in the TS tokenizer, whose current implementation strips quotes before facet
parsing (query.ts:108-138) and must be restructured the same way. SQL/ES compilation stays in
the owning modules.

`recording.EventFeedQuery` and `memory.QueryFacets` are deleted; `RecordingSqlStore`,
`EventController`, `SearchService`, `MemorySqlQueryAdapter` consume the `query` root API.
Adding a module is not free: the same slice updates
`ApplicationModuleStructureTest`'s `containsExactlyInAnyOrder` list (:17-21),
`PackageArchitectureTest.FULLY_MIGRATED_MODULES` (:35-36), and the intended-modules list in
docs/architecture/package-conventions.md:53. `frontend/src/lib/query.ts` remains the one
mirror; both are pinned by one golden fixture `src/main/resources/query/grammar-cases.json`
(input → expected facets/excludes/session/time/text/isAll, including quoting/comma/legacy
cases and per-keyword until: resolution), driven by a parameterized JUnit test and a vitest
suite importing the same file. **Every grammar change appends fixture cases in the same
commit.** The hidden-injection helper `quoteHiddenFacet` (StreamPage.tsx:412-414) adds `,` to
its quote-trigger class in the comma slice, or a canonicalKey containing a comma would split
into bogus project groups.

### 6.2 Feed SQL extensions (recording)

- `session:` → the agent_sessions-resolved IN-subquery of §5 (no new index needed).
- time bounds → `e.observed_at >= ?` / `<= ?`, composing with the keyset cursor (cursor is
  pagination position; `since:`/`until:` are query intent), served by idx_agent_events_observed.
- comma-OR → `IN (?,…)` / `NOT IN`; project comma-values OR-chain the existing cwd LIKEs.
- `is:all` → skip MEANINGFUL_EVENT_PREDICATE, overriding `meaningful=true` (§5 precedence).
- free text → AND of per-term predicates (FTS or LIKE, §6.3).

Cursor format, DESC/DESC ordering, error envelope, and `limit` clamping are untouched.
EventFeedTest gains cases for each new predicate, their composition with cursor + meaningful,
the `is:all`-vs-param precedence, and FTS-on/FTS-off returning identical rows for a two-term
query.

### 6.3 FTS5 (full-text that actually indexes)

Contentless FTS5 with delete support, keyed to `agent_events.rowid`:

```sql
CREATE VIRTUAL TABLE event_fts USING fts5(text, tool_name, extra, content='', contentless_delete=1);
```

`extra` = `substr(tool_output_json,1,6000) || substr(metadata_json,1,4000) || substr(tool_input_json,1,2000)`
(space-joined, null-coalesced) — **output first**: failures live in output, and measured on the
live corpus 3,246 events have inputs alone ≥8k (an input-first clip would exclude their output
entirely). metadata_json keeps landmark structured fields searchable, matching the LIKE
fallback's columns. Per-side budgets documented and tunable via rebuild.

- **DDL runs programmatically**, not in schema.sql: Spring's script runner splits on `;` and
  would break trigger bodies, and a hard CREATE VIRTUAL TABLE in schema.sql would fail startup
  on an FTS5-less driver instead of failing soft. The recording module's existing
  `ensureSchema()` @PostConstruct precedent (RecordingSqlStore.java:~75-105) hosts a guarded
  try-create probe; on failure, FTS is marked unavailable and everything falls back to LIKE.
  (FTS5 is compiled into the bundled sqlite-jdbc 3.45.3.0 — verified — so the probe is a
  safety net, not the expected path.)
- **Triggers cover INSERT, DELETE, and UPDATE** on agent_events (delete/update via
  `contentless_delete=1`). This makes the 8 integration-test classes that `DELETE FROM
  agent_events` between tests consistent by construction, and closes the empirically
  demonstrated wrong-rows failure (rowid reuse after DELETE returns *different events* from
  MATCH, not just misses).
- **Named invariant: no `VACUUM` on the live DB without an FTS rebuild.** agent_events has a
  TEXT PK, so its implicit rowids may be renumbered by VACUUM, silently remapping every FTS
  hit. Stated here and in docs/architecture.md when this ships.
- **Rebuild command** = `INSERT INTO event_fts(event_fts) VALUES('delete-all')` + the chunked
  backfill re-scan. The backfill (10k rows/batch, progress in `search_index_state`, background
  post-startup) and the rebuild are the same job.
- Free-text query path when FTS is ready: `AND e.rowid IN (SELECT rowid FROM event_fts WHERE
  event_fts MATCH ?)` with per-term `"term"*`, implicit AND; composes with every other
  predicate. **Fail soft** to the per-term LIKE path (§5 semantics, identical results modulo
  the FTS-only `extra` coverage of tool JSON — stated, not hidden) on any FTS error or during
  backfill.
- FTS is a rebuildable secondary inside canonical SQLite; the trigger fires in the same
  transaction as the canonical INSERT (an internal index, not fan-out).

**Elasticsearch is demoted off the Stream path entirely**: the Stream speaks only SQLite
(LIKE→FTS5), deterministic and always-on. ES keeps its existing `/api/search` role; no Stream
dependency on ES health, no ES schema or backfill work.

**Semantic recall is linked, not blended**: the "Ask memory →" affordance (§4.6) navigates to
the Ask surface prefilled, hitting the existing recall path over structured intent + summaries
only. No semantic rows in the feed, ever (P5, honesty constraint).

### 6.4 Autocomplete

`FACET_FIELDS` grows `session`, `since`, `until`, `last`, `is`. Time fields get static value
suggestions (`last:1h`, `last:24h`, `last:7d`, `since:today`, `since:yesterday`).
`tool:`/`project:` populate from the facet-counts endpoint's top values under the current query
(falling back to `/api/search/values` prefix lookup); `session:` suggests recent sessions
(title + relative time) from the existing sessions listing.

### 6.5 Query-scoped facet counts

`GET /api/events/facets?q=&meaningful=` (recording, EventController sibling), reusing the
feed's predicate builder. Per counted field (source, event_type, tool_name, cwd) the WHERE
drops that field's own include list (counts answer "what if I switched"); one `GROUP BY` per
field plus a total. Response `{total, fields: {source:[{value,count}], kind:[…], tool:[…],
project:[…]}}`, house error envelope. New index `idx_agent_events_tool_observed (tool_name,
observed_at DESC)`. Frontend: 300ms debounce, abort in-flight on q change; counts feed the
result header, the popover suggestions, and an on-demand counted browser opened from the match
count — **no standing counted rail**. While FTS backfill runs and free text is present, counts
are skipped (values render uncounted; header omits the number per §4.6).

### 6.6 Route promotion (partial slice-6 absorption)

`/stream` becomes a real route: added to the SpaForwardingController.java:15 whitelist and the
Solid router, rendering ActivityPage in stream mode. `/` keeps rendering the stream directly
(this **supersedes** the 2026-07-28 §4.10 decision that `/` would redirect to `/stream`; see
§11) and `?view=stream` links keep working. On `/stream`, the Browse/Ask mode tabs `navigate()`
to `/?view=browse` / `/?view=ask` instead of `setParams` — `/stream` never renders a non-stream
mode at a lying address. `/browse` and `/memory` promotion are **not** absorbed.
`http://localhost:8766/stream?q=session:X since:yesterday` is the deep-link currency this
design mints.

## 7. One filter language (the rogue filter dies on Stream)

`sourceFilter`'s actual consumer roster (grep-verified): StreamPage, SessionsPage (3 sites),
RecallPage, GraphPage, SourceChips, `createSessionsResource`, plus two test files. Therefore:

- Stream stops applying it (`filteredItems` collapses to `items()`; the canLoadMore
  phantom-vanish bug disappears structurally).
- The header Sources menu becomes **mode-aware by contract**: on the Stream it derives its
  checkmarks from `parseQuery(q).source` and writes through `setFacet` (comma multi-value);
  on Sessions/Recall/Graph it keeps the existing client-side signal behavior unchanged. The
  split is deliberate, named, and temporary — migrating the other surfaces to q is a recorded
  follow-up, not this spec.
- Per-run affordances (on both header variants, §4.1): **"Filter to this session"**
  (`session:<id>` via setFacet) and **"Copy link"** (absolute `/stream?q=…` URL). Expanded
  cards keep the precise "Open at this event →" (§4.3).

## 8. Saved views

- Built-in presets in a "Views" dropdown beside the input — expressible in the locked grammar
  and doubling as documentation: `Decisions this week` (`kind:Decision last:7d`), `Handoffs`
  (`kind:Handoff last:7d`), `Codex right now` (`source:codex last:2h`), `Prompts today`
  (`kind:UserPromptSubmit last:24h`). (An "Errors today" preset is **not** shippable: zero
  error-shaped event_types exist and failure-ness is payload-derived client-side (§4.3). A
  server-side derived error flag/facet is a recorded follow-up, out of scope, §12.)
- User views: localStorage `blackbox.savedViews` `[{name, q, createdAt}]`, "Save current
  view…" when q is non-empty. A view **is** a q string, hence also a copyable URL. Server
  resolution of relative time keeps saved views permanently live. No server-side store.

## 9. Trajectory loop (the two views admit they're one truth)

- **Fix the known flaw:** trajectory selection URL-syncs as `?focus=<nodeId|capture:<id>>` on
  ProjectsPage (TrajectoryView reads selection from the URL; `capture:<id>` resolves to the
  containing node).
- Trajectory detail rail gains **"View in Stream"** on burst/head/deep-past nodes →
  `/stream?q=` with the project scope + `since:<burst start> until:<burst end>` (absolute ISO).
- Expanded Decision/Handoff/Observation cards gain a **"Trajectory"** link →
  `/projects/<projectKey>?focus=capture:<eventId>` (projectKey resolved from the row's cwd via
  the existing catalog; link omitted when unresolvable).
- App-wide deep-link vocabulary, stated as contract: **`?q=` = filter · `?focus=` = selection ·
  `/?view=browse&session=&event=` = position.** Surfaces adopt it as they are touched.

## 10. Decisions table

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Chatter loses colored badges; landmarks + failures + live + active chips are the entire color budget | The screenshot's wall of identical pills is the core legibility failure |
| D2 | Group by **time-windowed stitched** session runs (15-min window, date-bounded), client-side pure lib | Contiguous grouping measured 309 runs / 772 DOM rows on the live newest-500 — worse than flat; stitching makes sessions sections despite sub-minute interleave. Grouping slice re-measures as an acceptance gate |
| D3 | Failure marking derives from presenter tone (payload: exit_code/is_error), not event_type | Zero error-shaped event_types exist in 286k events; the payload channel is the one that's real. Folds inherit the loudest tone |
| D4 | No virtualization; keep MAX_ROWS=500 + honest endcap | Grouping must beat flat-500 by measurement; a hand-rolled virtual list is the riskiest possible code for one localhost user |
| D5 | One grammar in a new pure `query` module; delete the two Java twins; TS mirror pinned by a shared JSON fixture; module-list tests updated in the same slice | Three drifting copies today; fixture turns drift into a test failure |
| D6 | Grammar v2 = `session:`, `since:/until:/last:`, comma-OR, `is:all`, per-term free text; **no** boolean algebra; compatibility breaks enumerated (§5.1) | Covers every §5 example question; expression grammars are parser tax with no observed need |
| D7 | Relative time resolves server-side at execution; `until:` dates resolve to period **end** | A saved `last:2h` means "the last two hours whenever asked"; `until:yesterday` must include yesterday |
| D8 | FTS5 contentless+`contentless_delete=1`, INSERT/DELETE/UPDATE triggers, programmatic DDL with probe, backfill-as-rebuild, output-first clips, no-VACUUM invariant, fail-soft to semantically-identical per-term LIKE | Indexed full text without ES; tool output searchable for the first time; the empirically shown wrong-rows desync and the schema.sql splitter problem are designed out |
| D9 | ES off the Stream path; semantic recall linked ("Ask memory →"), never blended | Determinism (P5) + the AGENTS.md honesty constraint verbatim |
| D10 | Query-scoped facet counts endpoint; counts in header/popover/on-demand browser, no standing rail; count omitted when unavailable | Instrument predictability without standing chrome, without stale numbers |
| D11 | `sourceFilter` dies on Stream; Sources menu is mode-aware (q on Stream, signal elsewhere); other surfaces migrate later | Unanimous across design lenses; the real consumer roster (5 surfaces) makes global deletion out of scope |
| D12 | Promote `/stream`; `/` renders the stream in place (supersedes the prior redirect decision); mode tabs navigate off `/stream` | Deep links need a stable noun that never lies about what it renders |
| D13 | Saved views = static presets + localStorage; no server store | One user, one machine; a view is a q string |
| D14 | Trajectory loop: `?focus=` sync, View-in-Stream, Trajectory links, per-event position links kept on cards | Makes raw-vs-interpretation navigable both ways; fixes a named flaw without losing position precision |
| D15 | Density rail (SVG histogram strip) **parked**, not built | The trajectory owns the overview identity; time tokens + counts answer "where is the signal" |
| D16 | `meaningful` stays a raw API param (default false); UI expresses opt-out as `is:all`; `is:all` beats the param; the active default is named in the result header | Raw consumers never silently filtered; deep links reproduce what the sender saw; P3 kept honest |

## 11. Relationship to prior specs

| Prior decision | This spec |
|----------------|-----------|
| 2026-07-28 §13 slice 4 (live/process monitor) | Untouched, stays open |
| 2026-07-28 §13 slice 5 (turn grouping, presenters, derived titles) | Partially absorbed: session titles surface on RunHeader; turn grouping explicitly **not** absorbed (stitched session runs instead, D2); missing presenters stay open, though D3's failure-tone derivation extends existing presenters |
| 2026-07-28 §13 slice 6 (route promotion, /memory merge) | Partially absorbed: `/stream` promoted (D12); `/browse`, `/memory` stay open |
| 2026-07-28 §4.10 `/` **redirects** to `/stream`; ActivityPage stops being a mode shell | **Superseded**: `/` renders the stream in place; ActivityPage remains the mode shell for now (D12) |
| 2026-07-28 phase-1 non-goals (no new deps, no token-level syntax highlighting, presenters never throw/regress) | Still binding |
| 2026-07-01 deferrals (facet counts, virtualization, canonical project keys) | Counts now built (D10); virtualization still rejected (D4); canonical keys still deferred |
| 2026-07-01 sourceFilter-as-client-post-filter | Superseded on Stream (D11) |

## 12. Out of scope

Browse/Ask surfaces (beyond the Ask prefill link and mode-tab navigation), the Board, slice 4
process monitor, new presenters beyond failure-tone adapters, turn grouping, ES changes,
virtualization, boolean query algebra, server-side saved views, the density rail (parked, D15),
light theme, canonical project keys on events, migrating Sessions/Recall/Graph off
`sourceFilter` (named follow-up), a server-side derived error flag/facet (named follow-up),
prefix-matching for `session:` (follow-up if exact-match friction shows up).

## 13. Risks and gotchas

- **Sticky run headers × N-new pill × expanded cards** need one deliberate z-index scale and an
  opaque header background (§4.1).
- **StreamPage.test.tsx assumes 1:1 items→rows** (16 tests). The segment memo lands with
  `streamGroups.test.ts` plus updated page tests in the same slice, or the suite goes red.
- **Stitched-run block reordering on live merge** can move a block the user is reading if they
  merge while mid-list; merge only happens near-top or on pill click today, which bounds the
  exposure — verified in live use before tuning.
- **FTS invariants**: no VACUUM without rebuild (D8); rebuild is delete-all + rescan; the FTS
  store tests must cover the delete + rowid-reuse case explicitly.
- **FTS5 backfill size/time on 286k rows is estimated, not measured** — the FTS slice measures
  on a `.backup` copy before enabling by default (budget §15).
- **Tokenizer quoted-ness** must be carried through both parsers or comma-OR silently breaks
  quoted values; `quoteHiddenFacet` gains `,` in the same slice (§6.1).
- **Failure-tone derivation cost**: parsing tool payloads per collapsed row — cached on the
  presentation object, measured in the ink slice before folds build on it.
- **Sources-menu split-brain** (q on Stream, signal elsewhere) is deliberate and temporary;
  ActivityPage.test.tsx and SessionsPage.test.tsx touch sourceFilter and are updated with it.
- **Kind-mark demotion may overshoot** — judged in live use after the ink slice; the mark
  vocabulary keeps tools distinguishable by text.
- **Any `mvn package` overwrites the live jar** — `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic` after builds.
- **New route must be whitelisted** in SpaForwardingController.java:15 or hard-refresh 404s.

## 14. Testing by layer

| Layer | What pins it |
|-------|--------------|
| Grammar (Java) | Parameterized JUnit over `grammar-cases.json`: every token, negation, quoting incl. comma suppression, comma-OR, repeated-token union, legacy shapes (§5.1), per-keyword since/until resolution, `is:all`, session refs, per-term free text |
| Grammar (TS) | Vitest over the same fixture; parse∘serialize round-trip |
| Feed SQL | EventFeedTest additions: session subquery, time bounds, IN predicates, composition with cursor + meaningful, `is:all` beats `meaningful=true`, FTS-on = FTS-off rows for two-term free text |
| FTS5 | Store tests: trigger population (insert/delete/update), delete + rowid-reuse consistency, MATCH vs LIKE fallback parity, backfill idempotency/progress, probe-failure skip |
| Facet counts | Controller/store tests: scoped counts, drop-own-field semantics, empty-query totals, envelope |
| Modulith/Arch | `ApplicationModules.verify()` green with `query` added to the pinned module lists (ApplicationModuleStructureTest, PackageArchitectureTest, package-conventions.md) |
| streamGroups | Pure unit tests: stitching window + date bounds, run ordering, datelines + filter-honest gap labels, folds (threshold on effectively-collapsed rows, expanded row breaks streak, loudest tone, oldest-id keys stable under prepend), compact↔sticky flip recompute |
| StreamPage | Updated page tests: segments render, chips↔q round-trip incl. new tokens, pinned project chip, endcap, live-paused badge, count-omitted fallback, persistent live region, popover keyboard nav |
| Sources menu | Checkmarks derive from q on Stream; write-through via comma multi-value; signal behavior unchanged elsewhere (ActivityPage/SessionsPage test updates) |
| Routing | `/stream`, `/`, and `?view=stream` all render the stream; mode tabs navigate off `/stream`; hard-refresh via whitelist (E2E cold load) |
| E2E (packaged) | Deep-link `/stream?q=…` cold load; filter-to-session; save/apply a view; live merge under an active time filter; **fold honesty: seed a failing bash event (exit_code 1) inside a same-tool streak and assert the fold's red mark** |
| Live use | Real `:8766` pass on the production corpus: scan test (find the newest Decision from across the room), question test (each §5 example returns correct rows in one query), grouping gate (§4.1 measurement beats flat-500) |

## 15. Performance budget (measure, don't assume)

| Thing | Budget | How verified |
|-------|--------|--------------|
| Mounted DOM rows at cap (collapsed) | < 500 (beat today), target ≤ ~400 | §4.1 measurement script on a fresh `.backup` copy, run in the grouping slice |
| FTS5 backfill (286k rows) | one-time, background, < ~5 min; DB growth measured before default-on | timed run on a `.backup` copy |
| FTS MATCH free-text feed query | < 50 ms p95 on live corpus | curl timing vs the LIKE baseline |
| Facet counts (4 GROUP BYs) | < 100 ms without free text; skipped during backfill with free text | curl timing on live corpus |
| Segment memo (500 items) | < 5 ms per recompute | unit-level micro-check, no jank on live merge |
| Failure-tone derivation (500 rows) | no measurable scroll/merge jank | measured in the ink slice, cached per presentation |

## 16. Acceptance criteria

1. A screen of mixed-agent activity reads as session sections with landmarks: stitched runs
   under headers, chatter in ink, Decisions/Handoffs/Observations/prompts and failures the only
   color — verified against the live corpus, and the §4.1 measurement beats today's flat 500.
2. Every §5 example question returns correct rows in one `q` string, and the URL reproduces the
   exact view (including meaningful state) on cold load.
3. No filter acts invisibly: beyond the two named standing defaults (pinned project chip,
   "meaningful" in the result header), every active filter renders as a chip; Load more never
   vanishes due to hidden filtering.
4. Free text matches tool output (FTS5) and falls back to LIKE with identical per-term
   semantics when FTS is absent.
5. A trajectory burst opens as a time-scoped stream slice; a capture card links back to its
   trajectory node; trajectory selection survives reload; expanded cards keep per-event
   position links.
6. `mvn test`, frontend vitest, `npm run build`, and the packaged E2E suite are green; the
   grammar fixture runs on both sides; the module-list tests include `query`.
7. Live verification on the production corpus passes the §14 scan/question/grouping-gate tests;
   the seeded E2E fold-honesty test passes.

## 17. Delivery slices (value order)

| # | Slice | Contents | Depends on |
|---|-------|----------|------------|
| 1 | Grammar v2 + one parser | `query` module (+ module-list test/doc updates), delete twins, session/time/comma/`is:all` SQL, per-term free text on LIKE, fixture + both runners, chip rendering for new tokens, `quoteHiddenFacet` comma fix | — |
| 2 | One visible filter language | sourceFilter off Stream + mode-aware Sources menu, pinned project chip, `/stream` route + tab navigation rule, meaningful→`is:all` UI + result-header naming, live-paused badge, filter-to-session + copy link | 1 |
| 3 | Ink hierarchy | kind-marks (shared 92px column), landmark rails, failure-tone red (presenter adapters + cost measurement), mono args, quiet time | — |
| 4 | Sections & datelines | streamGroups.ts (stitching), RunHeader (sticky + compact variant with actions), thread line, filter-honest datelines, drop repeated dot/cwd, **measurement gate** | 3 |
| 5 | Folds & honesty | chatter folds (oldest-id keys, expanded-breaks-streak, loudest tone), endcap, collapsed content-visibility | 4 |
| 6 | Chrome calm | two-line chrome, chips-on-active, QUICK_VALUES→popover + keyboard nav, Options disclosure, persistent live region, feed/article semantics | 2,3 |
| 7 | FTS5 | programmatic DDL + probe, triggers (I/D/U), backfill/rebuild, MATCH path + parity fallback, measured budget | 1 |
| 8 | Counted instrument | facets endpoint + tool index, result header counts w/ omitted fallback, popover counts, on-demand browser, autocomplete upgrades | 1 |
| 9 | Views & memory handoff | presets, localStorage views, "Ask memory →" | 1,2 |
| 10 | Trajectory loop | `?focus=` sync, View-in-Stream, Trajectory links | 1,2 |

Each slice is independently shippable and verified (tests + live use) before the next begins.
