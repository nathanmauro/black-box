# Handoff — 2026-07-29 (slice 3 shipped: secure file links + editor open)

**Shipped**: Phase 1 **slice 3** of the stream-first consolidation
(`docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md` §13) on branch
`stream-observatory-file-links`. The ship commit is one local commit above local `main` at
`9eab7de`. It is deployed live on `:8766` and remains **local-only / not pushed** (no upstream
set). `origin/main` remains intentionally 15 commits behind local `main`.

## What landed

- **Catalog-authorized file references**: `GET /api/projects/code-scopes` projects only
  session-backed, filesystem-verified Git checkout/worktree scopes from the existing project
  catalog. Broad, stale, non-Git, meld-only, root, home, and no-project scopes cannot authorize a
  file action.
- **Fail-closed resolution**: open/reveal requests carry only an opaque catalog `projectKey` plus a
  relative path and optional position. The backend revalidates the exact scope on every request,
  rejects traversal/absolute paths and symlink escape, requires a readable regular file, and checks
  one-based line/column bounds.
- **Shell-free local actions**: `POST /api/open-in-editor` uses two fixed argv calls for the verified
  workspace and canonical file/position. `POST /api/reveal-in-finder` uses fixed
  `/usr/bin/open -R` argv. Executables are absolute, executable, allowlisted Cursor/VS Code-compatible
  CLIs; timeouts and launch failures fail honestly.
- **Useful file UI**: resolved presenter and patch paths open in the editor, copy the raw display
  path, or reveal in Finder. Unresolved paths stay copy-only with a visible reason. Catalog loading,
  failure, retry, and refresh are distinct states; typed backend/clipboard failures render locally.
- **Accessible actions**: controls name the action and target, unresolved Finder actions remain
  focusable with a described reason, and compact fallback targets meet a 24 px minimum.
- **Packaged injection proof**: Playwright drives a literal
  `$(touch${IFS}$SBA_E2E_INJECTION_SENTINEL).ts` filename through presenter → resolver → HTTP →
  fake editor and verifies it remains one argv field with no sentinel side effect.
- **Contracts and docs**: REST/wire fixtures, endpoint snapshots, README, architecture notes, focused
  plan, tests, and the committed Vite bundle match the shipped behavior.

## Verification

- Backend: `mvn -q test` green, including resolver, controller, configuration binding, launcher,
  REST/wire contracts, Modulith, and architecture checks.
- Frontend: **316/316** tests across 39 files; TypeScript and Vite production build green.
- Packaged browser suite: final clean run **24/24** green in 2.1 minutes against an isolated
  database and fake editor. Protected production PID, DB identity, and synthetic-event count
  remained unchanged. One earlier run exposed an unrelated stale Board live projection after its
  timeline had already recorded `in_progress → done`; the scenario passed alone in 19.1 seconds
  and again in the clean full run.
- Independent backend review found eight resolver/contract/launcher gaps; all were fixed and
  covered. Final re-review found no high/medium issue; its one low process-cleanup residual was also
  closed by stopping and awaiting parent/child processes on timeout or interruption. Independent
  frontend review found catalog-state, accessibility, adversarial E2E, and failure-state coverage
  gaps; all were fixed, covered, and clean on final re-review.
- Live deploy: launchd PID `27496`, status healthy, Elasticsearch and local AI reachable, bundle
  `index-XHqOdHft.js` + `index-D-8V2GB4.css` serving.
- Live negative proof: 188 eligible scopes; `/`, `/Users/nathan`, and `__no_project__` absent.
  Unknown key, traversal, absolute relative path, and missing file returned typed
  `409 / 403 / 403 / 404`; Cursor PID `12766` and Finder PID `645` did not change.
- Live use proof: the real T3 Code `Read` event opened from Stream and reported
  `Opened in editor.` Cursor `--status` reported `Window (service.ts — t3code)` and the owning
  `t3code` workspace; read-only Cursor editor state recorded that exact file as MRU with the cursor
  at line 40, column 1. Desktop accessibility and screen-capture APIs timed out or lacked
  permission, so this is Cursor-native runtime/state proof rather than a screenshot.
- `git diff --check` is part of the final close gate.

## Open loops (ranked)

1. **Slice 4 — live/process monitor** (spec §4.11 and §13): add the next independently shippable
   slice without broadening file-action authority.
2. Then slice 5 (narrative + remaining presenters) and slice 6 (route promotion + Memory merge).
   New top-level routes MUST be added to `SpaForwardingController.java:15`.
3. **#21 redesign** (adopt-alive-runner-sessions) as lane-scoped adoption; also cures the retry
   wedge from #23 (crash-after-commit → branch-exists collision → task blocked).
4. Runner safety follow-ups: gate `cleanupWorktreeAndBranch`'s exception path
   (`RunExecutor.java:378→391`) on reachability; add `--` to the rev-list probe; no collector exists
   for preserved orphan worktrees.
5. `ask` module: nomic prefix defect (bare prompt, no `search_query:`) + its kNN query targets the
   foreign Elasticsearch index. Pointing it at the SQLite vector store fixes both. The two embedding
   HTTP clients (`ask`, `memory`) still want consolidation behind one public port in `memory`.
6. Recall: separate `query` from `scope`; Tier 2 full-corpus semantic search; re-embedding is not
   automatic on model change. The 0.61 floor is a ceiling, not a starting point.

## Gotchas (carried forward)

- Any `mvn package` (including the Playwright webServer) overwrites the live jar. Run
  `scripts/deploy-local.sh` afterward, then `launchctl kickstart -k` if 500s persist.
- Never `git add -A` except scoped
  `git add -A src/main/resources/static` after a bundle rebuild.
- File authorization is the current `/api/projects/code-scopes` projection, not `ProjectKey.decode`
  and not the broad `/api/projects` catalog. Preserve the exact matched worktree/scope key.
- The default direct Cursor CLI works under launchd's minimal PATH; the
  `/Users/nathan/.local/bin/cursor` shim does not.
- Module ratchet: `memory → {project, recording}`; `memory` must never import `ask`.
  Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`.
- Test DBs are temp **files**, never `cache=shared` memory. Never point a second app at the live DB;
  snapshot with `sqlite3 sba-agentic.db ".backup <path>"`. The event table is `agent_events`.
- Playwright against the live app uses `domcontentloaded`, never `networkidle` (SSE).
- `POST /api/events` ingest takes `toolInput` / `toolOutput` as **objects**. The `*Json` names are
  read-side only.
- Surefire counts: clear stale reports before trusting aggregates.
