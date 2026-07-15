# Logical Project Workspace Implementation Plan

**Goal:** Finish Black Box project integration by grouping verified worktree scopes under one logical project identity and replacing the parked Projects route with a useful project workspace.

**Product boundary:** Projects remain a local read model over recorded working directories. This slice does not create a general project-management system, infer Board tasks, rewrite historical events, scope Ask, or automatically merge ambiguous paths.

## Identity and safety contract

- SQLite remains authoritative.
- `agent_sessions.cwd`, raw events, task/spec project scopes, and historical meld rows are never rewritten.
- Exact queue filtering and the existing `project_exact:` search facet remain exact.
- A new alias layer groups scopes only for project catalog, session, storyline, and meld reads.
- Old encoded worktree project URLs continue to resolve.
- Only verified linked Git worktrees and `.claude/worktrees` / `.worktrees` paths with owner Git metadata may be discovered automatically.
- Basename-only Codex/Grok worktree guesses and other ambiguous contexts remain separate until
  explicitly curated. `/` and `__no_project__` are protected and can never be aliased.
- Alias writes are cycle-safe and reversible. Automatic rows retain their direct structural owner,
  so undoing a manual merge restores the original worktree group instead of losing it.

## Implementation

1. Add an idempotent `project_aliases` table, alias repository/service, verified worktree discovery, and safe REST mutation endpoints.
2. Aggregate `GET /api/projects` over resolved identities and expose constituent scope provenance without removing existing fields.
3. Make project sessions, timelines, saved melds, and meld membership validation alias-aware while preserving legacy encoded scope URLs.
4. Add an internal grouped-project query facet for Activity Stream/Find; retain raw exact filtering separately.
5. Update shared frontend project helpers and picker search to understand primary and variant scopes.
6. Unpark `/projects` as a searchable project flight recorder with grouped metrics, scope provenance, recent sessions, newest storyline evidence, saved synthesis, explicit alias curation, and pivots to Activity, Board, and Recall.
7. Normalize legacy Activity project URLs to the primary project key and fail closed when catalog resolution fails.
8. Keep Board task queries exact, but display grouped project context and provide a project-workspace pivot.
9. Restore Projects to utility navigation and the command palette; update README and project design status.

## Verification

- Fresh and existing SQLite schema initialization.
- Grouped counts and scope provenance for canonical plus aliased worktree paths.
- Legacy alias URLs for sessions/timeline/melds.
- Alias conflict, cycle, delete, and protected-context behavior.
- Verified worktree discovery without basename-only merging.
- Raw `project_exact:` versus grouped Activity filtering.
- Project workspace loading, failure, invalid key, empty timeline, alias merge/undo, newest-storyline, saved-meld, navigation, and accessibility behavior.
- Activity legacy URL normalization and catalog-failure fail-closed behavior.
- Board exact task-filter regression and grouped display mapping.
- Full frontend tests/build, `mvn -B verify`, `git diff --check`, isolated SQLite/browser exercise, and live post-deploy verification.

## Deferred

- First-class editable project profiles, GitHub/Todoist/Obsidian ingestion, automatic basename merges, event-level multi-project attribution, Ask scoping, meld creation UI, and task inference.
