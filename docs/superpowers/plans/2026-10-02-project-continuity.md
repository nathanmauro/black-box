# Project continuity

Status: implemented and locally verified. Owner authorized autonomous execution and subsequent PR creation/merge on 2026-10-02.

## Outcome

Choose a logical project, ask a separate question, inspect grounded structured evidence, and copy a bounded briefing to resume work. Explicit decision replacements preserve historical evidence and keep overturned choices out of normal recall. Grounded suggestions point to recorded captures. Cloud preparation remains locally testable; provisioning, credentials, live migration and deployment are separate release actions.

## Working contract

- Preserve SQLite default, aliases, existing scope links, HTTP/MCP compatibility, bounded outputs, optional semantic fallback and exact-source navigation.
- New recall parameters: `project` (canonical project identifier/path), `query` (topic), `includeSuperseded` (default false). Existing `scope`/`repoOrTopic` calls retain behavior. New fields are used when project/query provided; reject ambiguous legacy scope combined with new parameters.
- Both lexical and semantic results must stay inside the selected logical project, including registered aliases. Only query text is embedded. Blank query yields recent project intent. Unknown projects return no results, never global fallback.
- Decision capture accepts optional `supersedes` (one prior Decision event ID). Require a nonblank rationale explaining the replacement, a real Decision target in the same logical project, and a target not already superseded. New event and append-only relation commit atomically. Preserve old event bytes. Reject invalid requests without partial writes. No inferred supersession.
- Recall items add nullable `supersedesEventId` and `supersededByEventId`. Normal recall excludes replaced decisions even when replacement is outside the query/window. History is explicit with `includeSuperseded`.
- UI: project picker, full-width question, compact secondary filters, source-backed suggestions, resume action, bounded copy-context action, replacement form, history markers. Preserve `scope` deep links and menu-bar `run=1` auto-run compatibility.
- Briefings are compiled from retrieved captures with timestamps and source references. Label latest recorded context and recorded open questions; no claim that recency establishes truth or that an old open loop remains actionable.
- Suggestions retrieve actual captures after a debounce and honor project/time/kind filters; do not generate suggestions or call a new model. Race-safe results, keyboard operation, honest empty/error/degraded states.

## Milestones and ownership

1. Backend: memory/recording/project contracts, schema, REST/MCP, focused integration and concurrency tests, API documentation. Isolated backend worktree. Coordinator integrates and commits.
2. Frontend: Recall/Projects flow, query suggestions, export and explicit replacement form, unit and Playwright journeys. Isolated UI worktree. Coordinator integrates and commits.
3. Cloud readiness: reconcile later consumer-trace spec, implement only compatible locally verifiable transport readiness; document migration blockers and exact next actions. No cloud spend or production changes.
4. Integrated verification: complete backend/frontend suites, formatting, generated assets/contracts, real HTTP/MCP and browser flows on a disposable database, fresh review, local commit and continuity handoff.

## Acceptance

- Identical topics in two projects never leak across selection; aliases work; lexical-only and semantic paths both honor project scope.
- Existing scope-only requests and source links work; optional services can be absent.
- Replacement persists atomically, original evidence stays readable, history identifies the relation, invalid and concurrent replacements fail safely.
- Desktop and narrow viewport: choose project, type question, choose actual evidence, inspect source, copy bounded briefing, record replacement, inspect history.
- Source links, timestamps, truncation and coverage limits survive export; no unsupported generated claims.
- Cloud transport tests use fixtures/fake endpoints; no production configuration, database or external service changes.
- Full relevant checks plus `git diff --check`; record actual outcomes below.

## Execution notes

- Base is c523a0c in an isolated checkout. Other unintegrated work includes NAT-243 board removal, later cloud-spec amendments, and the menu-bar helper. Do not merge/publish unrelated branches or modify those checkouts. Preserve the relevant menu-bar deep-link behavior in the new flow.
- JetBrains is open on another project; native Maven validation applies here.
- Previous continuation evaluation has a negative result. This work establishes functional behavior, not productivity improvement.

### Baseline and transport evidence

- Baseline packaged API was run against an isolated fixture database. A request for `project=/fixture/alpha&query=auth` returned both Alpha and Beta: separate parameters were ignored. The disposable server was stopped afterward.
- Transport now requires exact `SBA_CAPTURE_HTTPS_ORIGIN` opt-in and a normalized-origin Keychain account. Existing loopback defaults and queue schema are preserved. Credential lookup is capped at one second within the shared deadline and occurs after durable acceptance.
- Nine focused HTTPS checks passed: actual local TLS, origin binding, credential-after-commit, status without credential access, invalid credentials, untrusted certificate/hostname mismatch, redirects, rotation and identical retry payloads, and real subprocess timeout/reaping. Keychain responses are fixtures; no real Keychain or cloud endpoint was used.
- Disposable PostgreSQL 16 is available for the backend contract suite; no existing database/service configuration was changed.
- Final transport regression: all 41 outbox tests and legacy hook checks passed. Local Nathan-authored commit `bc321a1` contains the transport and its documentation. Independent source review found no blocking transport issue. Real Keychain and a deployed remote endpoint remain untested.

### Integrated acceptance

- Backend: 763 tests, zero failures/errors, four environment-dependent skips. This includes all 19 real PostgreSQL contracts and SQLite concurrency/rollback tests, plus real REST/MCP capture and recall. Optional live model/Elasticsearch and platform-only checks are not established by this run.
- Frontend: 733 unit tests passed. TypeScript, Prettier and ESLint passed (zero errors; 83 existing/style warnings). Built assets were regenerated from source.
- All 32 Playwright journeys passed against the packaged integrated application and a disposable database. The new desktop and 390px journeys cover exact-project suggestions, keyboard selection, source navigation, project resume, real clipboard export, replacement and cross-session history links. Desktop/mobile-width screenshots were inspected; physical-phone use was not tested.
- The browser harness verified the existing port-8766 listener stayed unchanged and removed its isolated fixture database. It could not discover the production database, so no production row-count claim is made.
- Independent backend/UI review found no outstanding issues. Previously identified cross-session navigation, saved-write/failed-refresh state and empty legacy scope normalization issues were fixed before final verification. Documentation review corrected the distinction between existing remote PostgreSQL hosting and the proposed broader cloud architecture.
- `git diff --check` passed. No live service was restarted, no credentials or cloud resources were provisioned, and no historical database was migrated. The next cloud slice should verify the existing authenticated PostgreSQL consumer contract before expanding its architecture.
