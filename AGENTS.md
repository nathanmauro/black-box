# Black Box - agent guide

Black Box is the public/product name for this repo. The repo directory and Maven artifact are still
`sba-agentic`.

Black Box is a local-first, writable/queryable memory bus for coding agents. Agents commit
structured intent through decisions, handoffs, and observations, then recall that context later
through MCP, HTTP, CLI, or the web surface. The selected relational database is canonical:
SQLite remains the local default; the optional PostgreSQL profile supports a shared server.
Treat Elasticsearch and model summaries as optional supporting systems.

## Response style

Default to normal prose: concise, direct, professional, and complete.

## Working here

- Treat this as a product repo, not the Cockpit private control-plane repo.
- Keep public docs honest: semantic/vector recall currently surfaces structured intent
  (`Decision`, `Handoff`, `Observation`, `Idea`, `Evidence`) only. The memory index also stores
  session-summary vectors for backfill/future retrieval, but recall does not return summaries
  today; the full event corpus is not semantically indexed. Do not claim streaming UI behavior or
  other roadmap items until they exist and are verified.
- Avoid committing private machine state: local databases, `.codex` or `.claude` configs, IDE files,
  hook payload dumps, credentials, env files, and absolute workstation paths unless clearly marked as
  examples.
- Preserve local-first behavior and the SQLite default. A PostgreSQL deployment owns its separate
  canonical database; selecting a profile does not synchronize history or permit multiple API
  replicas. Elasticsearch remains an optional secondary index. Session summaries currently default to the
  bundled Codex cloud wrapper; document that transcript text can leave the machine in that mode.
  Use `SBA_SUMMARY_BACKEND=local` only when explicitly choosing the LM Studio/OpenAI-compatible
  local model path.
- Preserve the write-plus-query loop as the core product distinction. Black Box is not a passive
  transcript dashboard.
- Follow existing Java, Spring Boot, Maven, shell, and static frontend patterns before adding new
  abstractions.
- Keep edits narrow. Avoid unrelated refactors, public copy churn, or visual redesign drift unless
  the task asks for it.

## Methodical workflow

Use this workflow for multi-step work or anything that touches storage, hooks, MCP, search, exports,
or live services.

1. Recall
   - Read the nearest `AGENTS.md`, plus relevant repo docs.
   - Check recent local decisions or handoffs when prior context matters.
   - Inspect real files, commands, process state, API responses, DB rows, or UI behavior before
     assuming.

2. Frame
   - State the current slice and what is out of scope.
   - Identify external or persistent systems involved: SQLite DBs, Elasticsearch, local model
     servers, MCP clients, hook config, launchd jobs, exported docs, or browser state.
   - For risky changes, write down the safety contract before changing code.

3. Plan
   - For substantial work, publish the spec and its tickets to Linear (see
     [Issue tracker](#issue-tracker) below). An agent whose harness still uses a plan-file workflow may
     write the plan under `docs/superpowers/plans/` instead; the plans and specs already there
     stay as history.
   - Keep tasks independently verifiable.
   - Record observed results when they differ from expectations.

4. Fake first
   - Prefer fixtures, fake servers, in-memory databases, and targeted tests before live systems.
   - Test failure modes as well as the happy path.
   - Use read-only proof before any live write.

5. Gate mutations
   - Default to dry-run or read-only checks when touching live or user-visible systems.
   - Require explicit flags or allowlists for separate write behaviors.
   - Fail closed when credentials, scopes, runtime state, or prerequisites are missing.
   - Do not broaden scopes or use destructive APIs to get around a blocker.

6. Implement narrowly
   - Keep changes close to the requested behavior.
   - Prefer structured parsing and explicit state over ad hoc text handling.
   - Leave unrelated behavior, docs, and generated assets alone.

7. Verify
   - Run the smallest targeted test first.
   - Run the relevant suite before claiming completion.
   - Run `git diff --check` on touched files.
   - When behavior is live, verify the real local surface: command output, HTTP response, DB row,
     generated file, hook log, MCP result, UI state, or process state.

8. Document and close
   - Repo behavior, commands, tests, architecture, and agent instructions belong in repo docs.
   - Durable human-facing knowledge belongs in the Obsidian vault when Nathan says "update my docs".
   - Concrete actions belong in Linear, not in Black Box.
   - Decisions, handoffs, observations, and parked tangents belong in Black Box.
   - If future work remains, leave a concise handoff with current state, verification, open loops,
     and one next useful action.

## Black Box event discipline

Use Black Box for structured continuity, not for bulk transcript storage or task management.

- `Decision`: use when choosing a path future agents should not re-litigate without new evidence.
  Include rationale, rejected alternatives, confidence, open loops, and repo or topic.
- `Handoff`: use when stopping with work still open. Include current state, files changed or
  inspected, verification already run, open loops, next action, and whether any live system was
  touched.
- `Observation`: use for concrete facts that may matter later, such as command output summaries,
  API errors, counts, IDs, paths, process state, or UI behavior.
- `Idea`: use for an idea someone proposed that nobody is acting on now, whether the human's aside
  or an agent's suggestion. Capture it with `captureIdea` (origin, one-liner, verbatim quote, legs,
  status); record a later status change as a new capture with the same `ideaKey`.
- `Evidence`: use for a verifiable fact with provenance, optionally linked to an Idea, Decision,
  or Handoff as supporting or refuting evidence. Include the claim and source reference.
- `Parked tangent`: use when a related side path would derail the current task but should be
  findable later.

Do not put secrets, raw env files, large logs, full duplicate docs, or ordinary Linear tasks into
Black Box.

Clients outside this Mac (ChatGPT, cloud coding agents, voice, automation) follow the
[cross-environment capture contract](docs/cross-environment-capture.md): declare an `origin`, keep
provenance, capture only durable kinds, and end substantial work with a templated handoff.

## Verification commands

Use the narrowest relevant set for the change:

```bash
mvn test
mvn -q -DskipTests package
curl -fsS http://localhost:8766/api/status | jq
curl -fsS 'http://localhost:8766/api/recall?scope=/repos/black-box&withinHours=168&kinds=decision,handoff' | jq
git diff --check
```

For hook work, smoke-test `scripts/hooks/sba-agent-hook.sh` with a small fixture before documenting
or trusting live client wiring.

For demo or showcase work, run `./scripts/demo.sh` and verify the visible recall loop before updating
README claims or assets.

Frontend lint and formatting are standard npm scripts under `frontend/`: `npm run lint`
(ESLint with typescript-eslint and eslint-plugin-solid), `npm run format` (Prettier, in place) and
`npm run format:check`. Run `npm run format` on any frontend file you edit before committing; CI and
`scripts/verify.sh` fail on lint errors or unformatted files. Do not switch rules off to get a green
run; scope a justified exemption to the narrowest file set and say why in `eslint.config.js`. See
[Frontend standards](docs/frontend-standards.md).

Java formatting is opt-in through `mvn -Pjava-format spotless:check` / `spotless:apply`.
Use the pinned Palantir formatter and the AST-based blank-before-return step; do not substitute
Eclipse or regex rewrites over Java source. Scope routine formatting with `spotlessFiles` (an
absolute-path regex), preserve unrelated dirty work, and reserve a whole-project apply for an
explicit formatting pass. See [Java formatting](docs/java-formatting.md) for exact commands and
disposable verification. Normal Maven builds intentionally have no formatting gate.

## Agent skills

The workflow skills (`/grill-with-docs`, `/to-spec`, `/to-tickets`, `tdd`, `diagnosing-bugs`,
`domain-modeling` and the rest) come from the Cockpit-owned pack in
`~/Developer/proj/cockpit/agents/shared/skills/`, which carries its own Linear and Black Box
rules. This repo adds only what is specific to it.

### Issue tracker

Linear, project `Black Box` in team `Nathanmauro` (`NAT-<n>`); the pack resolves it from the repo
name. Specs are `Spec: <name>` issues with sub-issue tickets. Roles are statuses, not labels.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root, created lazily. See
`docs/agents/domain.md`.

## Docs routing

When Nathan says "update my docs", default to the Obsidian vault at `~/Notes/obsidian`. Still update
repo docs automatically when version-controlled behavior, commands, setup, tests, architecture, or
agent instructions change. The vault and in-repo docs are complementary.

Keep public-facing repo docs product-focused and machine-neutral. Keep private machine-operation
notes in Cockpit or Obsidian unless this repo owns the behavior.

## Commit style

Use human-readable changelog commit subjects, not Conventional Commits. Keep subjects concise,
title-case where natural, and descriptive of the shipped change.

Do not add assistant/model co-author trailers or generated-by marketing lines to commits, PRs, or
merge messages.

## Commands and architecture

### Commands

Backend (Java 21, Maven):

```bash
mvn test                                  # full backend suite
mvn test -Dtest=SomeClassTest             # single test class
mvn test -Dtest=SomeClassTest#someMethod  # single test method
mvn -q -DskipTests package                # build the jar
mvn spring-boot:run                       # run on http://localhost:8766
```

Frontend (SolidJS + Vite, in `frontend/`):

```bash
npm test                                  # vitest suite
npx vitest run src/path/to/file.test.tsx  # single test file
npm run lint                              # eslint (lint:fix applies safe autofixes)
npm run format                            # prettier --write (format:check verifies only)
npm run check                             # lint + format:check + tsc --noEmit
npm run build                             # tsc --noEmit + vite build → ../src/main/resources/static
npm run e2e                               # Playwright against a packaged jar on an isolated temp DB
```

Run `npm run format` on frontend files you touch; CI and `scripts/verify.sh` reject lint errors and
unformatted files. Rules and exemptions are explained in `docs/frontend-standards.md`.

`npm run build` emits into `src/main/resources/static/`, which is committed — rebuild and commit it
when frontend changes ship. The E2E suite never touches port 8766 or the production database.

macOS companion shell (Swift, in `companion/macos/`):

```bash
swift build
swift test
swift run BlackBoxCompanion --self-test /tmp/companion.png   # load /companion in the real panel, write a PNG
```

Verification and smoke:

```bash
curl -fsS http://localhost:8766/api/status | jq
scripts/test-agent-hook.sh                # hook bridge smoke test
./scripts/demo.sh                         # isolated decision → handoff → recall proof
./scripts/quickstart.sh                   # build + isolated demo DB + seeded story + UI
```

Local service caution: never package over a JAR used by a running service. Build in an isolated
checkout and use the verified prebuilt deployment path in `docs/operations.md`. The retired runner
CLI and deployment scripts are absent; leave any machine-local runner state untouched unless a
separate operator request authorizes recovery or cleanup.

### Architecture

Feature-first modular monolith (Spring Boot + Spring Modulith) under `dev.nathan.sbaagentic`, with
the SolidJS UI served as static assets from the same process. The full contract lives in
`docs/architecture.md`; package rules in `docs/architecture/package-conventions.md` are enforced by
ArchUnit tests.

Modules (first package segment = owning capability):

- `recording` — canonical session/event capture, redaction, and the relational write boundary
- `memory` — recall, search/facets, memory embeddings, optional Elasticsearch projection
- `lineage` — session links, hook-derived subagent relationships, child counts, and session DAGs
- `project` — logical project identity, aliases, catalog, timelines, secure open-in-editor
- `summary` — session finalization and summary backends (external Codex wrapper by default;
  `SBA_SUMMARY_BACKEND=local` for an OpenAI-compatible local server)
- `judgment` — optional cortex beat folding and typed Orbit judgments
- `ask`, `query`, `platform` (SSE hub, CLI, MCP wiring)

Each module keeps a hexagonal internal layout: `internal/domain`, `internal/application` (+`port`),
`internal/adapter/in/{web,mcp,cli}`, `internal/adapter/out/{sqlite,http,process,...}`. Key rules:

- A module may import another module's root API, never its `internal` packages. (Package conventions
  describe an optional `spi/` layout; no `spi/` package exists in the current tree.)
- Controllers call application use cases or a module facade, never repositories; application code
  never depends on web/MCP/JDBC/process implementations directly.
- Captures commit **before** optional fan-out (Elasticsearch indexing, SSE broadcast, discovery,
  summaries). SQLite is the local default; the optional PostgreSQL profile owns a separate
  shared database. Optional indexes are rebuildable. Do not infer history synchronization or safe
  multiple API replicas from PostgreSQL support; see `docs/postgres-backend.md`.
- No global `controller`/`service`/`util`/`common` buckets; tests mirror production packages.

Wire surfaces: MCP over Streamable HTTP at `/mcp` (historical server id `sba-agentic`), REST for
capture/recall/search/projects and session lineage. SSE at `/api/stream` replays durable
`event.appended` notifications; other event types remain transient refresh hints (see
`docs/durable-stream-recovery.md`). Task/spec endpoints and task tools are retired; cached MCP clients must reload their
tool inventory. Opt-in capture/recall hooks live under `scripts/hooks/`. The `/companion` route
(see `docs/companion.md`) is a chrome-less ambient view over the same stream and query surfaces.

Configuration defaults live in `src/main/resources/application.yml`, overridden by `SBA_*` env vars
(see `docs/operations.md`). Server binds to `127.0.0.1:8766`; optional authentication is disabled by
default. Enable it and HTTPS for network deployment; see `docs/authentication.md`.

### Constraints worth repeating

- Keep public docs honest: semantic recall surfaces structured intent events only; the full event
  corpus is not semantically indexed. Do not document roadmap behavior as shipped.
- Never commit local machine state: `sba-agentic.db*`, `.codex`/`.claude` configs, hook payload
  dumps, credentials, absolute workstation paths (unless clearly example values).
- Commit style: human-readable title-case subjects, not Conventional Commits, and no AI co-author
  or generated-by trailers.
