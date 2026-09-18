# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

Everything since 0.1.0, grouped by the era it landed in (see [docs/evolution.md](docs/evolution.md)).

- SolidJS UI rewrite with a global Activity stream as the front door: keyset-paged feed, faceted
  filters with query-scoped counts, SQLite FTS5 free-text search, a shared query grammar, saved views.
- Coordination: frozen specs, exact-lane queues, atomic claims, validated lifecycle, and completion
  Handoffs; REST mirrors the seven coordination operations.
- Optional board-driven runner process with FULL_AUTO and SDLC modes; fail-closed on unknown repos,
  red checks, missing approvals, or missing credentials.
- Feature-first modular monolith with ArchUnit and Spring Modulith enforced boundaries, proved by
  frozen REST, MCP, and SQLite contract fixtures; subagent lineage in Browse.
- Semantic recall over structured intent (Decisions, Handoffs, Observations) with optional sqlite-vec
  acceleration, true cosine scores, a measured 0.61 relevance floor, and lexical fallback.
- Logical Projects with a trajectory graph: burst epochs, ranked next steps from captured records,
  ghost Projections via `captureProjection`, rejected alternatives as stubs.
- Bounded `SessionStart` recall hook for Claude Code and Codex; complete session transcripts.
- Catalog-bound open-in-editor and reveal-in-Finder actions with server-side path validation.
- Optional PostgreSQL profile, optional authentication boundary, evidence-to-Linear prototype, and a
  documented single-owner managed AWS prototype.
- Recall telemetry that separates semantic attempted, completed, contributed, and returned.
- Docs and tooling: README rewrite around the capture, handoff, recall loop; new agent integration,
  operations, runner, evolution, and futures guides; `scripts/verify.sh` with an optional pre-push
  hook; CI reduced to manual dispatch and pushes to main.

## [0.1.0] - 2026-06-10

- Structured decision and handoff capture with recall over MCP (Streamable HTTP) and REST.
- Raw session event recording via opt-in hook bridge for Claude Code and Codex.
- SQLite source of truth with WAL.
- Session summaries via local OpenAI-compatible model by default, or external CLI backend when opted in.
- Secret redaction on ingest, on by default.
- Web UI with Sessions and Recall workspaces; ASK is shown only when its optional index is configured.
- Optional Elasticsearch secondary index.
- Obsidian and markdown summary export, opt-in.
- CLI commands for doctor, ingest, search, sessions, and summarize.
- Localhost-only bind by default.
- CI and tagged releases.
