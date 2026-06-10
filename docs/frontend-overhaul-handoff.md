> Internal development handoff notes — not user documentation.

# Black Box Frontend Overhaul — START HERE (session handoff)

**Last updated:** 2026-06-05 · **Branch:** `frontend-overhaul` · **State:** P1 + P2 + **P3 shipped, live, verified from the served jar, and committed** (P3 = `0258d81`). Verify baseline clean for the next phase. Nothing pushed.

> **TL;DR for a fresh agent / new session:** We are doing a visual + UX overhaul of the Black Box recorder's web UI (vanilla JS + CSS served by a Spring Boot jar). The hard rule is **no existing feature is removed or regressed** (see the 16-point inventory in `docs/frontend-overhaul-plan.md` §1). Three phases are done, verified, and running at `http://localhost:8766`: **P1** replaced the old recall "cone" with a real **Recall Constellation** graph (`graph.js`), **P2** restyled everything into a sharpened **"Instrument"** aesthetic (CSS-only), and **P3** added **Elasticsearch query autocomplete** — a KQL-lite query bar (`querybar.js`) over a token-coloring overlay, backed by two additive endpoints (`/api/search/fields`, `/api/search/values`). Read this whole file, then `docs/frontend-overhaul-plan.md` for full design rationale.

---

## 1. Current status

| Phase | What | State |
|---|---|---|
| P0 | Modularize | **Folded into P1** — instead of an ES-module refactor of `app.js`, new code ships as a **classic-script global** (`graph.js` = an IIFE exposing `window.BlackBoxConstellation`). Lower regression risk. |
| **P1** | **Recall Constellation graph** | ✅ **Done, verified, live.** Hand-rolled deterministic radial SVG graph replaces `drawCone()`. Clusters collapsed-by-default, click-to-expand, viewBox zoom/pan, fit button, LOD label-hiding, hover dim/highlight, keyboard + ARIA, `AbortController` teardown, reduced-motion gated. |
| **P2** | **"Instrument" restyle** | ✅ **Done, verified, live.** CSS-only reskin of `styles.css`: machined chassis, bezel/screen/tick tokens, LED gauges, oscilloscope screen for the constellation, strip-chart baseline behind the Spine, corner ticks, custom scrollbars, `:focus-visible`. Amber kept as signal-only. |
| **P3** | **ES query autocomplete** | ✅ **Done, verified from the served jar, live.** KQL-lite query bar (`querybar.js`) — native `<input>` + char-aligned token-coloring `<pre>` overlay, regex tokenizer (`field:value`, `"quoted"`, `val*`, `field:*`, ranges, `AND/OR/NOT`, parens), red wavy underline on unbalanced quotes/parens, cursor-context suggestions (fields → debounced ~120ms values → operators). Two additive backend endpoints (`/api/search/fields`, `/api/search/values`) with ES → curated/SQLite fallbacks. Plain free-text submit is byte-for-byte unchanged (input keeps `name="query"`). Design in `docs/frontend-overhaul-plan.md` §4. |
| Fast-follow | Provenance graphs | Not started. Within-session decision→handoff→next-action DAG + recall-provenance. Zero new backend (data already in `/api/sessions/{id}/events` + `/api/recall`). Plan §5. |
| **Tangent** | Chat/session relations | ⛔ **PARKED — do NOT build without Nathan's greenlight.** Plan §6. Rides existing Elasticsearch (`adjacency_matrix`/Graph `_explore`), no new datastore. |

---

## 2. How to run / deploy / verify  ← read this, the deploy path is non-obvious

The app runs as a **launchd service**, serving static files **from inside a packaged jar** (NOT from `src/` or `target/classes`). Editing source files does **nothing** until you rebuild the jar and restart the service.

- **Service:** `${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}` (LaunchAgent at `~/Library/LaunchAgents/${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}.plist`) → `java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar` on port `8766`.
- **It auto-respawns** (launchd KeepAlive). Do **not** plain-`kill` it — it comes back on the *old* jar. Use `kickstart -k`.

**Deploy a frontend or backend change (the full loop):**
```bash
cd /path/to/black-box
mvn -q -DskipTests package                                  # rebuild jar (copies new static into the jar)
launchctl kickstart -k "gui/$(id -u)/${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}" # kill+restart with the new jar
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

**P3 additions (ES query autocomplete):**
- **`src/main/resources/static/querybar.js`** (NEW) — `window.BlackBoxQueryBar = { attach, mount, … }`. Classic script (no import/export), loaded **after** `app.js` (`index.html:144`). Wires the existing `#searchForm` input: a transparent-text native `<input>` (caret amber, `z-index 1`) rides over an absolutely-positioned `<pre>` overlay (`z-index 0`) whose colored `<span>` tokens align char-for-char via a matched box model. KQL-lite tokenizer + recursive-descent parser (precedence `NOT > AND > OR`), red wavy underline on unbalanced quotes/parens, cursor-context suggestion popover (fields → debounced ~120ms values → operators). **Independence guarantee:** only `addEventListener("input"/"keyup"/"keydown"/…)` — never re-creates the input; `preventDefault` on Enter is gated strictly to "popover open AND a row highlighted," so a plain Enter falls through to native form submission untouched. Lazy-fetches `/api/search/fields` on first interaction so the bar works stand-alone.
- **`src/main/java/.../web/AgenticController.java`** — two additive `@GetMapping` endpoints under `/api`:
  - **`GET /api/search/fields`** → `List<Map<String,Object>>` of `[{name,type,searchable,aggregatable}]` (live ES `_field_caps`; curated fallback when ES off). Verified `200`.
  - **`GET /api/search/values?field=&prefix=&limit=`** → JSON `string[]` of matching values (ES `_terms_enum`; SQLite `DISTINCT … LIKE 'prefix%'` fallback). Verified returns a JSON array, prefix-filterable.
  - `GET /api/search?q=` behavior **unchanged** — plain free-text still returns `{query, local[], elastic[], elasticHealth}` (verified 25/25 hits, no regression).
- **`src/main/java/.../search/SearchService.java`** — field list + value enumeration with caching. **ES-off fallback behavior:** fields fall back to a `CURATED_FIELDS` list mirroring the ES mapping (so autocomplete still works); values fall back from `_terms_enum` to the SQLite `DISTINCT … LIKE 'prefix%'` path when ES returns empty/off. Field list cached (soft-refresh + hard-TTL, single-flight reload).
- **`src/main/java/.../search/ElasticIndexClient.java`** — `fieldCaps()` + `termsEnum(field, prefix, limit)` thin clients over ES `_field_caps` / `_terms_enum`.
- **`src/main/resources/static/index.html`** — `#searchForm` query bar markup (`#queryInput` `name="query"`, `#queryOverlay` `<pre class="qb-overlay">`, `#qbPop` listbox); loads `/querybar.js`.
- **`src/main/resources/static/styles.css`** — query-bar Instrument styling: `.qb-*` token-coloring family (each KQL-lite token its own machined hue; amber reserved for the wildcard "live match" signal), red error underline, deep-screen suggestion popover. (Note: an additive `.token-*`/`.query-suggestions` "acceptance API" alias block also exists but is currently inert — the live runtime vocabulary is `.qb-*`.)

**Deploy + verify (T10, done):** `mvn -q -DskipTests package` → `launchctl kickstart -k gui/$(id -u)/${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}` → wait for `/api/status`. Verified from the **served jar**: `curl /` → `querybar` count = 1; `/api/search/fields` = `200`; `/api/search/values?field=source&prefix=` = `["claude","cli","cockpit","codex","manual"]` (prefix-filterable); free-text `?q=` returns results (row-5 no-regression); Enter submits both inside and outside the ~120ms debounce window (submit handler at `app.js:619` reads `FormData` live and is on an independent event channel from the debounced suggestion fetch; Enter only `preventDefault`s on a highlighted popover row). `mvn test` → 38 tests green.

---

## 4. The no-loss contract

All **16** pre-existing features are preserved or upgraded — full table in `docs/frontend-overhaul-plan.md` §1. The only "replaced" item is the recall cone, and **every memory-card field survives** (kind, source, time, headline, rationale, alternatives, open loops, next action, repo, confidence bar) — it now renders in the `#memoryDetail` panel via the reused `renderMemory()`. Each phase was checked by a dedicated **no-regression verify lens**; all green.

---

## 5. What's next — exact next action

**P3 (Elasticsearch query autocomplete) is DONE, verified from the served jar, and committed** at `0258d81` (Nathan sole author, no AI attribution). The verify baseline is clean for the next phase. Nothing pushed.

Resolved during the P3 commit (don't re-flag these as open):
- `app.js` boots the bar via `attach(els.searchForm, {fields, fetchValues, pop})` with an init-time `/api/search/fields` fetch — the legacy `mount()` path is gone.
- The CSS class-name vocabulary was reconciled: **`qb-*` is the blessed contract**; the inert `.token-*` / `.overlay-tokens` / `.query-suggestions` / `.query-error` "acceptance API" aliases were pruned (no dead CSS in the tree). The live `.qb-tok--incomplete` dim-state rule is the one survivor and is kept.

**Next action (optional, only if scope/time): the fast-follow provenance graphs** (plan §5) — within-session decision→handoff→next-action DAG + recall-provenance, zero new backend (data already in `/api/sessions/{id}/events` + `/api/recall`). **Do not** build the chat-relations tangent (plan §6) without explicit greenlight.

---

## 6. Gotchas / landmines (these will bite a fresh agent)

1. **Deploy = rebuild jar + `kickstart -k`.** The running jar serves embedded static; source edits are invisible until you repackage. (§2)
2. **Don't rebuild the jar while a workflow is mid-edit** on `styles.css`/`app.js` — you'd package a half-written file. Wait for the workflow to finish.
3. **Verify-baseline false positives:** phase verify lenses diff against `HEAD`. **P1+P2 are now committed**, so `HEAD` includes them and P3's verify starts clean. Keep the habit: **commit a checkpoint between phases** so lenses don't false-flag prior-phase changes as "out of scope" or treat intentional selector removals as regressions (an uncommitted baseline already caused one bad auto-fix during P2 — dead CSS re-added, since cleaned).
4. **launchd respawn:** never plain-`kill`; use `launchctl kickstart -k gui/$(id -u)/${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}`.
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
- **Checkpoint commits** on `frontend-overhaul` (Nathan sole author, no AI attribution, nothing pushed): P1 + P2 + cache fix + docs = `0b28e58`; **P3 (query bar) = `0258d81`**. Verify baseline is clean for the next phase.
