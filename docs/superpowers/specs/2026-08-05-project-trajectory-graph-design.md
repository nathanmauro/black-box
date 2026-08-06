# Project Trajectory Graph — design

Replace the Projects timeline with a graph of where the project is and where it could go.

## Context

Black Box's Projects view currently renders a linear timeline (the "storyline" center pane of
`ProjectsPage.tsx`). This feature replaces that pane with a **graph**: the past as a trail of
collapsed branches, the current state as a head node, and a fan-out ahead of possible futures. It
realizes the parked "Tree of Souls" seed — the observatory consolidation design
(`2026-07-28-agent-observatory-consolidation-design.md`) explicitly preserved the orphaned `/graph`
constellation page as the seed for exactly this kind of mind-map.

The data already exists: captures are `agent_events` with structured metadata — Decisions carry
`alternatives[]` (roads not taken), `openLoops[]`, `confidence`; Handoffs carry `nextAction`
(756/772 populated) and `openLoops[]` (732/772); tasks carry live statuses. What was missing is a
graph assembly endpoint, a graph view, and a capture surface for speculative futures.

### Decisions locked

1. **Futures = captured + generated.** Solid future nodes derive from captured data (latest handoff
   nextActions, open loops, open/blocked tasks, recent decisions' alternatives). Ghost nodes are
   agent-written speculation, visually distinct.
2. **Ghost nodes are agent-written at capture time** — a new MCP projection capture surface, not
   server-side model generation. Black Box records what minds thought could happen; the graph
   renders the latest projection set.
3. Graph is the default center pane of `/projects/:key`; the old timeline stays reachable behind a
   toggle in the same pane.
4. House style: hand-rolled SVG, pure layout function, no new dependencies (DagView pattern).

## Graph semantics (data-verified against the live DB)

**Node taxonomy:** `deep-past | burst | head | future-next | future-loop | future-task |
future-ghost | future-more | stub`. Edge types: `trail | possible | projected | rejected`.

- **Past spine = burst epochs, not sessions or raw captures.** Sort captures ascending; new burst
  when the gap exceeds 48h. (Verified: the busiest project — this repo — yields ~9 bursts vs 91
  sessions/119 handoffs; typical projects 2–6.) Max 5 trail bursts visible; older collapse into one
  deep-past node (`+N earlier · M captures`). Burst node = date-range label + member count + accent
  diamond stud if it contains a Decision. Clicking a burst opens its member list in the right rail —
  the graph never re-layouts on selection.
- **Head = latest Handoff in the newest burst** (fallback: latest capture of any kind). Label =
  clamped `contextSummary`, eyebrow `NOW · timeAgo`. **Staleness is honest:** if the head is >14
  days old it renders dormant (`--text-dim`, no glow, `IDLE · 3w`) and futures dim to 0.7 opacity.
- **Futures from the frontier set**: for each session with a Handoff in the head burst or within 7
  days of the head, take that session's *latest* Handoff only — the last agent to leave the room
  listed what's open; older lists are superseded. No cross-handoff loop tracking (verified: loop
  repeats are ~95% paraphrases, identity tracking would fail). Dedup by token-set Jaccard ≥ 0.5,
  keep newer.
- **Future ranking** (fills up to 7 slots): 1) `nextAction` of frontier handoffs (max 2), 2) blocked
  tasks (max 2), 3) claimed/in_progress then open tasks, 4) deduped frontier openLoops, 5) ghost
  Projections (TTL 30 days, Jaccard-superseded pruning, max 3, **1 slot always reserved** so solids
  can't crowd ghosts out). Overflow → `+N more` node opening the ranked list in the rail.
- **Alternatives are NOT futures** — they carry "— rejected: ..." rationale; placing them ahead of
  the head would misrepresent captured intent. They render as **dead stubs** angling down off the
  spine, max 1 aggregated stub per burst (`N not taken`), full text in the rail card.
- **Layout: deterministic pure function, no physics.** Horizontal spine left→right (84px pitch,
  labels alternate above/below), head pulled +40px, futures in a "ladder-on-an-arc" fan up-and-ahead
  of the head (even 64px rows, center slot furthest right, rank-1 nextAction dead ahead, ghosts on
  an outer shell +36px, dashed). Stubs angle down. Canvas ~780×340 typical, worst ~1050×500 with
  `overflow-x: auto` + on-mount scroll-to-head.
- **Interaction: one gesture — select.** Click/Enter/Space selects a node → "Trajectory detail" card
  at top of the existing right rail (full text, source capture, session/task links). Nodes are
  `<g role="button" tabindex="0">`. Toggle `Trajectory | Timeline` tabs in the pane head, persisted
  as `bb.projectStoryView` in localStorage (mirrors `bb.streamDensity`).
- **Visual encoding** via existing `--node-color`/color-mix idiom: head = accent circle r14 + glow;
  trail = dim circles r9; future-next green, future-loop yellow, future-task mini board-rect with
  DagView status colors, ghosts = dashed accent circles, no shadow ("massless"), opacity
  0.45+0.4×confidence; stubs faint dashed r6. Confidence encodes only on ghosts/decision tooltips —
  handoffs and loops have no confidence field; don't fake one.
- **Honest fallbacks:** capture-poor projects get an empty state + Timeline tab (no spine from raw
  tool events); no "resolved" checkmarks (absence ≠ resolution); multi-thread futures visually
  attach to head with the true source cited in the card.

## Architecture

**Backend serves facts; frontend renders interpretation.** The server exposes a slim trajectory
*feed* (capped raw captures + open tasks + latest projection); all graph semantics (burst
clustering, frontier rule, dedup, ranking) and layout live in pure frontend functions. Rationale:
the heuristics (48h gap, Jaccard 0.5, caps) will be tuned repeatedly — tuning must not require
wire-contract churn + jar rebuild + launchd restart each time. This follows the house split
(DagService assembles, DagView layouts) but moves the tunable semantics to the cheap side of the
wire.

Key reconciliations: alternatives are stubs, not futures (they're explicitly rejected); the head is
the real latest-handoff capture, not a synthetic node; interaction is select→rail-card,
`role="button"` (not navigate-on-click); projection schema is `paths[]` in a single event so
"latest set wins" is trivial.

**Module boundary note:** `project` module's `allowedDependencies = "recording"` — enforced by
`ApplicationModuleStructureTest`. The trajectory store reads `tasks` via SQL from the project
module's own repository (house precedent: module boundaries are Java-package-level, not DB-level;
`ProjectRepository` already reads `agent_events`/`session_melds` directly).

### Surfaces

- `GET /api/projects/{projectKey}/graph` → `ProjectTrajectoryResponse(projectKey, canonicalKey,
  label, generatedAt, totalCaptures, captures[], tasks[])`; captures capped at 120, kinds
  decision|handoff|observation|meld|projection.
- Frontend `lib/trajectory.ts`: `buildTrajectory(feed, nowMs)` (bursts, head, frontier futures,
  ranking, stubs, staleness) + `layoutTrajectory(graph)` (spine/fan/stub coordinate math). Rendered
  by `components/TrajectoryView.tsx` (dumb SVG, DagView idiom).
- Projection capture: MCP `captureProjection` + `POST /api/projections`; event_type `"Projection"`,
  metadata `{kind:"projection", paths:[{title, description, confidence}], basis?, repo?}`, paths
  capped at 5; recallable via `recallContext(kinds:["projection"])`; latest set wins in the graph.
