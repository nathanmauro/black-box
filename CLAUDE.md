# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read `AGENTS.md` first — it defines the working rules for this repo (methodical workflow, Black Box
event discipline, docs routing, commit style). This file adds commands and architecture only.

Black Box is the product name; the repo directory and Maven artifact are still `sba-agentic`. It is a
local-first memory bus for coding agents: agents commit structured
Decisions/Handoffs/Observations and recall them later via MCP, REST, CLI, or the local web UI. The
server records intent and session relationships; selected execution belongs in Linear.

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

## Architecture

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

## Constraints worth repeating

- Keep public docs honest: semantic recall surfaces structured intent events only; the full event
  corpus is not semantically indexed. Do not document roadmap behavior as shipped.
- Never commit local machine state: `sba-agentic.db*`, `.codex`/`.claude` configs, hook payload
  dumps, credentials, absolute workstation paths (unless clearly example values).
- Commit style: human-readable title-case subjects, not Conventional Commits, and no AI co-author
  or generated-by trailers.
