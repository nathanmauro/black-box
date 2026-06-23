# Black Box — Public Showcase Plan

> Repo dir is still `sba-agentic` (artifact id `sba-agentic`); the product is **Black Box**.

## Goal

Make Black Box safe and compelling as a public portfolio repo without overselling unfinished behavior.

## The story

Black Box is a local "flight recorder for machine minds." AI coding agents (Claude Code, Codex) **write** structured intent — decisions, handoffs, observations — into it and **query** each other's prior reasoning back out at runtime via MCP. Local, no cloud, no file mutation.

It is **not** a passive dashboard. It is the writable/queryable shared-memory bus agents act through. It deliberately differs from the read-only sibling [agent-observatory](https://github.com/nathanmauro/agent-observatory) (a telescope): Black Box owns the **write + query** verb (the nervous system).

The signature moment: an agent calls `recallContext` and a structured `Decision` a *different* agent committed yesterday — with its open loops and confidence — comes straight back into the prompt.

## Current proof

- Runs on `localhost:8766` with HTTP API, web control surface, CLI, MCP server, SQLite storage, optional local-AI summaries, optional Elasticsearch index.
- MCP tools live in `AgenticTools`: write — `captureDecision`, `captureHandoff`, `captureObservation`; query — `recallContext`, `searchSessions`, `recentSessions`, `localModelStatus`.
- REST routes in `AgenticController`: `POST /api/{events,decisions,handoffs}`, `GET /api/{recall,sessions,sessions/{id}/events,search,status,exports/targets}`, `POST /api/sessions/{id}/summarize`, `POST /api/sessions/summarize`, `POST /api/sessions/summarize-missing`, `POST /api/sessions/{id}/exports/{targetId}`, plus health endpoints.
- Hook bridge implemented at `scripts/hooks/sba-agent-hook.sh` (local/opt-in).
- One-command demo at `scripts/demo.sh` (starts app, seeds a decision/handoff, shows the recall) — referenced as the README quickstart.
- Title seeding: `EventIngestService.titleFor(...)` uses `metadata.title` → first event text → tool/event fallback, compacted to 96 chars; stored once on first creation of a `(source, clientSessionId)` session. Later events do **not** retitle. This is title *seeding*, not smart retitle.

## Showcase readiness gates

- Public docs avoid machine-specific claims; absolute local paths only as clearly marked examples.
- Hook config documented as local/opt-in. Do not commit private `.codex` / `.claude` / database / IDE / runtime state.
- README is why-first: hook, hero asset, the write+query loop ("the clever bit"), quickstart, privacy line, vs-observatory divider, then reference.
- SQLite is the canonical store in the public story; Elasticsearch is explicitly optional/secondary and never led with.
- Local model is the only outbound dependency, and only for summaries.
- Hero GIF/screenshot captured from `./scripts/demo.sh` lands at `docs/assets/hero.gif`.
- Keep honest: no semantic/vector search, no live streaming — those are roadmap.

## Docs delivered

- `README.md` — full rewrite, why-first inverted pyramid. Done.
- `LICENSE` — MIT, © 2026 Nathan Mauro. Done.
- `docs/architecture.md` — write+query loop narrative + Mermaid diagram (agents → ingress → ingest → SQLite → recall/search/summary → back to agents; optional ES/local-model off to the side). Done.
- `docs/local-writes-and-elasticsearch.md` — existing operator notes, still valid for the optional ES path.

## Open follow-ups

1. Capture and commit `docs/assets/hero.gif` from `./scripts/demo.sh` (the README references it with a placeholder comment).
2. Decide the next product slice:
   - explicit retitle endpoint / MCP tool, or
   - early-session retitle rules that only replace weak fallback titles, or
   - local-AI title suggestion with user-visible confirmation.
3. Roadmap candidates (do not claim yet): semantic/vector recall, live event streaming to the UI.
4. Diagram/flow documentation workflow: install or standardize on draw.io Desktop for polished flow diagrams, keep Excalidraw for fast whiteboard sketches, and use Mermaid for repo-native diagrams in Markdown.

## Done since this plan was written

- Title behavior test coverage — `SessionTitleTest`: metadata title wins, first-line seeding, tool fallback, truncation, and an explicit test that later events do not retitle.
- Recall/decision/handoff round-trip — `ContextLoopTest`: write a decision via `/api/decisions`, recall it via `/api/recall`.
- Full suite green: 10 tests across 5 files (`mvn test`).
- Search filter suggestion dismissal — the Search page closes the suggestion popover after selection, supports Escape/click-away dismissal, keeps applied filters removable, and has Playwright coverage in `frontend/tests/e2e/smoke.spec.ts`.
- Activity Find result selection — embedded Activity Find results now open the selected session in the Browse reader without bouncing to standalone `/sessions/:id`; standalone Search links still deep-link normally.
- Local deploy safety — `scripts/deploy-local.sh` unloads the launchd service before rebuilding the executable jar and restarts it before status polling, with `scripts/test-deploy-local.sh` covering command order.

## Verification checklist

- `mvn test`
- `mvn -q -DskipTests package`
- `curl -fsS http://localhost:8766/api/status | jq`
- Write a decision through `POST /api/decisions`, recall it through `GET /api/recall`
- Demo search through `GET /api/search`
- Hook smoke test through `scripts/hooks/sba-agent-hook.sh`
- `git status --short` clean except intentional handoff branch state
