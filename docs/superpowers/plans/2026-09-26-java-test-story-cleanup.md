# Java test story and coverage ownership

## Intent

Make the Java tests a readable description of Black Box's capabilities, and remove
repeated proofs where they protect the same behavior at the same boundary. Keep
independent HTTP/MCP contracts, persistence guarantees, concurrency cases, backend
compatibility, and failure paths even when their setup resembles another test.

## Completion contract

- Audit all Java test areas and identify the owner of each consolidated assertion.
- Organize existing capability tests into named stories; do not add a second story
  suite that merely repeats existing scenarios.
- Consolidate only evidenced overlap, preserving meaningful inputs and assertions.
- Document a reading route and the responsibility of lower-level suites.
- Run the baseline, focused changed suites, the full Java suite, and diff checks.
- Keep production code, frontend tests, live services, and external providers out
  of this cleanup. Test fixtures use isolated temporary state.

## Work

1. Baseline and inventory: 124 Java source files, 584 test-method annotations
   (parameterized invocations are counted separately by the test runner).
2. Audit capture/recall, workflow/runner, and platform/project/backend areas.
3. Consolidate duplicated contract checks and scenario setup; establish readable
   capability chapters and an explicit coverage map.
4. Review the removed assertions against their retained owners and verify.

## Verification and outcomes

Baseline: `mvn test` passed, 673 tests, zero failures/errors, 20 skips.
The skips are the 16 PostgreSQL contracts, two live-model evaluations, and two
environment-dependent cases. No optional live integration was enabled.

The working branch was already `2026-09-24-iterate` at `719f352` when work began.
It predates the frontend standards now on `main`. A comparison of branch tips
therefore shows missing EditorConfig/Prettier/ESLint files; those are not deletions
from this cleanup. The formatter configuration and production sources stay intact.

## Coverage ownership after cleanup

| Consolidation | Surviving proof |
| --- | --- |
| Decision capture plus separate timeline setup | `ContextLoopTest.Decisions` verifies recall and the same stored event. |
| Default handoff recall plus separate ID lookup setup | `ContextLoopTest.Handoffs` verifies both access paths in one journey. |
| Projection caps and blank-path normalization | `StructuredCaptureServiceTest` retains both input lists as named cases. |
| Duplicate vector-store outage scenario | `RecallTelemetryTest` verifies lexical identity, mode, null score, and failure telemetry. |
| Chunk/split/batch and confidence variants | Parameterized cases retain the original boundaries and payloads. |
| External-summary stdin and shell success setup | One pipeline proves full multiline input, shell interpretation, and stdout. |
| Shared recall fixtures repeated by REST and MCP snapshots | `WireContractFixtureTest` owns their shape, types, and nullable score. |
| Direct MCP task lifecycle replay | REST and actual MCP callback journeys prove shared state and serialization; unique REST status transitions and errors remain. |
| Tool registry/schema checks inside the task suite | `McpContractSnapshotTest` owns discovery; empty callback results stay in the task journey. |
| Separate session-link task-ID and start/stop setups | Lineage journeys retain task provenance, both directions, exact deduplication, and stop-only recovery. |
| Repeated engine/rate-limit/SPA input bodies | Named parameterized cases retain all original input variants. |
| Saved-meld listing and timeline setups | One save/list/timeline journey retains both source-session links and timeline ordering. |

The broad capture, HTTP browsing, and task API suites now use JUnit capability
chapters. `docs/java-tests.md` supplies the full-suite reading route and explains
why backend, transport, migration, concurrency, and recovery tests remain separate.
Contract-matrix references were updated to the surviving task story methods.

The audit covered recording/event/session, memory/search/query, summary/Ask,
workflow/runner, project, platform/security/SSE, judgment, architecture, contracts,
and optional PostgreSQL tests. Independent review of the capture/workflow changes
found no material lost coverage; the moved HTTP bodies were checked against the
originals. The cleanup changes 17 Java files and reduces test-method annotations
from 584 to 562. Parameter invocations remain independently reported.

All focused changed suites passed. The final `mvn test` run passed: 678 tests,
zero failures/errors, 20 skips (the same optional/environment cases as baseline).
The higher invocation count comes from reporting previously bundled inputs as
separate parameterized cases. Unchanged suites retain their original discovered
test inventories. Contract-reference checks and `git diff --check` passed.

The changes are local and uncommitted. No merge, push, deployment, live provider
evaluation, service restart, or formatter configuration change was performed.

## Port onto main (2026-10-15)

The cleanup above was never committed on its original branch. On 2026-10-15 its intent was
re-applied to `main` at `a032c8f1`, where most of the touched suites had changed. Every test on
`main` was kept, moved into a chapter, renamed, or carried as a named parameterized case. The only
removals are duplicates from the coverage table whose surviving owners still exist.

Carried over: the `ContextLoopTest` and `AgenticControllerTest` chapters, recall fixture ownership
in `WireContractFixtureTest`, the vector-outage proof in `RecallTelemetryTest`, the saved-meld
timeline merge, and the parameterized projection, decision-confidence, chunking, external-summary,
and SPA route cases. Newer upstream evidence, idea, subagent-transcript, and human-only tests joined
the chapters; evidence and ideas form a new `ContextLoopTest` chapter. `SpaForwardingTest` keeps the
retired `/board` route as its own test.

Dropped as obsolete because `main` retired the board runner and task workflow: the engine and
rate-limit parameterizations, the `TaskApiContractTest` restructuring, and the `/api/specs` and
`/api/tasks` contract-matrix rows. The session-link task-ID merge was also dropped. `main` ignores
task associations, so that compatibility rule keeps its own test. The `SubagentLinkListenerTest`
start/stop consolidation was not carried over; that suite now lives under `lineage/`, unchanged.
`docs/java-tests.md` now routes through current suites only.

Before and after the port, `mvn test` passed 1,086 tests with zero failures or errors and 74 skips:
70 PostgreSQL contracts, two live-model evaluations, and two environment-dependent cases. Declared
test methods fell from 741 to 727; all 27 removed or renamed test cases have named successors.
The documented story commands, scoped Spotless checks, and `git diff --check` passed.
