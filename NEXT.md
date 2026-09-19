# Handoff — 2026-09-19 (verified local recovery and capture)

Current reviewed implementation is on local branch `codex/2026-09-19-blackbox-improvements`,
through `15ff1c5`, based on `6045d70`. The server candidate was deployed to the existing local
installation and verified through HTTP and the browser. The changes are ready for pull-request
review; local deployment does not imply a merge or released version. The separate dirty canonical
checkout was preserved. This file is a continuation
checkpoint; selected execution status remains in Linear. Earlier showcase history remains in Git
and [docs/evolution.md](docs/evolution.md).

## Completed slices

- Structured capture validates required identity and content at the common service boundary,
  including MCP calls. Missing handoff context now returns actionable validation instead of an
  implementation dereference. [Acceptance and evidence](docs/superpowers/plans/2026-09-19-structured-capture-validation.md).
- Runner failure/restart cleanup preserves dirty files, untracked and ignored files, unique
  commits, uncertain ownership, and live or uncertain workers. Recovery pointers survive API
  failure. [Runner behavior and limits](docs/runner.md).
- `POST /api/events/idempotent` binds an immutable capture identity to the recognized request;
  matching retries return the original event and session without a duplicate event or counter
  update. [Protocol and compatibility](docs/idempotent-capture.md).
- Delayed legacy or keyed captures preserve the greatest observed session activity time, including
  fractional-second precision and concurrent writes. This does not repair historically regressed
  checkpoints or change first-persisted `startedAt` semantics.
- The optional hook outbox sanitizes before private local storage, bounds the whole hook, retains
  unacknowledged rows, and retries immutable requests. It remains **opt-in**; global hooks were not
  changed. Read [the limits and activation instructions](docs/durable-capture.md) first.
- Recall exposes **Read recalled context** with an explicit full-source link; Browse exposes
  **Read full handoff**. Legacy first-line recall excerpts are labeled accurately. The existing
  fix for misleading handoff confidence is also present in the deployed candidate.
- Local deployment supports a verified prebuilt artifact, exact installation identity, retained
  recovery copies, confirmed shutdown, atomic replacement, and verified binary rollback.
  [Operations and recovery](docs/operations.md).

## Verification and boundaries

- Integrated Java: **637 tests, zero failures/errors, three intentional environment skips**,
  including SQLite and disposable real PostgreSQL contracts. The earlier broad run exposed a
  launcher fixture timeout; its focused rerun and this final full run passed.
- Frontend: **607 tests / 49 files**, production build and package. Actual browser checks covered
  long paragraphs, literal HTML, mouse and Enter/Space activation, exact source navigation,
  legacy multiline records, and 390-pixel viewport wrapping. This is browser evidence, not a
  physical-phone or full accessibility audit.
- Deployment: **26 actual-entrypoint scenarios**; copied old → new → old binaries preserved a
  disposable SQLite history and normal read/append behavior. Binary rollback does not undo data
  changes; the old binary cannot serve the new idempotent route.
- Hook: **32 durability tests plus 20 existing hook checks**. Actual hook → Java → SQLite proof
  covered outage, committed response loss, server restart, identical retries and one stored event.
  Fresh review caught and corrected raw Bash temporary files, secret-bearing JSON keys and an
  input-wait deadline gap. Pre-acceptance loss and unknown-secret limits remain explicit.
- The default local verification gate and PR backend job run the hook/deployment suites before
  Java, so these failure-path checks are part of routine regression coverage.
- Fresh independent reviews passed. Clean packaging was checked against source to exclude stale
  hashed assets. The local deployment preserved configuration and database identity, retained a
  private point-in-time backup and previous binary, and passed representative live routes. A real
  deployment handoff was acknowledged, replayed once without duplication, recalled and opened at
  its exact source in the deployed UI.

## Remaining decisions and evidence gaps

1. Decide when to activate durable capture in a particular client after reviewing its local data
   and retry limits. No implicit activation, remote routing, authentication expansion or scheduler.
2. Preserve the existing continuation-evaluation gates. The five-case bare-agent ceiling remains
   a negative result; checkpoint reconstruction and today's functional recovery proof do not show
   superiority over ordinary handoff/search, adoption or productivity gains. Do not tune another
   benchmark merely to manufacture a memory win; require a fresh, predeclared hypothesis.
3. Review separate lifecycle/forgetting work against actual retention and canonical-storage
   invariants before integration. This mission did not absorb unrelated drafts or dirty work.
4. Supersession, query-versus-scope separation, automatic subagent handoffs and embedding refresh
   remain possible future investments, not accepted work or proven customer demand. Old PR, CI
   badge and history-cleanup observations need current verification and publication authority.

## Operational cautions

- **Never package over a JAR used by a running service.** Build in an isolated checkout and use
  `./scripts/deploy-local.sh --prebuilt-jar /absolute/path/to/verified.jar`. Rebuild mode is only
  for the configured installation checkout and stops the service before Maven. See operations.
- A normal Maven package can retain obsolete files in `target/classes`. Build the final artifact
  from a clean, non-running target and verify generated asset references.
- Stage exact owned paths. Generated frontend files come from the source build, never hand edits.
- Test databases are disposable files, not the live database. Use a consistent online backup for
  live recovery preparation; never start a second application against the canonical store.
- Keep recall-hook stdout free of leading `[` or `{`; ingest takes object-valued `toolInput` and
  `toolOutput`, while `*Json` names belong to read-side data.
- Browser readiness uses a loaded document or concrete UI state, not `networkidle` with SSE.
- Preserve fresh test reports before cleaning the build; stale report aggregates are not proof.
