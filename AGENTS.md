# Black Box - agent guide

Black Box is the public/product name for this repo. The repo directory and Maven artifact are still
`sba-agentic`.

Black Box is a local-first, writable/queryable memory bus for coding agents. Agents commit
structured intent through decisions, handoffs, and observations, then recall that context later
through MCP, HTTP, CLI, or the local web surface. Treat SQLite as the source of truth. Treat
Elasticsearch and local AI summaries as optional supporting systems.

## Response style

Default to normal prose: concise, direct, professional, and complete.

## Working here

- Treat this as a product repo, not the Cockpit private control-plane repo.
- Keep public docs honest: do not claim semantic/vector search, streaming UI behavior, or other
  roadmap items until they exist and are verified.
- Avoid committing private machine state: local databases, `.codex` or `.claude` configs, IDE files,
  hook payload dumps, credentials, env files, and absolute workstation paths unless clearly marked as
  examples.
- Keep storage local-first: SQLite is the source of truth, and Elasticsearch remains an optional
  local secondary index. Session summaries are owned by Black Box and currently default to the
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
   - For substantial work, write or update a plan under `docs/superpowers/plans/`.
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
   - Concrete actions belong in Todoist, not in Black Box.
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
- `Parked tangent`: use when a related side path would derail the current task but should be
  findable later.

Do not put secrets, raw env files, large logs, full duplicate docs, or ordinary Todoist tasks into
Black Box.

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
