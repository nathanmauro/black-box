# Handoff — 2026-09-17 (showcase pass)

Branch `showcase-pass` from `main` at `1833e07`. This is the repo's living handoff: current state,
what was verified, and the ranked open loops. Durable history is in [docs/evolution.md](docs/evolution.md).

## What landed

- **README rewritten** around the smallest useful loop (capture → handoff → recall across Codex and
  Claude Code), with the terminal proof near the top, the coordination queue demoted to an extension,
  on-demand recall stated as a design choice, a "why the implementation matters" section with dated
  test results, an evolution table, and a self-recorded-history section built from the store's own
  210 captures about this repo (the 2026-06-10 positioning decision and the 2026-08-07 projection
  scorecard). The CI badge was removed rather than left pointing at a July run.
- **Five docs absorb the operations manual:** [agent-integration](docs/agent-integration.md),
  [operations](docs/operations.md), [runner](docs/runner.md), [evolution](docs/evolution.md),
  [futures](docs/futures.md). `PLAN.md` moved to `docs/history/2026-05-28-showcase-plan.md` as a dated
  record. `docs/fleet/spec.md` (private fleet ledger) is untracked and ignored; the local file remains.
- **Stale claims corrected:** `SBA_BIND_ADDRESS` "no built-in auth" row; "start in 60 seconds";
  "REST mirrors exactly"; Projection semantic recall (lexical only); `docs/architecture.md` on SQLite
  as the only store, the LIKE fallback semantics, authentication, and the after-commit fan-out claim
  (narrowed: completion-Handoff listeners run inside the outer task transaction); the phantom `spi/`
  package in package conventions and `CLAUDE.md`; the first-person workstation narrative in
  `docs/local-writes-and-elasticsearch.md`.
- **Local verification gate:** `scripts/verify.sh` (Java suite, frontend type check, vitest,
  whitespace; `--e2e` adds Playwright) and an optional pre-push hook via
  `git config core.hooksPath scripts/git-hooks`.
- **CI workflow** reduced to `workflow_dispatch` plus pushes to `main`, Ubuntu only, with a frontend
  job added. GitHub Actions stays **disabled at the repo level by choice**; the workflow is ready to
  run manually when enabled.
- `CHANGELOG.md` gained an Unreleased section covering everything since 0.1.0.

## Verification

- `./scripts/verify.sh` on this branch, 2026-09-17: Java 593 run, 0 failures, 0 errors, 11 skipped;
  type check clean; vitest 607 tests / 49 files.
- **Both CI jobs were executed as written in Linux containers before this branch could run on
  GitHub**, and then for real on GitHub. Two rounds of environment-specific failures came out of it:
  - *Container round.* Backend failed with 5 failures and 1 error: four runner script tests need
    `jq`, which the macOS machine has and the base Linux image does not, and the Finder-reveal test
    asserted a `/usr/bin/open` reveal that image lacks. Fixed by ensuring `jq` in the backend job and
    gating the reveal tests.
  - *Real-runner round (run 35282236056).* Frontend passed; backend still failed on one test, the
    fail-closed companion added in the previous round. The GitHub `ubuntu-latest` image **does** ship
    `/usr/bin/open`, so an OS-based gate was the wrong predicate: the test expected the command to be
    absent and found it present. Both reveal tests now key on the actual precondition the service
    checks (`/usr/bin/open` is a regular executable file) via JUnit assumptions, not on the OS.
    Verified in containers both with and without that command, and in the full suite under the
    command-present condition.
  - **Lesson worth keeping:** a container is not the runner. Verify environment-sensitive tests
    against the real image, or gate them on the condition the code actually tests.
- Every commit SHA, file path, and anchor cited by the README and the five docs was resolved against
  the repo; two independent fact-check passes (one Codex, one Claude) ran against source.
- `git diff --check` clean.

## Open loops (ranked)

1. **Re-add a CI badge once a green run exists for `main`.** Actions was re-enabled on 2026-09-17
   and the workflow is manual dispatch plus pushes to `main`, Ubuntu only. The badge stays out until
   a run for `main` is green, so it can never again show `passing` for a two-month-old commit.
2. **Consider whether `docs/fleet/spec.md` should be scrubbed from history.** It is untracked and
   gitignored as of this pass, but earlier commits still contain it, including a machine-specific
   instruction string. Removing it entirely would mean rewriting published history.
3. **Bi-temporal supersession** (`supersedes` on `captureDecision`, current-vs-historical recall) is
   the first item in [docs/futures.md](docs/futures.md) and the most requested primitive in adjacent
   tools.
4. **`query` separate from `scope` on recall** (seam at `ContextService.pathOrIdScope`); fold `ask`
   into `memory`. Do not extract the runner.
5. **SubagentStop auto-Handoff** (spawned sessions leave no handoff today).
6. **`captureHandoff` without `contextSummary` returns a raw NullPointerException** instead of the
   typed validation envelope every other tool uses.
7. **Open PRs:** #27 (Cursor agent, process monitor; `/api/processes` 404s live) and #21 (fleet
   round 1) are stale. Verify or close; merging #27 as-is would put non-maintainer commits on `main`.
8. **Runner config example** advertises a disabled engine that has no implementation; only `codex`
   and `fake` exist.
9. Earlier loops still valid: runner `cleanupWorktreeAndBranch` exception path ungated
   (`RunExecutor.java`), rev-list probe without `--` (`CrashRecovery.java`), no auto re-embed on
   model change.

## Gotchas (carried forward)

- Any `mvn package` (including the Playwright webServer and `verify.sh --e2e`) overwrites the jar a
  local service may run from. Restart it afterwards (macOS launchd:
  `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`).
- Never `git add -A` except scoped `git add -A src/main/resources/static` after a bundle rebuild.
- Recall hook stdout for Codex must not start with `[` or `{`.
- Test DBs are temp **files**, never `cache=shared` memory. Never point a second app at the live DB;
  snapshot with `sqlite3 sba-agentic.db ".backup <path>"`. The event table is `agent_events`.
- Playwright against the live app uses `domcontentloaded`, never `networkidle` (SSE).
- `POST /api/events` ingest takes `toolInput` / `toolOutput` as **objects**; `*Json` names are read-side.
- Surefire counts: clear stale reports before trusting aggregates.
