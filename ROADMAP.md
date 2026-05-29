# Black Box — Roadmap

Black Box is a local flight recorder for machine minds: agents **write** structured
intent (decisions, handoffs, observations) and **query** each other's prior reasoning
back out at runtime via MCP — local, no cloud, no file mutation. This roadmap tracks
the build in phases.

**Status:** ✅ shipped · 🟡 in progress · ⬜ planned · 💤 stretch

---

## ✅ P0 — Harden the write path

- Race-free `UPSERT` `findOrCreateSession` (replaced find-or-create race)
- `@Transactional` persist + summary write path
- SQLite WAL + `busy_timeout`, connection pool = 4
- `ApiExceptionHandler` error envelope — no stack traces leak into agent context
- Hook bridge can never fail the host agent's turn (fire-and-forget, capped curl)
- Session title ranking — TEXT > TOOL upgrade at ingest, AI title locked at rank 100,
  `title_rank` migration for pre-existing DBs

## ✅ P1 — The signature write + query slice

- Typed MCP tools — write: `captureDecision`, `captureHandoff`, `captureObservation`;
  query: `recallContext`, `searchSessions`, `recentSessions`, `localModelStatus`
- REST — `POST /api/{decisions,handoffs,events}`; `GET /api/{recall,sessions,search,status}`
- `ContextService` recall returning typed `RecalledItem` (decision, rationale,
  alternatives, open loops, confidence)
- Cognition Spine UI + amber recall-cone + structured memory cards
- Self-hosted IBM Plex; honest status readouts
- `scripts/demo.sh` seeding a cross-agent recall story
- Tests: 10 across 5 files, green

## 🟡 P2 — Make the instrument sing  *(current phase — branch `phase-2-polish`)*

Visual polish + a showcase asset. **No new data capabilities** — the README honesty
boundary holds (no semantic search, no live streaming yet).

- ⬜ **Motion signature** — events strike in onto the Spine (animated tick-in); header
  breathes on ingest. One motion signature only.
- ⬜ **Confidence rendering** — a sparkline / gauge on memory cards so a recalled
  decision's confidence reads at a glance.
- ⬜ **Light / dark toggle** — the instrument has both faces; coal chassis stays default.
- ⬜ **Hero asset** — an animated SVG of the recall moment (amber beam striking the
  spine) committed at `docs/assets/`, plus `scripts/capture-hero.sh` so the *real*
  GIF can be recorded from the live demo on demand.
- ⬜ *(outward, Nathan-only)* GitHub repo rename via `gh repo rename` — content is
  already rebranded; Java package `dev.nathan.sbaagentic` + artifactId kept as-is.

## ⬜ P3 — Live + real search

- SSE live strike-in — events stream onto the Spine in real time (today: poll/static)
- Cadence sparkline — ingest rhythm in the header
- Activate the orphaned `turn_id` threading
- Real FTS5 full-text search (replaces `LIKE`; Elasticsearch stays optional/secondary)

## 💤 P4 — Semantic recall  *(stretch)*

- Local embeddings + `sqlite-vec` semantic recall
- Streaming summaries

---

## Cuts (deliberate)

- Elasticsearch demoted to optional/secondary; FTS5 owns lexical recall.
- No "control plane" framing — it overclaims.
- One motion signature only. Restraint is the aesthetic.

## Sibling project

[agent-observatory](https://github.com/nathanmauro/agent-observatory) is the read-only
**telescope** (filesystem discovery). Black Box is the writable, queryable **nervous
system** agents call back into. Complementary, not competing.
