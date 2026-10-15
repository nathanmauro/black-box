# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

- Shutdown no longer waits for stream clients. An open `/api/stream` subscription or MCP listening
  stream (`GET /mcp`) used to hold Spring Boot's graceful shutdown for its full 30-second phase
  timeout, so a launchd restart, which sends SIGKILL after 5 seconds, ended in a kill that dropped
  queued summaries and post-commit fan-out such as Elasticsearch indexing. Both kinds of stream now
  end cleanly as shutdown begins (an MCP listening stream resumed with `Last-Event-ID`, which the
  MCP SDK cannot replay, still waits for the 2-second cap); an `/api/stream` request that arrives
  during shutdown ends after its opening frames, and new MCP requests get 503. Clients reconnect
  with `Last-Event-ID` and resume after their last event. Graceful shutdown then waits only for
  in-flight requests, and each shutdown phase is capped at 2 seconds
  (`spring.lifecycle.timeout-per-shutdown-phase`), before browsers reconnect. Background summaries
  get 1 second to finish and judgments 0.5 seconds; unfinished summaries are named in a warning so
  `summarize-missing` can backfill them. A summary command that is interrupted or times out now also
  stops the programs it started, so a summarizer's agent CLI no longer outlives the server. With
  both kinds of stream attached and a summary running, SIGTERM to exit took 32.1 s before this
  change and about 1.1 s after.
- The capture hook no longer silently drops large events. It passed each payload to `jq` as process
  arguments, about three copies for a tool event, and argv shares `ARG_MAX` (1 MiB on macOS) with the
  environment. On macOS, tool events from about 350 KB, and prompts or non-JSON input from about
  530 KB, failed with `Argument list too long`, and nothing was recorded; Linux refuses any single
  argument over 128 KiB. The hook now streams payload and text to `jq` on stdin and sends the whole
  event; the recorder applies its own text limits.
  Only a payload or text over 20,000,000 bytes is clipped first, to the recorder's 50,000-unit
  scalar budget, because the recorder rejects a JSON string over 20,000,000 UTF-16 units. With jq
  1.6 or later, smaller events are sent byte for byte as before.
- The SQLite snapshot in `scripts/storage/blackbox_backup.py` works while the service is running.
  It copied 256 pages per step, and every commit by another connection restarted the copy from the
  first page, so against the live service it never finished and failed with
  `sqlite_backup_timeout` after five minutes. It now copies the whole database in one step under
  one read snapshot. In WAL mode the service keeps committing meanwhile, and its `-wal` file grows
  until the copy ends. The five-minute limit now applies only while waiting for a busy or locked
  source; see [docs/database-recovery.md](docs/database-recovery.md).
- Saved meld and braid reads no longer lock up the connection pool. Each read resolved its project
  and loaded its source sessions with a query per row while the row query still held its pooled
  connection, so four concurrent braid lists could take all four SQLite connections and wait on
  each other; requests, captures included, then stalled for 30 seconds or more, and most failed
  with a 500. Meld by ID, the unassigned braid list, project melds, timeline and graph, and
  `findBraids` now map their rows first and load sources in batched queries, one connection at a
  time, on both backends.
- SQLite read-write transactions begin `IMMEDIATE`. A save that reads before it writes, such as a
  saved braid or meld, now waits up to the 5-second `busy_timeout` for a concurrent capture or
  summary instead of failing at once with `SQLITE_BUSY`. Read-only transactions stay deferred, and
  PostgreSQL is unchanged. Because a read-write transaction now holds the writer lock from its
  begin, [docs/architecture.md](docs/architecture.md) lists what code inside one must not do.
- Prompts from the record: `GET /api/continuations` builds up to three deterministic starting
  prompts from one project's recorded handoffs and current decisions (exact project and alias
  scope, replaced decisions excluded, trajectory frontier, stale and future-dated labels, explicit
  Evidence caveats, fenced captured text, 6,000-character budget). Recall gains a **Prompts from
  the record** panel with Inspect and Copy prompt. No model is involved and nothing is stored.
- Optional continuation restatement, off by default: `POST /api/continuations/synthesize` asks a
  loopback local model (`SBA_CONTINUATION_SYNTHESIS_ENABLED`) to restate one prompt with `[E#]`
  evidence handles and redacted, fenced input, validates the whole reply, and returns
  `synthesized: null` with a fixed reason on any failure. The deterministic prompt never changes.
- Stream query composition: completion follows the caret, including negated, quoted and
  comma-listed tokens. It offers recorded values for facets, labelled with where they came from
  (counts appear only when they describe the query being composed), and grammar values for time
  operators. Tab accepts and keeps a value typed in full, field names complete, and session
  suggestions show titles. Submitted queries explain what was searched as text, and empty results
  offer one-click fixes. The "Prompts today" view now includes `is:all`. With a pinned project,
  the URL normalization no longer re-serializes the query: it only removes positive
  `project:`/`cwd:` includes, with a note naming each one it removed, and it replaces the history
  entry, so Back is not trapped. An unclosed quote no longer keeps the URL changing or swallows
  the project scope, and a trailing bare `NOT` no longer negates that scope.
- Optional macOS menu-bar quick recall helper (`macos/BlackBoxRecall`) with its build, install and
  hermetic test scripts and an opt-in Trace with Jev action; see
  [docs/macos-quick-recall.md](docs/macos-quick-recall.md).
- Frontend lint and formatting: ESLint 10 (typescript-eslint, eslint-plugin-solid) and Prettier 3
  with `npm run lint`, `format`, `format:check`, and `check`; enforced in CI and `scripts/verify.sh`,
  with an opt-in pre-commit hook and a one-time mechanical reformat of `frontend/`.

## [0.2.0] - 2026-09-20

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
  documented single-owner managed AWS prototype (retired; no hosted service is included).
- Recall telemetry that separates semantic attempted, completed, contributed, and returned.
- Recoverable, opt-in hook outbox with replay-safe capture; unfinished runner work is preserved,
  and local deployment uses immutable artifacts with readiness checks and binary recovery.
- Recall windows of 90 and 180 days, with accessible contextual help and practical search examples.
- Offline lifecycle rehearsal and reproducible memory evaluation tools with published limits;
  the measurements do not establish a general productivity benefit.
- Opt-in Spotless/Palantir Java formatting setup; existing application sources are not reformatted.
- Docs and tooling: README rewrite around the capture, handoff, recall loop; new agent integration,
  operations, runner, evolution, and futures guides; `scripts/verify.sh` with an optional pre-push
  hook; CI on pull requests, main pushes, and manual dispatch. Release artifacts include SHA-256
  checksums and version-matched release notes.

See [release notes and upgrade guidance](docs/releases/v0.2.0.md). This remains a pre-1.0 release;
API and database compatibility are not covered by a long-term stability promise.

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
