# Task-list pagination implementation plan

## Scope

Add backward-compatible REST paging and status exclusion to `GET /api/tasks`, push both into the
SQLite query, and make runner task scans aggregate every server page. Preserve the existing MCP
tool contract, frontend behavior, and Round 1 crash-recovery adoption logic.

## Safety contract

- Existing three-argument `TaskQuery` callers remain source-compatible and unpaged.
- REST keeps its default limit of 100 and its 1–250 clamp; negative offsets clamp to zero.
- Runner scans stop on a short page and fail rather than silently returning a partial result if the
  safety-page ceiling is reached.
- Null-status runner lane scans exclude cancelled tasks at the server while existing client-side
  guards remain intact.
- No package build or live launchd service change is required.

## Steps

1. Extend repository and REST contract tests for exclusions, stable pages, offsets, repeated and
   comma-separated exclusions, and omitted-parameter compatibility.
2. Extend the fake-server client tests for aggregation beyond 250 rows and cancelled exclusion.
3. Extend `TaskQuery`, add deterministic `LIMIT`/`OFFSET` SQL, and remove the controller's post-query
   limit.
4. Centralize runner pagination for both `listTasks` overloads with a bounded page loop.
5. Update architecture and fleet docs, then run targeted tests, `mvn -q test`, and
   `git diff --check` before committing exact paths.
