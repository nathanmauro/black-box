# Cross-Environment Capture Contract Plan

Status: definitional slice written 2026-09-24 for Linear NAT-201; no runtime behavior changed.

Scope:

- Define the origin vocabulary, minimum capture schema, event-kind mapping, handoff template,
  selective-capture instruction block, scope classes, and Obsidian promotion rule in
  [docs/cross-environment-capture.md](../../cross-environment-capture.md).
- Ground every "shipped" statement in current code and docs; label everything else roadmap.
- Repoint `AGENTS.md` task routing from Todoist to Linear and link the contract from the event
  discipline section and the README documentation list.

Decisions:

- `result` and `blocker` are written as `Observation` with `captureKind` metadata rather than new
  event types. Rationale: no schema migration for a definitional slice; `kind:` search and the
  metadata key keep them findable. Rejected for now: first-class `Result`/`Blocker` event types.
  Revisit when a cloud writer exists and needs typed queries.
- `origin` is a metadata key with a fixed vocabulary, separate from the gateway's existing
  `declaredVoiceOrigin`. Rationale: the voice keys already ship and carry different semantics
  (surface, not environment); merging them would change verified gateway behavior.
- Scope classes are applied by surface (which tools a client is given) until the server has token
  scopes. This is stated as a limit, not as enforcement.

Not done (open acceptance criteria):

- Per-client credentials and server-side scope enforcement.
- Any reachable endpoint for Codex Cloud or Claude cloud agents.
- Structured handoff fields, projection, and `claude_voice` through the gateway.
- Dedupe on the structured capture endpoints.
- The cloud → Black Box → local handoff demonstration.

Verification: documentation only; `git diff --check` and the README-reading contract tests.
