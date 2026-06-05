# Black Box Frontend Overhaul — START HERE (session handoff)

**Last updated:** 2026-06-05 · **Branch:** `frontend-overhaul` · **State:** P1 + P2 shipped, live, and **committed as a checkpoint**; P3 not started. Nothing pushed.

> **TL;DR for a fresh agent / new session:** We are doing a visual + UX overhaul of the Black Box recorder's web UI (vanilla JS + CSS served by a Spring Boot jar). The hard rule is **no existing feature is removed or regressed** (see the 16-point inventory in `docs/frontend-overhaul-plan.md` §1). Two phases are done, verified, and running at `http://localhost:8766`: **P1** replaced the old recall "cone" with a real **Recall Constellation** graph (`graph.js`), and **P2** restyled everything into a sharpened **"Instrument"** aesthetic (CSS-only). **P3 (Elasticsearch query autocomplete) is the next phase and has not been started.** Read this whole file, then `docs/frontend-overhaul-plan.md` for full design rationale.

---

## 1. Current status

| Phase | What | State |
|---|---|---|
| P0 | Modularize | **Folded into P1** — instead of an ES-module refactor of `app.js`, new code ships as a **classic-script global** (`graph.js` = an IIFE exposing `window.BlackBoxConstellation`). Lower regression risk. |
| **P1** | **Recall Constellation graph** | ✅ **Done, verified, live.** Hand-rolled deterministic radial SVG graph replaces `drawCone()`. Clusters collapsed-by-default, click-to-expand, viewBox zoom/pan, fit button, LOD label-hiding, hover dim/highlight, keyboard + ARIA, `AbortController` teardown, reduced-motion gated. |
| **P2** | **"Instrument" restyle** | ✅ **Done, verified, live.** CSS-only reskin of `styles.css`: machined chassis, bezel/screen/tick tokens, LED gauges, oscilloscope screen for the constellation, strip-chart baseline behind the Spine, corner ticks, custom scrollbars, `:focus-visible`. Amber kept as signal-only. |
| **P3** | **ES query autocomplete** | ⛔ **NOT STARTED — this is the next action.** Design fully specced in `docs/frontend-overhaul-plan.md` §4. |
| Fast-follow | Provenance graphs | Not started. Within-session decision→handoff→next-action DAG + recall-provenance. Zero new backend (data already in `/api/sessions/{id}/events` + `/api/recall`). Plan §5. |
| **Tangent** | Chat/session relations | ⛔ **PARKED — do NOT build without Nathan's greenlight.** Plan §6. Rides existing Elasticsearch (`adjacency_matrix`/Graph `_explore`), no new datastore. |

---

## 2. How to run / deploy / verify  ← read this, the deploy path is non-obvious

The app runs as a **launchd service**, serving static files **from inside a packaged jar** (NOT from `src/` or `target/classes`). Editing source files does **nothing** until you rebuild the jar and restart the service.

- **Service:** `com.nathan.sba-agentic` (LaunchAgent at `~/Library/LaunchAgents/com.nathan.sba-agentic.plist`) → `java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar` on port `8766`.
- **It auto-respawns** (launchd KeepAlive). Do **not** plain-`kill` it — it comes back on the *old* jar. Use `kickstart -k`.

**Deploy a frontend or backend change (the full loop):**
```bash
cd /Users/nathan/Developer/proj/sba-agentic
mvn -q -DskipTests package                                  # rebuild jar (copies new static into the jar)
launchctl kickstart -k "gui/$(id -u)/com.nathan.sba-agentic" # kill+restart with the new jar
curl -s --retry 40 --retry-delay 1 --retry-all-errors --retry-connrefused \
  -o /dev/null -w "api/status: %{http_code}\n" http://localhost:8766/api/status   # wait for boot
```
Then verify markers from the *served jar* (not the source file), e.g.:
```bash
curl -sI http://localhost:8766/styles.css | grep -i cache-control   # expect: Cache-Control: no-store
curl -s  http://localhost:8766/ | grep -c constellation              # expect: 1
```

**Browser cache:** SOLVED. `application.yml` now sets `spring.web.resources.cache.cachecontrol.no-store: true`, so static assets are never cached — a **plain refresh** always shows the latest. (If a copy cached *before* that change lingers, one hard-refresh — Cmd+Shift+R — evicts it for good.)

**Tests:** `mvn test` (31 tests, all passing as of P2). `node --check src/main/resources/static/graph.js` and `app.js` for JS syntax.

---

## 3. File map (what changed and why)

Working tree vs `main` (`git diff --stat`): `app.js` +72, `index.html` +5/-2, `styles.css` +690/-…, `application.yml` +8; new untracked `graph.js`, `docs/frontend-overhaul-plan.md`, this file.

- **`src/main/resources/static/graph.js`** (NEW, 596 lines) — `window.BlackBoxConstellation = { render, redraw, destroy }`. The recall graph engine. Deterministic radial layout, clusters by `kind`, expand/collapse, zoom/pan/fit, LOD, hover, `ctx.onSelect(item)` callback. Classic script (no import/export), loaded **before** `app.js`.
- **`src/main/resources/static/app.js`** (847 lines) — P1 edits only to the recall path: `doRecall()` now calls `BlackBoxConstellation.render(...)` and `renderRecallDetail()`; `renderMemory()` is **kept and reused** to render the full card into `#memoryDetail`; `drawCone()`/`#cone`/`#memoryCards`/`SVG_NS` removed. Everything else (search, capture, sessions, spine, summarize, export, gauges, identity, tool filter) untouched.
- **`src/main/resources/static/index.html`** — recall stage now `<div id="constellation">` + `<div id="memoryDetail">`; loads `/graph.js` then `/app.js`.
- **`src/main/resources/static/styles.css`** (1342 lines) — P1 `.constellation*`/`.memory-detail` styles + the full P2 Instrument restyle. Dead `.cone`/`.memory-cards`/`@keyframes draw` removed (a verify false-positive had re-added them; cleaned).
- **`src/main/resources/application.yml`** — added `no-store` for static resources (the cache fix).
- **`docs/frontend-overhaul-plan.md`** — the full plan: feature inventory (no-loss proof), visual direction, graph fix, ES-autocomplete design, graphability, the chat-relations tangent, research provenance. **Read it for the "why".**

---

## 4. The no-loss contract

All **16** pre-existing features are preserved or upgraded — full table in `docs/frontend-overhaul-plan.md` §1. The only "replaced" item is the recall cone, and **every memory-card field survives** (kind, source, time, headline, rationale, alternatives, open loops, next action, repo, confidence bar) — it now renders in the `#memoryDetail` panel via the reused `renderMemory()`. Each phase was checked by a dedicated **no-regression verify lens**; all green.

---

## 5. What's next — exact next action

**Start P3: Elasticsearch query autocomplete** (design in plan §4). Concretely:
1. **Backend (Java, additive):** `GET /api/search/fields` (from ES `_field_caps`, cached; static curated list fallback when ES off) and `GET /api/search/values?field=&prefix=&limit=` (from ES `_terms_enum`; SQLite `DISTINCT … LIKE 'prefix%'` fallback). Touch `AgenticController` + `ElasticIndexClient`/`SearchService`. Keep `/api/search?q=` behavior unchanged.
2. **Frontend:** new classic script `querybar.js` — native `<input>` + aligned token-coloring overlay + small regex tokenizer (KQL-lite: `field:value`, `"quoted"`, `val*`, `field:*`, ranges, `AND/OR/NOT`, parens). Cursor-context suggestions (fields → values debounced ~120ms → operators). Inline syntax highlight + red error underline. **Plain free-text search must still work identically.**
3. **Wire** into the existing `#searchForm` input; on submit, structured KQL-lite translates to the ES query, free-text falls through to today's path.

**Then** (optional, only if scope/time): the fast-follow provenance graphs (plan §5). **Do not** build the chat-relations tangent without explicit greenlight.

---

## 6. Gotchas / landmines (these will bite a fresh agent)

1. **Deploy = rebuild jar + `kickstart -k`.** The running jar serves embedded static; source edits are invisible until you repackage. (§2)
2. **Don't rebuild the jar while a workflow is mid-edit** on `styles.css`/`app.js` — you'd package a half-written file. Wait for the workflow to finish.
3. **Verify-baseline false positives:** phase verify lenses diff against `HEAD`. **P1+P2 are now committed**, so `HEAD` includes them and P3's verify starts clean. Keep the habit: **commit a checkpoint between phases** so lenses don't false-flag prior-phase changes as "out of scope" or treat intentional selector removals as regressions (an uncommitted baseline already caused one bad auto-fix during P2 — dead CSS re-added, since cleaned).
4. **launchd respawn:** never plain-`kill`; use `launchctl kickstart -k gui/$(id -u)/com.nathan.sba-agentic`.
5. **SQLite WAL is live** (other agents may be writing via MCP). Restarts are ~2–4s; fine, but be quick.
6. **Latent nit:** constellation glyphs are colored per `kind` (decision/handoff). Recall only returns those two kinds today, but an unknown kind would render a colorless diamond — add a fallback `.constellation-glyph` fill if that ever changes.

---

## 7. How this is being built (workflow pattern)

Per Nathan's standing preference + the `codex-workflow-hybrid` skill: **Codex (gpt-5.5, xhigh) implements; Claude orchestrates + adversarially verifies; Claude owns git.** Each phase = a background `Workflow` with: **Implement** (one thin agent shells `codex exec`), **Verify** (3 parallel Claude lenses — functional / no-regression / aesthetic, structured schema, `real:true` only for genuine breakage), **Fix** (conditional `codex exec` on real issues only).

Codex invocation (memorize):
```bash
codex exec -c model='"gpt-5.5"' -c model_reasoning_effort='"xhigh"' -c service_tier='"priority"' \
  --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --color never -- "$(cat /tmp/<task>.md)"
```
Workflow scripts are session-scoped; a new session re-authors them. The P1/P2 scripts live under this session's `…/workflows/scripts/` if needed for reference.

Guardrails baked into every Codex task: edit only named paths; never `git add/commit/push`; no new deps/CDN; match existing style; terse summary out.

---

## 8. References

- **Plan (design rationale):** `docs/frontend-overhaul-plan.md`
- **Live UI:** `http://localhost:8766`
- **Black Box continuity events** (recall via the `sba-agentic` MCP `recallContext`/`recentSessions`): decision `e298be6d` (direction locked), observation `f51f6078` (jaws "make it a doer" tangent), handoff (this state) — see latest.
- **Todoist:** task `bf7dd11c` — rework `/jaws` + siblings into doers (research → implement), not research-only.
- **Checkpoint commit:** P1 + P2 + the cache fix + these docs are committed on `frontend-overhaul` (Nathan sole author, no AI attribution). Verify baseline is clean for P3. Nothing pushed.
