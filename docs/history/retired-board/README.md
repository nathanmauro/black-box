# Retired task board and runner

These specifications and implementation plans are historical evidence. NAT-243 removed the
unused board, task/spec queue, and runner after extracting session lineage into its own module.
Do not follow these documents as current setup or implementation instructions.

- `specs/` preserves the task-queue, FULL_AUTO runner, and SDLC proposals.
- `plans/` preserves their implementation and recovery records.
- Session lineage, parent/child navigation, session DAGs, Orbit, and normal Handoff capture remain.

See [retirement and upgrade notes](../../board-retirement.md) and the current
[architecture](../../architecture.md) for supported behavior.
