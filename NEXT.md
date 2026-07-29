# Handoff — 2026-07-29 (slice 2 shipped: Recall links + exact event lookup)

**Shipped**: Phase 1 **slice 2** of the stream-first consolidation
(`docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md` §13) on branch
`stream-observatory-consolidation`. After the ship commit the branch is **15 commits ahead of
`main`**, deployed live on `:8766`, and **local-only / not pushed** (no upstream set).

## What landed

- **Recall lineage**: `RecalledItem` now carries its owning internal `sessionId` through REST and
  MCP recall responses.
- **Exact event read**: `GET /api/events/{id}` returns the canonical `AgentEvent`; an unknown id
  returns `404`. The Board now resolves `resultHandoffId` with this primary-key read instead of a
  full-year Recall scan.
- **Recall → Browse**: every Recall card head links to its owning session and exact event. The link
  includes an explicit all-project URL override, so remembered Activity scope cannot discard a
  repo-less target.
- **Capped-session safety**: Browse loads the exact target separately when it falls outside the
  normal 2,000-event session batch, verifies the returned event belongs to the selected session,
  and merges it into the reader before highlighting it.
- **Stream budget repaired**: expanded panels use CSS layout containment. Live 500-row expand-all
  now measures **85 ms median** (83 / 85 / 91 ms), under the < 100 ms budget, with all 500 rows and
  the final row still accessible. See
  `docs/superpowers/plans/2026-07-28-stream-perf-notes.md`.
- **Contracts and docs**: REST/MCP shape fixtures, endpoint snapshots, README, architecture notes,
  implementation plan, tests, and the committed Vite bundle all match the shipped behavior.

## Verification

- Backend: `mvn -q test` green, including exact-event found/404, Recall lineage, REST/MCP contract,
  Modulith, and architecture checks.
- Frontend: **299/299** tests across 36 files; TypeScript and Vite production build green.
- Packaged browser suite: **23/23** Playwright tests green against an isolated database; protected
  production port, DB identity, and production synthetic-event count remained unchanged.
- Independent backend review: clean.
- Independent frontend review found and verified fixes for three edge cases: errored Solid resource
  access on Board, remembered project scope hiding repo-less Recall targets, and targets older than
  Browse's 2,000-event batch. Final re-review: clean.
- Live deploy: launchd PID `89718`, status healthy, Elasticsearch and local AI reachable, bundle
  `index-DJCzXC4L.js` + `index-C44qCCYV.css` serving.
- Live use proof: Recall returned owning `sessionId`, the card URL retained `session` + `event` +
  explicit `project=`, Browse selected the target event, and the All projects scope remained active.
- `git diff --check` is part of the final close gate.

## Open loops (ranked)

1. **Slice 3 — file links / open-in-editor** (spec §4.10 and §13): the next independently shippable
   slice and the first meaningful security surface. Resolve paths against the project catalog and
   fail closed for paths outside known project roots.
2. Then slice 4 (live/process monitor), slice 5 (narrative + remaining presenters), and slice 6
   (route promotion + Memory merge). New top-level routes MUST be added to
   `SpaForwardingController.java:15`.
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
- Module ratchet: `memory → {project, recording}`; `memory` must never import `ask`.
  Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`.
- Test DBs are temp **files**, never `cache=shared` memory. Never point a second app at the live DB;
  snapshot with `sqlite3 sba-agentic.db ".backup <path>"`. The event table is `agent_events`.
- Playwright against the live app uses `domcontentloaded`, never `networkidle` (SSE).
- `POST /api/events` ingest takes `toolInput` / `toolOutput` as **objects**. The `*Json` names are
  read-side only.
- A Recall link deliberately uses `project=` to override remembered Activity scope without erasing
  the remembered preference. Absence of the parameter still restores the remembered project.
- Surefire counts: clear stale reports before trusting aggregates.
