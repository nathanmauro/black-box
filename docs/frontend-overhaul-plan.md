# Black Box — Frontend Overhaul Plan

Branch: `frontend-overhaul` · Status: **plan, awaiting go-ahead** · Author pass: Claude (research fan-out verified adversarially)

A visual/UX overhaul **plus three targeted feature builds** for the Black Box recorder UI.
Hard rule: **no existing functionality is removed or regressed.** This is sharpen-and-add, not replace.

---

## 0. Constraints (locked)

- **Stack stays vanilla.** No framework, no bundler, no npm build step. The frontend remains `index.html` + JS + `styles.css` served statically by Spring Boot. This is treated as a *feature* (crisp, instant, low-friction). The only structural change is splitting the single `app.js` into native ES modules (`<script type="module">`) — still zero-build.
- **Offline-first.** Any third-party lib (only `force-graph`) is **vendored** into `static/vendor/`, not CDN-loaded, so the recorder works with no network.
- **Amber stays a signal, not decoration.** Existing semantic discipline (amber = recall/intent/active/pulse; green = health; red = failure) is preserved.
- **No commits/PRs without explicit approval.**

---

## 1. Feature inventory — the no-loss contract

Every existing capability and where it lands after the overhaul. Nothing in the "Preserved" column may break.

| # | Feature (today) | Source | Disposition |
|---|---|---|---|
| 1 | Masthead brand + 3 live readouts (storage / local-ai / elastic) with ok·degraded·off states | `index.html`, `loadStatus` | **Preserved**, restyled into a tighter instrument cluster |
| 2 | Sessions rail: list, source labels, event counts, relative time, active highlight | `loadSessions` | **Preserved** |
| 3 | Per-session **copy session IDs** (rail button + masthead button + identity chips) | `copySessionIds`, `idChip` | **Preserved** |
| 4 | Capture form (source select, session id, note → POST `/api/events`) + capture pulse | `captureForm` | **Preserved** |
| 5 | Search: query box → `/api/search`, merged local+elastic, origin badges, ES score, `<mark>` highlights, click/Enter to open+locate | `renderSearch`, `mergeSearchResults` | **Preserved + upgraded** (query bar w/ autocomplete; plain text still works identically) |
| 6 | Recall console: scope + time-window (24h/7d/30d/all) → `/api/recall` | `doRecall` | **Preserved** |
| 7 | Recall result **cone/beam** SVG + memory cards (kind, source, time, headline, rationale, alternatives, open loops, next action, repo, confidence bar) | `drawCone`, `renderMemory` | **Replaced** by a real graph; **every card field preserved** (cards become the graph's detail panel / node payload) |
| 8 | Click a recalled memory → locate on Cognition Spine (smooth scroll + pulse) | `locateOnSpine` | **Preserved** (now also an edge in the graph) |
| 9 | Recall panel collapse/expand + clear | `toggleRecallButton`, `clearRecall` | **Preserved** |
| 10 | Cognition Spine: chronological timeline, node types (decision/handoff/tool/error/prompt), dot sizing, ink animation, details (`alternatives`, tool i/o), confidence, open loops | `renderSpine`, `renderNode` | **Preserved**, restyled; optional graph view added (does not replace timeline) |
| 11 | Tool filter strip (All / Tool uses / per-tool counts) | `refreshToolFilter` | **Preserved** |
| 12 | Summarize (`/api/sessions/{id}/summarize`) + summary panel | `summarizeButton` | **Preserved** |
| 13 | Export summary to targets (Obsidian etc.) + inline status | `exportSummaryButton` | **Preserved** |
| 14 | Session identity chips (blackbox + client id, click-to-copy compacted) | `renderSessionIdentity` | **Preserved** |
| 15 | Status gauges health polling, capture pulse, resize-redraw, reduced-motion support | various | **Preserved + extended** to new motion |
| 16 | Responsive single-column collapse < 1000px | `@media` | **Preserved** |

**Verification gate before merge:** run `mvn spring-boot:run` + `./scripts/demo.sh` seed, then exercise rows 1–16 by hand (the `verify`/`run` skills). No row may regress.

---

## 2. Visual / UX direction — "Instrument" (sharpened Black Box)

We keep the flight-recorder identity (it's already *not* generic AI-dashboard) and push it into a crisper, more deliberate **instrument console**. Three directions were generated; the primary is committed, two alternates noted for redirection.

### Primary — **A · Instrument** (committed)
The recorder as a piece of precision lab/avionics equipment. Coal chassis, amber as the only live signal, machined hairline detailing, monospace readouts with tabular numerals, and one new motion signature.

- **Palette:** keep `--coal #15161a` + `--amber #e8a33d`; introduce a tightened elevation ramp (reuse existing `--coal-solid/raised/hover`) and a slightly cooler hairline (`--line`). Amber, green `#41b884`, red `#e0533d`, plus existing source hues (blue/sage/steel) retained for semantics.
- **Type:** keep **IBM Plex Sans + Mono**. Optional single addition: **Departure Mono** (free, OFL) for the big readout numerals only — gives the gauges a characterful instrument feel. Optional/deferrable; not required to ship.
- **Texture:** evolve the flat 1px grid into a subtler "tape" fabric + a faint horizontal strip-chart baseline rhythm behind the Spine.
- **Detailing:** corner ticks on panels, hairline rules, crisper panel headers, a more legible readout cluster. Sparse, machined, intentional.
- **Motion signature (new, one):** when a recall fires, the recall stage runs a single **scope sweep** that resolves into the constellation graph (replacing the bezier "cone"). Reuses the existing "ink strikes the tape" stagger for nodes. All new motion gated under `prefers-reduced-motion`.
- **Ergonomics (the Nimbalyst lesson):** lower friction — clearer empty/loading states, keyboard affordances on search results (already present) extended to the graph, a graph that is *immediately legible* without reading. Spatial + multi-view, not denser.

### Alternates (say the word to switch)
- **B · Phosphor Scope** — greener CRT/oscilloscope treatment, scanlines, phosphor trail. Higher risk of "hacker cliché"; scope CRT effects to <5% so green keeps meaning.
- **C · Patch-bay Console** — more skeuomorphic: numbered labels, patch-bay cabling for edges, hardened grid. Most work, most "physical."

> Decision: **A** balances "fresh + cutting-edge" against "crisp + low-friction + preserves identity." It's the lowest-risk path to a distinctive result. (Implementation uses the `frontend-design` skill for the restyle pass.)

---

## 3. Graph readability fix (Problem #1) — the **Recall Constellation**

**Root cause (verified):** there is no graph today. `drawCone()` draws one emitter circle + one cubic-bezier `<path>` per memory card to a CSS grid. It reads as decorative beams, not structure.

**Fix:** a real node-link graph, **hand-rolled in SVG** (zero dependency, offline-safe, crisp DOM text, full token control). For recall results (small N), a *deterministic radial/clustered* layout is more legible and more on-aesthetic than a force-directed blob — which directly answers "you can't tell it's a graph." `force-graph` is **reserved for the larger provenance / cross-session graphs (§5)** where physics + scale earn their keep. The recall graph delivers **every requirement you listed**:

- **Collapsed-by-default clustering.** Recalled items group into a few **cluster nodes** by `kind` (decisions / handoffs) — or by `repo`/`source` when one kind dominates. Each cluster shows its count. **Expand on click** (hover-preview optional) explodes a cluster into its member nodes; click again to recollapse. The current query/scope sits at the center as the root node.
- **Layout, spacing, sizing, edges.** Deterministic radial layout (clusters on an even angular ring around the origin, members fanned on arcs) for stable, legible spacing; node size scales by `confidence`; edges are thin amber hairlines (origin→cluster, cluster→member). Decisions = filled amber diamonds, handoffs = open amber diamonds, matching the Spine's dot language.
- **Zoom / pan.** Built in (wheel + drag), with a "fit" control.
- **Level-of-detail.** Labels hidden when zoomed out or when node count is high; shown on zoom-in / hover. Keeps it legible at any scale.
- **Minimap.** A small corner navigator (custom 2nd canvas drawing node positions + viewport rect) toggled on for larger graphs.
- **Detail panel.** Clicking a node surfaces the **full memory card** (every field from today's `renderMemory` — rationale, alternatives, open loops, next action, confidence bar, repo) in a side/detail slot. **No card content is lost**; the cards stop being the layout and become node payloads.
- **Spine link preserved.** Nodes whose `eventId` is on the active Spine keep the click-to-locate behavior (now visualized as an edge into the current session).

**On minimap:** skipped for the recall graph (overkill at small N), reserved for the larger graphs. Zoom/pan + LOD (label hiding) are included. The clustered-collapsed default keeps even a large recall set legible; `force-graph` remains the drop-in engine for the big-graph views.

---

## 4. Elasticsearch query autocomplete (Problem #3) — **zero-build query bar**

Kibana-style, but right-sized for a vanilla app. **No CodeMirror/Monaco** — verification confirmed CM6-over-esm.sh hits `@codemirror/state` duplicate-instance breakage and ~124KB+ gzip; wrong fit. Instead: a **custom query bar** = native `<input>` + an aligned overlay layer for colored tokens + a small regex tokenizer + a cursor-context suggestion popover.

### Syntax (KQL-lite, regex-tokenizable)
`field:value` · `field:"quoted value"` · `field:val*` (wildcard) · `field:*` (exists) · ranges `field > n` / `>= <= <` · booleans `AND OR NOT` + parens · bare terms = free text. **Plain free-text queries behave exactly as they do today** (no regression for row 5).

### Suggestions (cursor-aware)
- **Fields** — from a cached field list.
- **Values** — fetched per field as you type, debounced 120ms.
- **Operators** — `AND OR NOT` offered after a complete clause.

### New backend endpoints (Spring Boot, additive)
- `GET /api/search/fields` → field list from ES **`_field_caps`** (`{name,type,searchable,aggregatable}`), cached; **static curated list fallback** when ES is off.
- `GET /api/search/values?field=&prefix=&limit=` → from ES **`_terms_enum`** (purpose-built low-latency prefix lookup on keyword fields; this is exactly what powers Kibana value autocomplete — snappy on large indexes by design), with a SQLite `SELECT DISTINCT … LIKE 'prefix%'` fallback when ES is off.
- **On submit:** structured KQL-lite translates server-side to the existing ES query path; free-text falls through to today's behavior. SQLite path stays as-is.

### Highlighting + errors (cheap)
Overlay tokenizer colors fields/values/operators/strings; unbalanced quotes/parens or unknown fields get a red underline (`--red`). All client-side, no editor framework.

**Performance:** debounce + field-list cache + `_terms_enum` = stays snappy on large indexes (the API is designed for autocomplete latency).

---

## 5. Graphability (Problem #2) — what *should* be a graph

Ranked by value ÷ effort, tied to the real schema. (All "now" items are **zero new backend** — verified: `/api/sessions/{id}/events` and `/api/recall` already return everything.)

| Rank | Graph | Nodes / Edges | Backend | Effort | When |
|---|---|---|---|---|---|
| 1 | **Recall Constellation** (§3) | query → recalled memories, clustered | existing | M | **Now (P1)** |
| 2 | **Within-session provenance DAG** | events → decision→handoff→next-action + tool causal links; reuses the graph engine as an optional Spine view (timeline stays) | existing | M | **Fast-follow (P2.5)** |
| 3 | **Recall provenance** | recalled item → its origin session/event → current scope | existing (`RecalledItem.eventId`) | S | **Fast-follow (P2.5)** |
| 4 | Tool co-occurrence graph | tools linked by co-occurrence in a session | existing | M | Defer |
| 5 | Entity/topic knowledge graph | terms mined from event text | needs mining/NLP | L | Defer |
| 6 | **Cross-session relations** | sessions linked by repo/tools/handoff/terms | see §6 | M | **Tangent — opt-in only** |

Recommendation: ship **1** now; **2 & 3** as quick fast-follows on the same engine; defer 4–5; treat 6 as a flagged tangent.

---

## 6. Tangent (do **not** build without greenlight) — **Chat / Session Relations**

> Flagged per your `/tangent` instruction. Sketch + estimate only.

**Idea:** a "constellation of sessions" — each session a node, edges weighted by relatedness: shared `cwd`/repo (strong), shared tools, explicit handoff (`toAgent`) links, and shared salient terms. Clusters reveal which agent sessions are working the same problem; click a node → open that session. A genuinely novel view for a cross-agent recorder.

**Feasibility (verification correction):** the earlier "needs a new backend + O(n²) pass" claim was **refuted** — the app already runs Elasticsearch, and ES **`adjacency_matrix` aggregation** / **Graph `_explore` API** can derive an undirected weighted session-relationship graph directly from the existing index (no new datastore, no all-pairs scan). Repo/handoff edges are computable from data already on hand.

**Effort:** ~M (1–2 focused days): one new `/api/relations` endpoint (adjacency_matrix over `cwd`/`source`/tool/term buckets) + a new graph view reusing the §3 engine. **Held pending your greenlight.**

---

## 7. Implementation plan & order (on approval)

Priority follows your stated order: (a) no loss → (b) graph readability → (c) UX polish & perf → (d) ES autocomplete.

- **P0 — Modularize, behavior-frozen.** Split `app.js` into ES modules (`core`, `graph`, `querybar`) via `<script type="module">`; vendor `force-graph.min.js`. Zero visible change. Verify rows 1–16.
- **P1 — Recall Constellation** (§3). The headline fix.
- **P2 — Visual/UX restyle** (§2) using the `frontend-design` skill; perf pass (LOD, reduced-motion, no layout thrash).
- **P2.5 — Graphability quick wins** (provenance DAG + recall provenance) if scope allows.
- **P3 — ES autocomplete** (§4): backend endpoints + query bar.
- **Tangent (§6):** only if greenlit.

**Build mechanics:** heavy implementation delegated to Codex per standing preference; Claude integrates, adversarially verifies, runs the app to confirm behavior, and owns git. Commit/push/PR only on explicit approval.

---

## 8. Decisions & assumptions (made where ambiguous)

1. **Keep & sharpen** the Black Box identity rather than a from-scratch aesthetic — "fresh" achieved by pushing the instrument metaphor, not discarding a strong, non-generic identity. (Direction A; B/C offered.)
2. **Hand-rolled radial SVG for the recall graph** (vs a library) — most legible at small N, zero-dependency, offline, best aesthetic match. **`force-graph` (vendored) reserved** for the larger provenance / cross-session graphs where scale + physics matter. (Also: new JS ships as classic scripts exposing globals, not an ES-module refactor — lower regression risk than rewriting the working 866-line `app.js`.)
3. **Custom query bar, not CodeMirror** — zero-build constraint + verified CM6 fragility/weight.
4. **agent-observatory = ergonomics reference only**, not an aesthetic source (per your steer).
5. ES autocomplete **degrades gracefully** to SQLite/curated lists when Elasticsearch is off — the UI never hard-depends on ES.
6. Optional **Departure Mono** for numerals is a nice-to-have, not a blocker.

---

## 9. Research provenance

4 web-grounded research lenses + 8 adversarially-verified load-bearing claims (6 confirmed, 2 refuted → corrections folded in above).

- `force-graph` v1.51.4, MIT, zero-build ESM/UMD via jsDelivr — **confirmed**.
- ES `_terms_enum` (7.14+) + `_field_caps` power Kibana-style field/value autocomplete — **confirmed**.
- KQL is regex-tokenizable; CodeMirror-6-over-CDN has `@codemirror/state` conflicts + ~124KB — **confirmed**.
- "Nymbalyst" = **Nimbalyst** (real, maintained); "generic Tailwind look" — **refuted** (it's a distinctive visual workspace; the takeaway is *ergonomics*, not skin).
- Recall "graph" = `drawCone`, no real nodes — **confirmed**.
- Within-session graphs need zero new backend — **confirmed**.
- Cross-session relations "need new backend + pairwise pass" — **refuted** (rides existing Elasticsearch `adjacency_matrix`/Graph API).
