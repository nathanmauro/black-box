# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read `AGENTS.md` first — it defines the working rules for this repo (methodical workflow, Black Box
event discipline, docs routing, commit style). This file adds commands and architecture only.

Black Box is the product name; the repo directory and Maven artifact are still `sba-agentic`. It is a
local-first memory bus and coordination ledger for coding agents: agents commit structured
Decisions/Handoffs/Observations and recall them later via MCP, REST, CLI, or the local web UI. The
server never launches workers or executes task commands — it records intent and arbitrates ownership.

## Commands

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

Verification and smoke:

```bash
curl -fsS http://localhost:8766/api/status | jq
scripts/test-agent-hook.sh                # hook bridge smoke test
./scripts/demo.sh                         # isolated decision → handoff → recall proof
./scripts/quickstart.sh                   # build + isolated demo DB + seeded story + UI
```

Local service caution: the launchd service (`com.nathan.sba-agentic`, deployed via
`scripts/deploy-local.sh`) runs the jar from `target/`. Any `mvn package` (including the E2E suite's
packaging step) overwrites that jar and degrades the live service until it is restarted —
`launchctl kickstart -k gui/$UID/com.nathan.sba-agentic` after builds. `scripts/deploy-runner-local.sh`
deploys the board runner service; starting it launches autonomous orchestration per
`~/.blackbox/runner.json`, so do not start it casually.

## Architecture

Feature-first modular monolith (Spring Boot + Spring Modulith) under `dev.nathan.sbaagentic`, with
the SolidJS UI served as static assets from the same process. The full contract lives in
`docs/architecture.md`; package rules in `docs/architecture/package-conventions.md` are enforced by
ArchUnit tests.

Modules (first package segment = owning capability):

- `recording` — canonical session/event capture, redaction, and the relational write boundary
- `memory` — recall, search/facets, memory embeddings, optional Elasticsearch projection
- `workflow` — spec/task coordination: frozen specs, exact-lane queues, atomic claims, lifecycle,
  completion Handoffs
- `project` — logical project identity, aliases, catalog, timelines, secure open-in-editor
- `summary` — session finalization and summary backends (external Codex wrapper by default;
  `SBA_SUMMARY_BACKEND=local` for an OpenAI-compatible local server)
- `ask`, `platform` (SSE hub, CLI, MCP wiring), `runner` (external board-runner process,
  `java -jar sba-agentic.jar runner` — an ordinary REST client, config-gated, fails closed)

Each module keeps a hexagonal internal layout: `internal/domain`, `internal/application` (+`port`),
`internal/adapter/in/{web,mcp,cli}`, `internal/adapter/out/{sqlite,http,process,...}`. Key rules:

- A module may import another module's root API, never its `internal` packages. (Package conventions
  describe an optional `spi/` layout; no `spi/` package exists in the current tree.)
- Controllers call application use cases or a module facade, never repositories; application code
  never depends on web/MCP/JDBC/process implementations directly.
- Standalone captures commit **before** optional fan-out (Elasticsearch indexing, SSE broadcast,
  discovery, summaries); inside task completion the listeners run within the outer task transaction
  (see the transaction note in `docs/architecture.md`). SQLite is the local default; the optional PostgreSQL profile owns a separate
  shared database. Optional indexes are rebuildable. Do not infer history synchronization or safe
  multiple API replicas from PostgreSQL support; see `docs/postgres-backend.md`.
- No global `controller`/`service`/`util`/`common` buckets; tests mirror production packages.

Wire surfaces: MCP over Streamable HTTP at `/mcp` (spring-ai MCP server; historical server id
`sba-agentic`), a REST API that mirrors the seven coordination operations (REST task listing additionally accepts
`offset` and `excludeStatus`; shared field names,
ISO-8601 timestamps, typed error envelopes), SSE at `/api/stream` as a best-effort wake hint (never
a queue — `claimNextTask`/`listTasks` stay authoritative), and opt-in capture/recall hooks under
`scripts/hooks/`.

Configuration defaults live in `src/main/resources/application.yml`, overridden by `SBA_*` env vars
(see `docs/operations.md`). Server binds to `127.0.0.1:8766`; optional authentication is disabled by
default. Enable it and HTTPS for network deployment; see `docs/authentication.md`.

## Constraints worth repeating

- Keep public docs honest: semantic recall surfaces structured intent events only; the full event
  corpus is not semantically indexed. Do not document roadmap behavior as shipped.
- Never commit local machine state: `sba-agentic.db*`, `.codex`/`.claude` configs, hook payload
  dumps, credentials, absolute workstation paths (unless clearly example values).
- Commit style: human-readable title-case subjects, not Conventional Commits, and no AI co-author
  or generated-by trailers.
