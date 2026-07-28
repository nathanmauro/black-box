# Black Box Consolidation — The Stream Becomes The Product

**Date:** 2026-07-28
**Status:** Design, awaiting approval
**Phase 1 scope:** implementation-ready. Phases 2 and 3 are sketched to fix the seams only.

---

## 1. Problem

Black Box has become Nathan's default place to watch agents work, and it is not good enough at
the one job it is now used for. Three complaints, in his words:

1. The stream should have an **expanded-by-default mode** — a toggle.
2. Rendering should be **readable, formatted, and colored**, rich enough to follow an agent's
   behaviour, **without sacrificing much performance**. It should have **file links**.
3. **Recall's memory links go nowhere.**

Behind those sits a broader one: *"there are too many disjointed things in blackbox right now…
I really need to polish and make what is good good."* And a stated direction: bring the good parts
of **Agent Observatory** (`~/Developer/proj/agent-observatory`) into Black Box, then expose Black
Box's APIs to other tools (`ask-my-history`, and others).

## 2. What the survey found

### 2.1 The board was never the weight

Black Box has 11 routes, 5 in the nav. Observatory has 7 routes, 7 in the nav. Observatory is not
lighter because it does less. It is lighter for three mechanical reasons:

- **Two npm dependencies** (SolidJS + router) and one hand-written 845-line CSS file. ~2,500 LOC of
  frontend total.
- **The client is deliberately dumb.** All expensive work lives in a Go indexer that never re-reads
  a byte. The server ships at most 100 events, so no virtualization is needed.
- **Rendering is restrained.** Native `<details>` collapsed by default, a character count in every
  summary (`Output (18,003 chars)`), semantic color pills, one color per agent, **zero icons**.

Nathan was offered the choice to park the board and chose *"keep it, just fix the stream first."*
That decision is adopted here: **nothing is parked; the stream is prioritized.**

### 2.2 The trap in "port Observatory's renderer"

Observatory has **no per-tool rendering** — `Bash`, `Edit`, `Read`, and `WebFetch` all render as the
same pretty-printed JSON blob. No diffs. No syntax highlighting. No markdown. **No file links at
all** (verified: zero hits for `vscode://`, `cursor://`, `file://`, clipboard APIs). It parses token
usage, model name, and stop reason off the wire and **discards all three** before they reach its DB.

The rich readable thing Nathan wants exists in **neither** application. Observatory's contribution is
*discipline and ingestion architecture*, not a renderer.

### 2.3 Black Box already stores everything the renderer needs

Measured against the live database on 2026-07-28 (read-only):

| Fact | Value |
|---|---|
| Events / sessions | **239,501 / 3,674**, current through 21:22 tonight |
| Events with `tool_input_json` | **218,012** |
| Events with `tool_output_json` | **209,002** |
| Events carrying `transcript_path` in metadata | **231,183** |
| Events carrying `"model"` in metadata | **112,921** |

Tool distribution (top): `Bash` 147,026 · `Read` 18,061 · `WebFetch` 8,498 · `apply_patch` 8,063 ·
`WebSearch` 6,518 · `StructuredOutput` 4,995 · `Edit` 3,867 · `Write` 2,989 · `ToolSearch` 2,910 ·
`update_plan` 2,380 · `Grep` 480.

Payload shapes, sampled from live rows:

- `Edit` → `{"file_path":"…","old_string":"…","new_string":"…"}` — **a diff, already stored.**
- `apply_patch` → `{"command":"*** Begin Patch\n*** Update File: /abs/path\n@@\n…"}` — **already a
  unified patch**, carrying its own absolute file path. No computation required.
- `Bash` → `{"command":"…"}` — a headline and a highlightable body.
- `update_plan` → `{"explanation":"…","plan":[{"step":"…","status":"completed"},…]}` — **a
  ready-made narrative of what the agent is trying to accomplish**, currently rendered as JSON.
- `Read`/`Write`/`Edit` all carry `file_path` — **file links, already stored.**

**The stream feels thin because the renderer discards a fully-formed structured record it already
holds.** That is why this work is cheap: it is a rendering job, not a data job.

### 2.4 What exists in the frontend today

- `frontend/src/components/events/ToolPayload.tsx` (206 LOC) already does **good generic** payload
  work: two-pass JSON deserialization, field ordering by `INPUT_PRIORITY`/`OUTPUT_PRIORITY`,
  extraction of Codex's `Exit code:\nWall time:\nOutput:` result format into structured fields, and
  JSON pretty-printing. **This is kept as the fallback path for unknown tools.**
- `frontend/src/components/events/EventRow.tsx` (130 LOC) has one ad-hoc per-tool special case
  (`apply_patch` → `Patch <file>` at `:122-125`) and otherwise falls back to `PRIMARY_KEYS`
  heuristics. `ReaderText` (`:74`) owns a private `createSignal(false)`.
- `frontend/src/components/events/StreamRow.tsx` (56 LOC) is the row; it already renders a
  `View session →` link and delegates the body to `EventRenderer`.
- `frontend/src/pages/StreamPage.tsx` (401 LOC) holds `expandedId` as a **single** signal
  (`:50`) — only one row can be open at a time — with resets at `:82`, `:95`, `:103`.
  `FEED_LIMIT = 100`, `MAX_ROWS = 500` (`:10-11`). No virtualization; a manual "Load more".

The gap is precisely: **typed, tool-aware rendering on top of a working generic layer.**

### 2.5 Recall links go nowhere because there is no link

`frontend/src/pages/RecallPage.tsx` `RecallCard` (`:167-206`) contains **zero `href`**, imports
neither `A` nor `useNavigate`, and never reads `props.item.eventId`. Underneath:

- `RecalledItem` (`src/main/java/…/memory/RecalledItem.java:15-35`) carries `eventId` and
  `clientSessionId` but **not** `agent_sessions.id`. Every working deep link in the app keys on the
  internal id, and `agent_sessions` is `UNIQUE(source, client_session_id)` — so a link built from
  `clientSessionId` alone would not resolve.
- There is **no `GET /api/events/{id}`**, so the frontend cannot resolve `eventId → sessionId`
  client-side either. `BoardPage.tsx:614-619` works around its absence with a full-year
  `recallHandoff(...)` scan plus a client-side `.find()`.

### 2.6 Observatory has already lost the read plane

Black Box: 3,674 sessions / 239,501 events, current tonight. Observatory: ~2,163 / ~138,805, with
its RAG and session-search work sitting **uncommitted** on `feature/correlation-substrate`.
There is nothing to migrate. Observatory becomes a parts donor and is then switched off, ending the
cost of two multi-gigabyte SQLite indexes and two indexers on the same laptop.

The one thing Observatory genuinely does better is **ingestion model**: it *pulls* by tailing
`~/.claude/projects` and `~/.codex/sessions` off disk; Black Box *pushes* via hooks and therefore
only sees instrumented agents. In practice this matters less than it appears — see §9.

## 3. Decisions taken

| Decision | Choice | Rationale |
|---|---|---|
| Board / tasks / specs / runner | **Keep, unchanged** | Nathan's explicit answer. The board is not the source of the sprawl; priority was. |
| `/graph` (SVG constellation, stale, unreachable) | **Park knowingly — do not delete** | It is the only graph substrate in the repo and the seed for the shaped `agent-observatory-session-mindmap` "Tree of Souls" backlog item. |
| `/stats`, `/overview` | **Delete** | Orphaned, unreachable, superseded by ActivityPage. |
| Ask + Recall | **Merge into one Memory surface** | They are the same act — querying memory, one structured, one lexical+synthesis. Ends a hidden `?view=` route. |
| Default editor | **`cursor`** | Installed at `~/.local/bin/cursor`; `code`, `idea`, `zed` allowlisted and configurable. |
| Watch modes to serve | **All four** — live, narrative, forensic in Phase 1; cost re-homed to Phase 2 | Nathan selected all four. Cost's mechanism (transcript reading) is Phase 2's machinery — see §4.8. |
| Spec depth | **Phase 1 deep, Phases 2–3 sketched** | Nathan's explicit answer. |

## 4. Phase 1 — architecture

### 4.1 The presenter layer

**Problem it solves:** per-tool knowledge is currently ad-hoc, inline, and untestable
(`EventRow.tsx:122-125` is the only example, and it is a regex inside a headline function).

**Design:** a registry of pure functions in `frontend/src/lib/presenters/`, one module per tool,
mapping a raw event to a typed presentation. No DOM, no signals, no I/O — unit-testable against
golden fixtures extracted from the live corpus.

```ts
// frontend/src/lib/presenters/types.ts
export type Tone = "neutral" | "run" | "read" | "write" | "net" | "plan" | "memory" | "error";

export type InlineSpan =
  | { kind: "text";     text: string }
  | { kind: "code";     text: string }
  | { kind: "fileLink"; label: string; ref: CodeReference }
  | { kind: "url";      href: string; label: string };

export type DetailBlock =
  | { kind: "diff";     file: string; ref: CodeReference | null; hunks: Hunk[] }
  | { kind: "bash";     command: string; cwd: string | null; output: string | null; exitCode: number | null; wallTime: string | null }
  | { kind: "plan";     explanation: string | null; steps: PlanStep[] }
  | { kind: "code";     lang: string; text: string; ref: CodeReference | null }
  | { kind: "markdown"; text: string }
  | { kind: "json";     value: unknown }
  | { kind: "text";     text: string }
  | { kind: "fallback"; inputJson: string | null; outputJson: string | null };  // → ToolPayload

export type Presentation = {
  kindPill: { label: string; tone: Tone };
  headline: InlineSpan[];
  blocks:   DetailBlock[];
  sizes:    { inputChars: number; outputChars: number };
  refs:     CodeReference[];
};

export type Presenter = (event: AgentEvent) => Presentation;
```

**Registry contract:** `presenterFor(toolName, eventType): Presenter`. Lookup is by normalized
tool name (lowercased, `mcp__x__y` stripped to `y`). An unrecognized tool resolves to the
**generic presenter**, which emits a single `{kind:"fallback"}` block rendered by the existing
`ToolPayload` component. **No tool ever renders worse than it does today.**

**Why this shape:** each presenter answers the three isolation questions cleanly — what it does
(one tool → one presentation), how you use it (call it with an event), what it depends on (the
event record and nothing else). Components become dumb renderers of `DetailBlock`, which means the
rendering surface is a fixed set of ~7 block components regardless of how many tools are added.

**Visual system.** Colour carries semantics, not decoration. `Tone` is the only colour vocabulary a
presenter may express; the mapping to CSS custom properties lives in `theme.css` and nowhere else,
so no presenter or component hard-codes a colour.

| Tone | Meaning | Tools |
|---|---|---|
| `run` | executed something | Bash, `apply_patch` execution |
| `read` | observed without changing | Read, Grep, ToolSearch |
| `write` | changed the working tree | Edit, Write, apply_patch |
| `net` | left the machine | WebFetch, WebSearch |
| `plan` | intent, not action | update_plan, Task spawn |
| `memory` | Black Box's own captures | Decision, Handoff, Observation |
| `error` | non-zero exit, failure | any tool with a failure result |
| `neutral` | everything else | fallback |

Three rules carried over from Observatory, because they are the mechanism behind "feels light":
**no icons or emoji anywhere** — identity is carried by 6–8px colour dots and text pills;
**one colour per agent source**, applied identically in the dot, the badge, and the process panel;
and **a size label on every collapsed heavy block**.

### 4.2 Per-tool presenters (Phase 1 set)

Nine presenters cover **~97% of the 239k-event corpus**:

| Presenter | Events | Headline | Blocks |
|---|---|---|---|
| `bash` | 147,026 | first non-comment line of `command`, `+N lines` suffix | `bash` (command, cwd, output, exit code, wall time) |
| `read` | 18,061 | `fileLink` of `file_path` + line range if present | `code` with language inferred from extension |
| `webFetch` | 8,498 | `url` span | `markdown` of the fetched content |
| `applyPatch` | 8,063 | `Patch <fileLink>` — path parsed from `*** Update File:` | `diff` parsed directly from the stored patch |
| `webSearch` | 6,518 | the query | `text` results |
| `edit` | 3,867 | `fileLink` of `file_path` | `diff` computed from `old_string`/`new_string` |
| `write` | 2,989 | `fileLink` of `file_path` | `diff` rendered as all-additions |
| `grep` | 480 | pattern + path | `text` matches |
| `updatePlan` | 2,380 | `explanation`, else `N steps · M done` | `plan` with per-step status |

Plus `task`/subagent spawn (links to the child session via existing `session_links`), and the
generic fallback. `StructuredOutput` (4,995) and `ToolSearch` (2,910) use the fallback deliberately —
they are agent-internal plumbing, not behaviour worth surfacing.

**Explicit non-goal:** exhaustive tool coverage. The fallback is good. Presenters are added when a
tool's volume or forensic value justifies one.

### 4.3 Diff rendering

- `Edit`: line diff computed client-side from `old_string`/`new_string`. Hand-rolled
  Myers/LCS in `frontend/src/lib/diff.ts` — no new npm dependency, preserving Observatory's
  dependency discipline. Word-level intra-line highlighting is a **stretch goal**, not required.
- `apply_patch`: the stored `command` is already a patch. Parse `*** Update File:` /
  `*** Add File:` / `*** Delete File:` headers and `@@` hunks directly.
- `Write`: rendered as an all-additions diff.

**Cost control:** diffs are computed **only when their block is opened**, and memoized per event id.
A collapsed or unopened diff costs zero.

**Where the colour comes from — and where it deliberately doesn't.** Phase 1 ships *semantic*
colour: tone pills, per-agent colours, diff add/remove lines, plan step states, and error tones on
non-zero exits. **Token-level syntax highlighting is explicitly out of Phase 1.** It is the one
place "readable, formatted, with colour" collides with the no-new-dependency rule, and the
collision is resolved in favour of the dependency budget — diff colouring and tones carry most of
the felt colour. If that proves insufficient in real use, adding one small highlighter becomes a
named, deliberate follow-on decision, not an ambient assumption of this spec.

### 4.4 File links — "open in the project it's in"

Nathan's requirement: clicking a path opens it in an editor **in the window rooted at that
project**, not a stray single-file window.

**Contract** — reused verbatim from the already-shaped `black-box-electron-desktop-shell-and-code-links`
backlog item, because it was designed transport-neutral for exactly this and its threat model is
already reviewed:

```
CodeReference { projectKey, relativePath, line?, column?, commit? }
```

**Backend:** `POST /api/open-in-editor`, body = `CodeReference`.

1. Resolve `projectKey` → repo root through the **existing project catalog** (`project_aliases` +
   the `agent_sessions.cwd` projection). **Never trust a renderer-supplied absolute path.**
2. Canonicalize the resolved path; reject anything outside the resolved root (traversal, symlink
   escape).
3. Verify the file exists; verify line bounds when a line is given.
4. Exec an **allowlisted** editor CLI. Cursor and VS Code both reuse the window whose workspace
   folder contains the target file, so `cursor -g <abs>:<line>` lands in the correct project window
   natively. If no window owns that root, open the root first (`cursor <root>`), then the file.
5. Never accept an executable, argument string, or shell fragment from event data, model output, or
   the renderer.
6. Exec with an **argv array, never a shell string**, and store editor commands as **absolute
   paths** — the launchd-run service does not inherit a user shell `PATH`, so a bare `cursor`
   (which lives at `~/.local/bin/cursor`) would fail in production while passing in `mvn
   spring-boot:run` dev runs.

**Config:** `sba.editor.command` (default `/Users/nathan/.local/bin/cursor`),
`sba.editor.allowlist` (absolute paths for `cursor`, `code`, `idea`, `zed`),
`sba.editor.enabled` (default true).

**Responses:** `200` opened · `404 file_missing` · `403 outside_project_root` ·
`409 project_unresolved` · `503 editor_disabled`. Every failure renders as honest UI text, never a
silent no-op.

**Derivation:** presenters extract `file_path` from tool input. Absolute path → `projectKey` by
longest-prefix match against known project roots; `relativePath` is the remainder. A path outside
every known root yields **no link** (copy-path and reveal remain available), never a guess.

**Fallbacks always present:** copy path, reveal in Finder.

This works in the plain browser build — the backend is local — and becomes the Electron app's IPC
surface later, unchanged.

### 4.5 Expand model and the toggle

- New persisted global mode `streamDensity: "collapsed" | "expanded"` (localStorage), surfaced as a
  toggle beside the existing `meaningful events only` checkbox at `StreamPage.tsx:315-318`.
- `StreamPage.tsx:50` — `expandedId: string | null` becomes `overrides: Set<string>` interpreted as
  **exceptions to the mode**, not a list of expanded rows: a row is expanded when
  `(mode === "expanded") !== overrides.has(id)`. This is the detail that makes live tailing work —
  in expanded mode, newly arriving SSE rows render expanded with zero bookkeeping, because absence
  from the set means "follow the mode." (A plain `expandedIds` set would silently collapse every
  new event in expanded mode.) Toggling a row adds/removes its exception; switching modes clears
  the set. Update the toggle at `:339` and the resets at `:82`, `:95`, `:103`.
- `ReaderText` (`EventRow.tsx:74`) takes an `expanded?: boolean` prop threaded from the row,
  defaulting from the global mode; its private signal becomes the per-instance override.

**Performance contract — the load-bearing rule:**

> "Expand all" means *show the narrative body*. It does **not** mean render 500 diffs.

Heavy blocks (`diff`, `bash` output, `code`, large `json`) render inside an expanded row behind a
native `<details>`, each labelled Observatory-style with its size — `Output (18,003 chars)`. Detail
block content mounts lazily on first open and stays mounted. Native `<details>` is used rather than
signal-driven show/hide: zero re-render cost, keyboard and find-in-page friendly, no expand-state
bookkeeping.

### 4.6 Live — "is it working or stuck?"

- **Follow mode.** A pin-to-newest toggle. The existing `pendingItems` "N new" pill
  (`StreamPage.tsx:204-212`, `:326-331`) becomes the *paused* state of the same control.
- **Process monitor**, ported from Observatory (`internal/processes/monitor.go`): server-side poll
  of `ps -eo pid,ppid,rss,pcpu,etime,comm` every 1s, matched against known agent binaries,
  **broadcast over the existing SSE `/api/stream` only when the set changes**. New
  `GET /api/processes`. ~150 LOC, and it is the only signal that distinguishes *thinking* from
  *hung* — no transcript can answer that.
- **Per-session heartbeat.** "last event 4s ago" plus a running / idle / stale dot derived from
  `agent_sessions.last_seen_at`.

### 4.7 Narrative — "what is it trying to accomplish?"

- **Turn grouping.** Events already carry `turn_id`. Group consecutive same-turn events under a
  subtle rail so a burst of 30 tool calls reads as **one move** rather than 30 rows. Grouping is a
  pure client-side fold over the already-ordered feed; it does not change fetching.
- **`update_plan` presenter.** Renders steps with status, and diffs against the previous
  `update_plan` event in the same session to show what changed. This is the single highest-value
  narrative signal already in the corpus and currently invisible.
- **Derived session titles.** Port Observatory's `internal/sources/title.go` logic (241 LOC):
  first-user-prompt fallback, strip `<tag>` preamble blocks and `## My request for Codex:` markers,
  stopword removal, title-casing, acronym repair (`api`→`API`, `sqlite`→`SQLite`, `github`→`GitHub`),
  7-word cap, and `LooksLikeOpaqueID` rejection of UUID-shaped titles. `agent_sessions.title_rank`
  already exists as the slot for ranking derived vs. explicit titles.
- Existing `DecisionCard` / `HandoffCard` / `ObservationCard` are kept, restyled to the shared
  token system.

### 4.8 Cost — re-homed to Phase 2

Nathan asked for cost visibility (tokens, model, context growth), and the mechanism is known:
Codex events carry `model` / `model_context_window` in metadata, and Claude usage lives in the
transcript — reachable because 231,183 events already store `transcript_path`.

But reading transcripts at known paths **is Phase 2's machinery.** An earlier draft of this spec
put a narrow `SessionUsageReader` in Phase 1; review caught that this means building **two
transcript readers** — the narrow one, then Phase 2's real incremental tailer that obsoletes it
within weeks. So cost moves to Phase 2 as the **first consumer of the single transcript reader**:
the same read that lifts full-fidelity assistant text also lifts `message.usage`. Details in §12.

What this buys Phase 1: **zero schema change and zero new ingest surface.** Phase 1 becomes a pure
rendering-and-navigation release plus three small endpoints.

### 4.9 Recall links

1. Add `sessionId` to `RecalledItem` (`src/main/java/…/memory/RecalledItem.java:15`).
2. Populate it in `ContextService.toRecalledItem` (`…/memory/internal/application/ContextService.java:289-303`)
   — it already holds the full `AgentEvent`, which carries `sessionId`.
3. Mirror the field in `frontend/src/lib/api.ts:64-79`.
4. Add **`GET /api/events/{id}`** to `EventController`.
5. Wrap the `RecallCard` head (`RecallPage.tsx:174-179`) in a link to
   `/browse?session=<sessionId>&event=<eventId>`, reusing the href shape already proven at
   `StreamPage.tsx:377-385`.
6. Replace `BoardPage.tsx:614-619`'s full-year recall scan with the new endpoint.

`recallContext` (MCP) returns the same record, so **agents gain the deep link too**, not just the UI.

### 4.10 Consolidation

- **Delete** `/stats` and `/overview` (pages, routes, `SpaForwardingController.java:15` entries).
- **Park `/graph`** — leave route and code in place, out of nav, with a comment naming it the seed
  for the Tree of Souls mind-map backlog item.
- **Merge Ask into Recall → one `/memory` surface**, two modes (`Recall` structured;
  `Ask` lexical + synthesis). Ask's citations render as the same linkable recall cards.
  Redirects: `/recall` → `/memory`, `/?view=ask` → `/memory?mode=ask`.
- **Promote `?view=` params to real routes.** `/stream` and `/browse` become routes; `/` redirects
  to `/stream` **preserving the full query string**, so project scope (`?project=`), facet queries,
  and `?session=`/`?event=` deep links all survive the redirect. `ActivityPage` stops being a 3-way
  mode shell and becomes a project-scope provider wrapping two real routes. Existing `?view=` URLs
  redirect to their new homes with query intact.
- Resulting nav — **five, all real routes**: Stream · Browse · Memory · Projects · Board.

⚠️ Every new top-level route **must** be added to `SpaForwardingController.java:15` or hard-refresh
returns 404. It is an explicit whitelist, not a catch-all.

## 5. Data model changes

**None in Phase 1.** Every slice reads what is already stored. The usage columns an earlier draft
placed here (`input_tokens`, `output_tokens`, `cache_read_tokens`, `model`, `context_window`,
`usage_updated_at` on `agent_sessions`) move to Phase 2 with the cost work, and will follow the
existing idempotent-`ALTER` pattern (`RecordingSqlStore.java:74`) when they land. No table is
dropped; no existing column changes type.

## 6. API changes

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/events/{id}` | Single event by id — unblocks recall links and removes BoardPage's scan |
| GET | `/api/processes` | Live agent processes (pid, cpu, rss, elapsed, agent) |
| POST | `/api/open-in-editor` | Resolve a `CodeReference` and open it in an allowlisted editor |

`GET /api/stream` (SSE) gains a `processes` event type. All existing endpoints are unchanged;
no response shape is removed. `RecalledItem` gains a field (additive).

## 7. Error handling

- **Presenters never throw.** A presenter that cannot parse its payload returns the generic fallback
  presentation. Malformed JSON in 239k historical rows is expected, not exceptional.
- **Diff parsing failures** degrade to the raw `text` block, never to an empty body.
- **Editor-open failures** surface as typed, human-readable states in the row (file missing, outside
  project root, project unresolved, editor disabled) — never a silent no-op.
- **Process poll failures** degrade to "process info unavailable"; the stream keeps working.

## 8. Testing

| Layer | Coverage |
|---|---|
| Presenters | Unit tests per presenter against **golden fixtures extracted from the live corpus** (real Bash/Edit/apply_patch/update_plan payloads). Pure functions, no DOM. |
| Diff | Unit tests: line diff, patch parsing (`Update`/`Add`/`Delete` File), malformed-patch degradation. |
| Components | Expand model (set semantics, global mode, per-row override), follow mode, lazy block mounting. |
| Backend | `GET /api/events/{id}` found + not-found; process monitor parse; `CodeReference` resolution against the project catalog. |
| Security | **Path traversal tests specifically** on `/api/open-in-editor`: `..` escape, symlink escape, absolute path outside root, unknown `projectKey`, non-allowlisted editor, injection attempts in `relativePath`. |
| E2E (Playwright) | Expand-all toggle; diff renders; file-link click (editor exec mocked); live tail with follow mode; recall card navigates to the right session **and** event. |
| Perf | Measured, not assumed — see §9. |

Full gate, per `docs/frontend-overhaul-handoff.md`:

```sh
mvn -q test && (cd frontend && npm run test && npm run build) \
  && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)
```

## 9. Performance budget

Explicit and measured before/after on a 500-row stream:

- Time to first paint of the stream: **no regression** vs. today's collapsed baseline.
- Expand-all at `MAX_ROWS = 500`: interaction stays under **100ms** to toggle.
- Memory: bounded by lazy block mounting — unopened `<details>` content is never constructed.
- Live tail: SSE debounce stays at 500ms; the process feed broadcasts **only on change**.

If expand-all cannot meet the budget at 500 rows, the fallback is a lower `MAX_ROWS` in expanded
mode, **not** silent truncation of bodies.

## 10. Risks and gotchas (carried from `NEXT.md`)

- **Jar swap kills the live service.** Any `mvn package` — *including the Playwright `webServer`* —
  overwrites the jar the launchd `:8766` service runs from, causing 500s. Run
  `scripts/deploy-local.sh`, then `launchctl kickstart -k` if 500s persist.
- **Never `git add -A`** except the scoped `git add -A src/main/resources/static` after a bundle
  rebuild. Built assets are committed.
- **Module import ratchet:** `memory → {project, recording}`; `memory` must never import `ask`.
  Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`
  (enforced by `PackageArchitectureTest`).
- **Playwright against a live app** must wait on `domcontentloaded`, never `networkidle` — the SSE
  connection never idles.
- **Test DBs are temp files**, never `cache=shared` memory.
- Never point a second application at the live DB; snapshot with
  `sqlite3 sba-agentic.db ".backup <path>"`.

## 11. Out of scope for Phase 1

Board, projects, specs, runner, MCP tool surface, embeddings, and recall scoring semantics are all
untouched. No Electron work. No new npm dependency. No schema removal.

## 12. Phases 2 and 3 — sketch

### Phase 2 — one monitor

**Phase 2 is much smaller than "port the Go indexer," and the survey is why.** Because every event
already stores `transcript_path`, Black Box **does not need** Observatory's cleverest ingestion
machinery — root discovery and the dash-encoded project-path decoder are solving a problem Black Box
does not have. And Claude + Codex are 99.9% of the corpus, both already hooked; `cursor` appears
**once, ever**, in 239,501 events.

So Phase 2 collapses to **enriching known sessions from known transcript paths**:

- Byte-offset incremental tailing (Observatory's `internal/indexer/indexer.go` pattern: skip on
  matching mtime+size, resume from stored offset, exact offsets that survive partial final lines).
- A parser-version constant as a deliberate cache-bust for full reparse.
- Full-fidelity assistant text and thinking blocks that hooks do not carry.
- **Cost, as the first slice** (re-homed from Phase 1): the same read that lifts assistant text
  lifts `message.usage`. Usage columns land on `agent_sessions` (`input_tokens`, `output_tokens`,
  `cache_read_tokens`, `model`, `context_window`, `usage_updated_at`) via the idempotent-`ALTER`
  pattern, plus an opt-in `POST /api/sessions/usage/backfill` (`apply=false` dry-run default,
  shaped like the embeddings backfill) so the 3,674 existing sessions are not blank on day one.
  Rotated or deleted transcripts leave fields `NULL` — never zero; a zero would be a lie. Display:
  per-session token badge, per-project rollup, context-window fill bar. **No dollar figures** —
  pricing drifts and a confidently wrong number is worse than an absent one.
- Backfill of pre-instrumentation history.
- Then: **switch Observatory off**, reclaiming ~2GB and one always-on indexer.

Discovery-based ingestion for un-hooked agents (Cursor, Augment) is deferred until there is
evidence Nathan uses them.

### Phase 3 — Black Box as the memory API

- A versioned, documented public API contract over what is now an organically grown ~45-endpoint
  surface.
- Tier-2 semantic search over the full corpus (already an open loop in `NEXT.md`).
- Separate `query` from `scope` on recall so subject and scope can differ (open loop).
- Point `ask-my-history` at Black Box and **drop its Elasticsearch dependency** — one less
  always-on service.

## 13. Delivery slices

Phase 1 is too large for one plan. It decomposes into six vertical slices, each independently
shippable, each leaving the app better than it found it. Ordered by value-per-unit-effort:

| # | Slice | Contains | Why here |
|---|---|---|---|
| 1 | **Presenter layer + core presenters + expand toggle** | §4.1 §4.2 (bash, edit, write, read, apply_patch) §4.3 §4.5 — plus the free deletions of `/stats` and `/overview`, which are trivial and touch files this slice already owns | The whole felt improvement. Everything else is additive to it. |
| 2 | **Recall links** | §4.9 | Smallest slice, fixes a stated complaint outright, and removes BoardPage's expensive workaround. |
| 3 | **File links** | §4.4 | Depends on slice 1's `CodeReference` extraction. Carries the only new security surface, so it gets its own review. |
| 4 | **Live** | §4.6 | Process monitor, follow mode, session heartbeat. Independent of 1–3. |
| 5 | **Narrative** | §4.7 + remaining presenters (`update_plan`, grep, webFetch, webSearch, task) | Turn grouping and derived titles change how the whole stream reads. |
| 6 | **Consolidation** | §4.10 minus the slice-1 deletions: route promotion, Memory merge, `/graph` parked | Last, because it touches every page and benefits from the others being settled. |

Slices 2 and 4 have no dependency on slice 1 and can be pulled forward or run in parallel if a
worker is free. Slice 6 should not start before 1 and 5 land — it would otherwise mean editing the
same page files twice.

## 14. Acceptance criteria (Phase 1)

1. A **Collapsed / Expanded** toggle exists on the stream, persists across reloads, and per-row
   toggling still overrides it.
2. `Bash`, `Edit`, `Write`, `Read`, `apply_patch`, `update_plan`, `Grep`, `WebFetch`, and
   `WebSearch` each render with tool-appropriate structure and color. Every other tool renders at
   least as well as it does today.
3. `Edit` and `apply_patch` events show a readable **diff**.
4. Clicking a file path opens that file **in the editor window rooted at its project**, at the right
   line; failures state why; copy-path and reveal always work.
5. Recall cards **navigate** to the owning session with the correct event selected.
6. A live process panel shows which agents are running, with CPU and elapsed time, updating without
   a reload.
7. Nav is five real routes; `/stats` and `/overview` are gone; `/graph` is parked with a comment;
   Ask and Recall are one `/memory` surface.
8. The §9 performance budget is met, with before/after numbers recorded.
9. Full gate green; live `:8766` service healthy after `scripts/deploy-local.sh`.
