# Handoff — 2026-07-28 (fleet disposition + recall score semantics landed)

**Shipped**: fleet PRs #23 and #24 merged to main (`b1dbd6d`) after independent adversarial
verification of the prior review session's disposition; PR #21 stays a draft pending redesign;
fleet run 22-194034 acked and closed (round 2 done, round 1 rework). Then the **recall score
semantics slice** merged (`5e3aeab`) and deployed live.

## What landed

- **Recall score semantics** (`5e3aeab`, Codex-implemented, cross-model verified, deployed).
  `RecalledItem.score` is now the **true cosine similarity** between the query and the item —
  or `null` when there is honestly no number (lexical mode, missing vector) — never the RRF
  artifact (~0.016). Fusion still decides order; cosine is attached post-fusion, with lexical
  hits scored via a batched fetch from canonical `memory_embeddings`. A **measured relevance
  floor 0.61** (`sba.memory.recall.relevance-floor`, `0` disables) gates *semantic-only
  additions* pre-fusion — lexical hits are never dropped — so a junk query in hybrid mode
  returns an **honest zero** again. Basis, measured live 2026-07-28 on a corpus snapshot:
  true targets 0.620–0.688 (n=6), junk-query best hits 0.496–0.606 (n=10) — a clean gap
  [0.606, 0.620]. Re-measure after corpus growth or a model change via
  `SBA_EVAL_LIVE_MODEL=true` + `SBA_DATASOURCE_URL=<snapshot>` running
  `RecallCosineDistributionEvaluationTest` (never point a second app at the live DB; use
  `sqlite3 sba-agentic.db ".backup <path>"`). Suite: **406 tests, 0 failures, 2 skipped**
  (both env-gated harnesses), floor tests mutation-verified.
- **PR #23 — Preserve runner worktrees holding never-published commits** (`bb900d9`).
  `CrashRecovery.pruneIfClean` no longer treats porcelain-clean as licence to destroy: the
  destructive path is gated on `git rev-list --count HEAD --not --remotes <default>` == 0,
  and any probe failure preserves (fail-closed). This was a live data-loss bug on main —
  a worker commits before it reports, so any local-only ship left a clean worktree whose
  branch held the only copy, and the next daemon restart force-deleted it.
- **PR #24 — Paginate task listings for runner scans** (`78f581b`). PR #22's pagination commit
  rebased onto main (merging #22 as it stood would have landed #21's unreviewed code). SQL-level
  `LIMIT/OFFSET` with a unique `ORDER BY` tiebreaker, client pages at 250, short-page
  termination, loud failure at the 101-page cap. #22 closed superseded; its
  `fleet/round-2-paginate-task-listings` branch deleted from origin after the merge.

## Verification (do not re-litigate)

Four independent agents verified the review before merging (Black Box observation `6b5827a4`):
gate semantics probed empirically in throwaway repos (unpushed=1 preserve / pushed=0 prune /
no-remote=1 preserve / probe-failure preserve), #24 byte-compared to `ba8934f` with zero #21
leakage, both PRs test-merged together conflict-free. Post-merge `mvn test` on main:
**395 tests, 0 failures, 0 errors, 1 skipped** (the env-gated recall eval), all 81 surefire
reports fresh. Jar untouched (mtime 02:15) — live :8766 unaffected, no restart needed.

## New findings to carry (from the verification, none blocked the merges)

1. **The worktree-destruction hazard is narrowed, not closed.** `WorktreeManager.cleanupWorktreeAndBranch`
   on RunExecutor's `RuntimeException` catch path (`RunExecutor.java:378→391`) still force-removes
   worktree+branch with **no** reachability check. A `BlackBoxApiException` from `annotate`
   (`:306`) or `completeTask` (`:361`) after a local-only commit — e.g. the known jar-swap 500s —
   still destroys the sole copy. Pre-existing, contradicts the invariant #23 added to
   architecture.md.
2. **#23 trades data loss for a retry wedge.** Crash-after-commit → task reset to open, branch
   preserved → retry collides at `git worktree add -b` (branch exists) → task blocked until manual
   git surgery. Preserved-but-blocked beats destroyed; the #21 redesign (adoption) is the cure.
3. Preserved orphans accumulate unboundedly for repos whose ships are local-only by config —
   no collector exists.
4. Minor, fail-safe direction: the rev-list probe lacks a `--` terminator (a file named `main`
   → permanent over-preservation); `defaultBranch` fallback returns the checkout's *current*
   branch, not the repo default.

## PR #21 (adopt-alive-runner-sessions) — draft, redesign required

All four review defects **confirmed at file:line**, plus three more found:
adopted-done work gets pruned on the *next* restart (success path leaves the worktree
unprotected on that branch's pre-#23 code); adoption keys on tmux liveness, so a reboot loses
reported-done work; a transient `completeTask` failure resets a confirmed-done run to open →
worktree collision → blocked. Redesign constraints (from the review, verified): lane-scoped to
`auto` only, needs a ship-already-ran marker (`since = task.updatedAt()` never advances for
in_progress tasks — annotations don't bump `updated_at`), should adopt on reported-done
regardless of session liveness, and shared terminal handling belongs in
`runner.internal.application` (a new top-level package trips the 8-module assertion).

## Open loops (ranked)

1. **#21 redesign** as lane-scoped adoption per constraints above; also cures the retry wedge.
2. Safety follow-ups from verification: gate `cleanupWorktreeAndBranch`'s exception path on
   reachability; add `--` to the rev-list probe.
3. `ask` module still has the nomic prefix defect (bare prompt, no `search_query:`) and its knn
   targets the foreign Elasticsearch index; pointing it at the SQLite vector store fixes both.
4. Two embedding HTTP clients exist (`ask`, `memory`); consolidate behind one public port in
   `memory` (cycle ratchet blocks the reverse direction).
5. Separate `query` param on recall so scope and subject can differ; Tier 2 full-corpus
   semantic search (sqlite-vec's real payoff); re-embedding is not automatic on model change
   (`content_hash` covers text, not model).
6. Floor caveat worth remembering: 0.61 sits inside the *cluster* overlap (junk p90 was 0.577
   but individual noise items can exceed it), so above-floor noise is reduced, not impossible;
   the floor's job is honest-zero for junk queries, and `score` remains a within-query ranking
   signal, not proof of relevance. Live post-deploy smoke found a real match at **0.6109** —
   0.0009 above the floor and below the harness's measured target minimum (0.620) — so treat
   0.61 as a ceiling, not a starting point, when re-tuning; raising it will drop real matches.

## Gotchas (carried forward)

- Any `mvn package` (incl. the Playwright webServer) overwrites the live jar → run
  `scripts/deploy-local.sh` after, then `launchctl kickstart -k` if 500s persist.
- Never `git add -A` (except scoped `git add -A src/main/resources/static` after a bundle rebuild).
- Module ratchet: `memory → {project, recording}`; `memory` must never import `ask`.
  Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`.
- Test DBs are temp **files**, never `cache=shared` memory.
- Surefire counts: clear stale reports before trusting aggregates.
- codex `-c` overrides cannot address dotted/quoted keys; trust lookup wants the physical path.
- Playwright against the live app: `domcontentloaded`, never `networkidle` (SSE).
