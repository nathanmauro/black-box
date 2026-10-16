# Read Black Box through its Java tests

The tests describe a loop: record what an agent learned, retrieve that evidence,
resume work, and preserve the result. Start with the capability stories below,
then follow the smaller suites when you need the rules behind a behavior.

Paths in this guide are relative to
[`src/test/java/dev/nathan/sbaagentic`](../src/test/java/dev/nathan/sbaagentic).
The named JUnit chapters are navigation, not execution dependencies: each test
sets up its own scenario and must pass on its own.

## The capability stories

| Read in this order | What it demonstrates |
| --- | --- |
| [`context/ContextLoopTest.java`](../src/test/java/dev/nathan/sbaagentic/context/ContextLoopTest.java) | Capture a decision with its rationale, find it by repository or topic, leave a handoff and resume by its ID, recall possible futures alongside the project graph, and recall evidence and ideas when their kinds are requested. |
| [`recording/IdempotentCaptureHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/recording/IdempotentCaptureHttpTest.java) | Retry a capture without duplicating it; reject conflicting reuse and preserve atomicity across concurrency, failure, and restart. |
| [`recording/ConfigSecretRedactionHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/recording/ConfigSecretRedactionHttpTest.java) | Capture an agent displaying or writing a service's launchd configuration, including Claude Code payloads sent through the real capture hook in its default and durable modes (`launchctl print` with quotes, lowercase names and multi-line values, `grep -n` gutters, `plutil -p`, binary property-list bytes, `plutil -replace`, `launchctl setenv`, PlistBuddy, a pasted prompt, a Stop message, Grep context, an Edit patch), and keep that evidence, while the stored event, its full-text index, event reads, the session title and the Elasticsearch document never hold the credential. `ConfigSecretRedactionTest` owns the display forms at every JSON encoding level, raw and inside a tool-result object, the benign code and prose that must survive, and the per-rule near-miss scan bounds. |
| [`summary/SummaryRedactionHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/summary/SummaryRedactionHttpTest.java) | Summarize a session whose stored text predates a redaction rule, and preview a direct meld from it: the external summary command never receives the credential, and the summary and title it answers with are stored redacted. An ordinary session reaches the command as stored, and a meld preview sends every selected session's evidence. |
| [`context/ProjectContinuityTest.java`](../src/test/java/dev/nathan/sbaagentic/context/ProjectContinuityTest.java) | Recall a project and its aliases before ranking, and replace a decision without rewriting its history. Only one concurrent replacement wins; invalid or unscoped targets fail without partial writes. |
| [`web/AgenticControllerTest.java`](../src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java) | Browse sessions and transcripts, including subagent transcripts, filter read surfaces to human turns, inspect projects and timelines, preview and save combined session summaries, export summaries, and inspect Ask dependencies. |
| [`project/ProjectAliasIntegrationTest.java`](../src/test/java/dev/nathan/sbaagentic/project/ProjectAliasIntegrationTest.java) | Treat verified worktrees and manual aliases as one logical project without losing historical URLs or introducing alias cycles. |
| [`project/ProjectlessBraidHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/project/ProjectlessBraidHttpTest.java) | Save a braid of sessions from different projects without choosing a project, keep its provenance across session moves and restarts, and roll back a failed save. |
| [`search/CompactSearchHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/search/CompactSearchHttpTest.java) | Recover evidence within a byte budget, preserve provenance, apply exclusions before limiting candidates, and report incomplete searches. |
| [`memory/internal/application/ContextServiceHybridRecallTest.java`](../src/test/java/dev/nathan/sbaagentic/memory/internal/application/ContextServiceHybridRecallTest.java) | Combine lexical and semantic recall while respecting relevance, kind, age, and repository boundaries. |
| [`memory/internal/adapter/in/web/EvidenceHttpMcpTest.java`](../src/test/java/dev/nathan/sbaagentic/memory/internal/adapter/in/web/EvidenceHttpMcpTest.java) | Attach evidence to every revision of an idea over HTTP and MCP with canonical provenance, and reject invalid evidence without writing an event or session. |
| [`ask/internal/application/AskServiceTest.java`](../src/test/java/dev/nathan/sbaagentic/ask/internal/application/AskServiceTest.java) | Answer from retrieved evidence with stable citations, and explain missing evidence or unavailable synthesis. |
| [`web/SessionLinkApiContractTest.java`](../src/test/java/dev/nathan/sbaagentic/web/SessionLinkApiContractTest.java) | Connect agent sessions before their transcripts arrive, read links in both directions, count children, and project a bounded lineage graph. Obsolete task associations are ignored. |
| [`web/RetiredWorkflowUpgradeApiTest.java`](../src/test/java/dev/nathan/sbaagentic/web/RetiredWorkflowUpgradeApiTest.java) | Upgrade a database created before the board retirement: remove the task tables while keeping completion handoffs recallable and session links readable. |
| [`platform/internal/adapter/in/web/security/AuthenticationHttpTest.java`](../src/test/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/security/AuthenticationHttpTest.java) | Log in, capture, subscribe, and log out with browser sessions and CSRF protection while agent requests remain stateless. |
| [`platform/internal/adapter/in/sse/EventStreamTest.java`](../src/test/java/dev/nathan/sbaagentic/platform/internal/adapter/in/sse/EventStreamTest.java) | Receive appended events over HTTP, replay from a cursor after a disconnect without skipping late or tied captures, and keep an idle stream alive with heartbeats. |
| [`platform/internal/adapter/in/sse/StreamShutdownTest.java`](../src/test/java/dev/nathan/sbaagentic/platform/internal/adapter/in/sse/StreamShutdownTest.java) | Restart while `/api/stream` and MCP listening clients are attached: their streams end as shutdown begins, so it finishes well inside a service manager's 5-second stop timeout, and a reconnect after the restart resumes after the last delivered event. |
| [`judgment/internal/application/JudgmentStandaloneIntegrationTest.java`](../src/test/java/dev/nathan/sbaagentic/judgment/internal/application/JudgmentStandaloneIntegrationTest.java) | Continue capture and recall while an optional judge is blocked or failing; the provider is a test double. |

## Where detailed coverage belongs

Each boundary owns a different question. Similar setup alone is not duplicate
coverage; repeated assertions against the same implementation usually are.

| Area | Responsibility of its focused suites |
| --- | --- |
| `recording`, `event`, `session` | Capture normalization, validation, and identity; redaction; acknowledgement after the canonical commit; canonical time and chronology; human turns; transcripts; lifecycle publication; migrations; SQLite writer-lock transactions; and feed SQL. |
| `query`, `search` | Query grammar and time resolution, search SQL and facets, compact response budgets, backend fallback, and Elasticsearch transport behavior. Feed SQL and search SQL are separate implementations. |
| `memory` | Ranking and relevance rules, recall telemetry and fallback, idea and evidence capture and revisions, embedding/indexing/backfill lifecycle, serialization, and each vector-store implementation. Live model evaluations are separately gated measurements. |
| `summary`, `ask` | Backend selection, subprocess and HTTP contracts, redaction of what a summary backend is sent and of the summary it returns, chunk budgets and ordering, bounded background summaries at shutdown, safe export writes, answer citations, and unavailable-model behavior. |
| `lineage` | Session-link storage and deduplication, and parent/child links derived from recorded subagent hooks. HTTP suites own external representations and errors. |
| `project` | Alias identity and cycles, graph assembly, timeline/query-plan guarantees, saved meld and braid storage, reads that finish on one pooled connection, discovery, redaction, and migrations, safe file navigation, editor configuration, and process launch arguments. Alias-scoped journeys protect scope resolution separately from ordinary project journeys. |
| `platform` | Configuration, CLI parsing, API errors, static routing, authentication and cookies, SSE authorization, payloads, replay ordering, heartbeat and shutdown lifecycle, and the retired-workflow schema migration. Live socket tests own scheduling and transport behavior. The root `Cli*ProcessTest` suites run the CLI as a separate process. |
| `judgment` | Request shaping, response validation, export redaction and telemetry, provider failures, persistence, event listening, beat folding, bounded shutdown, and disabled/standalone operation. |
| `contracts` | `WireContractFixtureTest` owns shared DTO/enum/frame fixtures, including the recall result and item shapes. `RestContractSnapshotTest` owns REST mappings and the endpoint matrix. `McpContractSnapshotTest` owns advertised tool names, annotations, and schemas. |
| `architecture` | Module dependencies and package rules, and isolated bootstrap of the lineage, memory, and recording modules. A module loading in isolation is distinct from the full app loading. |
| `postgres` | The real PostgreSQL engine's SQL, HTTP, transaction, concurrency, and migration contracts. SQLite passing does not establish PostgreSQL behavior. |

## Keeping the suite readable

- Extend an existing capability story when the new assertion describes its next
  observable result. Avoid repeating its entire setup in a second happy-path test.
- Give an independent failure or boundary condition its own test. Do not grow a
  single ordered scenario that hides which rule failed.
- Use `@Nested` and `@DisplayName` for capability chapters in broad suites, and
  descriptive method names for the individual behaviors.
- Use named parameterized cases when only the input and expected answer change.
  Preserve every meaningful input and boundary; a lower method count alone is
  not the goal.
- Keep fixtures local and explicit. Introduce a shared helper only when its
  contract is clearer than the setup it replaces.
- Before deleting overlap, identify the surviving test and move any unique
  assertion there. Transport serialization, transactions, authorization, and
  concurrency need evidence at their actual boundary.

## Running the stories

```bash
# Core capture, recall, and resume stories, including nested chapters
mvn test -Dtest=ContextLoopTest,ProjectContinuityTest

# Browser-facing API capabilities
mvn test -Dtest=AgenticControllerTest,ProjectAliasIntegrationTest

# One behavior inside a chapter
mvn test '-Dtest=ContextLoopTest*#projectionPostEndpointValidatesAndStoresPaths'

# All Java tests
mvn test
```

A plain `Class#method` selector does not reach a method inside a `@Nested` chapter;
it runs nothing and still succeeds. Add `*` after the class name, as above, or name
the chapter class (`ContextLoopTest$CaptureOverHttp#...`). Contract-matrix entries
such as `AgenticControllerTest#ingestAndListSessions` name the top-level class.

Surefire keeps one report file per top-level test class. Inside it, a test case's
class name is the `@DisplayName` of its class or chapter when one is declared.

The default suite uses temporary databases, fake providers, and isolated processes
for its ordinary scenarios. Tests requiring a live model use
`SBA_EVAL_LIVE_MODEL=true`; PostgreSQL contracts require `SBA_POSTGRES_TEST_URL`
and a disposable database as described in [PostgreSQL backend](postgres-backend.md).
Other environment assumptions can also skip tests. Inspect the Surefire report
before claiming those optional integrations were exercised. Do not enable live
provider or database tests merely to reduce the skipped count.
