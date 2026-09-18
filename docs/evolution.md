# How Black Box evolved

This is a curated history through `1833e07` (2026-09-17), not a roadmap. The dated designs and
implementation plans under [docs/superpowers](superpowers/) are the primary sources. They preserve
both original proposals and later corrections; a proposal alone does not establish shipped behavior.

## Eight eras

### 1. Reusable intent emerges from recording — May 21–June 10

The initial application, `8b33fcd` (2026-05-21), collected agent sessions and hook events in a local
store. The rebrand, `b6e1a9e` (2026-05-29, PR #2), made the purpose more specific: agents could
capture typed decisions and handoffs, then retrieve another agent's reasoning through MCP.
The important change was a write-and-query loop, not another transcript viewer.

The launch gate added loopback binding (`7766af7`, 2026-06-10), default secret redaction
(`ba0482d`, 2026-06-10), and a reproducible demo plus optional recall hook (`b3640b8`, 2026-06-10).
The `v0.1.0` tag resolves to `a45e8c5` (2026-06-10). The [release record](../CHANGELOG.md) belongs
to that gate; it is not a release-by-release account of everything built afterward.

### 2. Sessions become project continuity — June 15–July 9

Finding information became the next constraint. The
[Projects and durable melds design](superpowers/specs/2026-06-15-projects-durable-melds-design.md)
made previews, explicit saves, and visible provenance part of the human workflow. The
[UI rewrite](superpowers/specs/2026-06-16-blackbox-ui-rewrite-design.md) prioritized search and
navigation as the event history grew.

`ecf4eb7` (2026-06-16) added the project workbench and meld preview; `c2f00d3` (2026-06-24, PR #10)
replaced the vanilla-JavaScript interface with SolidJS and Vite. The rewrite included SSE updates
and an isolated Playwright path. `9c4214c` (2026-07-01, PR #12) made global activity the default,
and `27a669e` (2026-07-09, PR #13) integrated project context. The
[Activity design](superpowers/specs/2026-07-01-activity-stream-plan.md) explains the shift toward
starting with the stream and narrowing it.

### 3. Memory acquires ownership rules — July 9–17

The [task-queue design](superpowers/specs/2026-06-28-agent-task-queue-design.md) asked whether
independent agents could divide work without making the server an executor. `dac5d52`,
`6100960`, and `ee70724` (all 2026-07-09) established exact-lane claiming, lifecycle records,
and completion Handoffs. PR #14 integrated the loop as `5aab0a3` (2026-07-10).

The server never launches a worker or executes a task command. `0d578a0` (2026-07-10) made that
boundary explicit in the docs. An external [FULL_AUTO runner](superpowers/specs/2026-07-15-full-auto-board-runner.md)
arrived in `d3f449a` (2026-07-16, PR #18), followed by
[SDLC approval gates](superpowers/specs/2026-07-16-sdlc-mode.md) in `1b45fd6` (2026-07-17, PR #19).
The runner consumes REST state; task completion preserves a normal Handoff for the next session.
These are implemented coordination mechanisms, not evidence of heavy queue adoption.

### 4. Boundaries and lineage become explicit — July 20–23

The [modular-monolith refactor plan](superpowers/plans/2026-07-20-java-modular-monolith-refactor.md)
set an enforceable dependency graph as the goal. The work landed in one day, but its safety argument
was frozen behavior, not speed: `97e8616` (2026-07-20) captured the pre-refactor contracts before
`f63ca2f` removed the universal event repository and `5a4cf1b` enforced module boundaries
(both 2026-07-20). PR #20 integrated the refactor as `aaad475` (2026-07-20).

The intentional evidence is the [contract fixtures](../src/test/resources/contracts/), added by
`97e8616` before any code moved: MCP tools, REST mappings and shapes, wire examples, and a
pre-refactor SQLite schema. ArchUnit and Spring Modulith tests enforce the new module boundaries.
Together they check that internal movement did not silently change clients' contracts. Some test
packages still carry their pre-refactor flat names; that is leftover shape, not a policy, and the
convention remains that tests mirror production packages. The [subagent-lineage design](superpowers/specs/2026-07-23-subagent-lineage-design.md)
then made parent/child recording explicit; `fb7d8e5` (2026-07-23) integrated hook-derived child
sessions and nested Browse navigation.

### 5. Recall earns the right to return nothing — July 27–28

The [semantic-recall design](superpowers/specs/2026-07-27-semantic-recall-design.md#3a-measured-the-naive-implementation-does-not-work)
records a diagnostic against 956 structured events and six hand-selected paraphrases. The results,
committed in `da76395` (2026-07-27), changed the implementation before it shipped:

| Embedding input | Recall@1 | Recall@5 |
| --- | --- | --- |
| Raw text, no task prefixes | 0/6 | 3/6 |
| Raw text with document/query prefixes | 1/6 | 4/6 |
| Distilled content with prefixes | 2/6 | 5/6 |

Task prefixes mattered, and the repeated structure of long handoffs overwhelmed their subjects.
Distilling decision/rationale and handoff/next-action content improved retrieval. This was a small
in-corpus diagnostic, not a general benchmark. One symptom-to-fix query still missed, and the
fixture could later contaminate its own corpus when its queries appeared in captured plans, an
observation made during this showcase pass rather than a recorded finding at the time.
Hybrid lexical/vector recall shipped in `a958396` (2026-07-28).

No-match queries then returned plausible noise. `39b5e1e` (2026-07-28) explicitly refused to guess
a score floor. `15e0f72` (2026-07-28) exposed true cosine similarity instead of a fusion score and
added a distribution harness. The [score-semantics plan](superpowers/plans/2026-07-28-recall-score-semantics.md)
defines the measurement gate; Decision `9a3d4929` (2026-07-28) records a minimum true-target
score of 0.620 and a highest junk hit of 0.606. `5c1ceaf` (2026-07-28) set a configurable floor of 0.61 for semantic-only additions.

The follow-up `9070a2b` (2026-07-28) recorded a valid live match at 0.6109. That narrow margin is
why the floor should be remeasured, not treated as a universal constant. Lexical matches remain;
semantic recall may add nothing. Semantic retrieval covers Decisions, Handoffs, and Observations
only. Projections are lexical-only; stored summary vectors are not returned by recall.

### 6. The stream becomes the product — July 28–August 22

The [consolidation design](superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md)
prioritized making recorded work legible. The
[trajectory design](superpowers/specs/2026-08-05-project-trajectory-graph-design.md) organized past
captures, rejected alternatives, open loops, and proposed futures without treating them as the same
kind of evidence. `1f90378` and `f886140` (2026-08-06) added the trajectory view and agent-written
Projections. A Projection records a possibility; the server does not invent or execute it.

The [Stream legibility design](superpowers/specs/2026-08-20-stream-legibility-and-queriability-design.md)
turned the stream into a queryable evidence surface. `6a96cf0` (2026-08-20) added SQLite FTS5;
`bd89abf` (2026-08-20) added saved views and source navigation. PR #26 integrated that work as
`af4ba42` (2026-08-22). A shared [grammar fixture](../src/main/resources/query/grammar-cases.json)
checks the Java and TypeScript query parsers against the same cases. Capped history is visible,
rather than presented as the complete past.

### 7. The read-half audit — August 28–31

The [read-half audit and plan](superpowers/plans/2026-08-28-wire-the-read-half.md) tested whether
captured context could actually reach an agent in a usable form. It found an unused optional
hook, a short lookback that missed relevant history, and oversized manually requested results.
Those findings challenged the inference that successful capture alone proved continuity.

Recall at every session start is not the product's required workflow. Recall is pulled on demand by
design; SessionStart injection remains optional. The relevant correction was to make
both paths usable and observable, not to make the hook mandatory. `87b6a30` (2026-08-31, PR #28)
shipped bounded recall and complete-session reader improvements. The hook defaults became 720
hours, 3 items, and 4,000 characters; MCP gained its separate default 24,000-character text clamp.
A hook registration or a returned packet still does not prove useful continuation.

### 8. Usefulness becomes the question — September 8–17

The [cloud prototype and product reset](superpowers/plans/2026-09-08-cloud-prototype.md) shifted
attention toward evidence becoming selected work. `306adb4` (2026-09-08) added PostgreSQL,
authentication, and the [evidence-to-Linear adapter](linear-integration.md). `7c090b0` (2026-09-08)
corrected bearer authentication across asynchronous completion, and `3b8d71b` (2026-09-08)
recorded prototype acceptance. This was a single-owner managed AWS prototype, documented in
[docs/lightsail-prototype.md](lightsail-prototype.md); it is not a public service.

`1833e07` (2026-09-17) added [recall observability](recall-observability.md), following the
[dated instrumentation plan](superpowers/plans/2026-09-17-recall-observability.md). It measures
service behavior and failure modes without recording raw query/result content. It cannot establish
that an agent read a result or made a better decision. That remains the question to test.

## Three times the project changed its mind

**Task lifecycle needed its own log.** Queue transitions belong to a project-scoped task, while
captured agent events belong to sessions. The
[queue design](superpowers/specs/2026-06-28-agent-task-queue-design.md) put lifecycle facts in
`task_events`. Completion deliberately rejoins the two through a real Handoff, so coordination
results remain available through ordinary recall rather than a second memory system.

**More capture did not prove continuity.** The
[August audit](superpowers/plans/2026-08-28-wire-the-read-half.md) showed that stored intent could
coexist with unusable retrieval defaults. The corrective sequence was smaller packets, explicit
on-demand access, and then service telemetry. Neither event volume nor call volume establishes
that prior intent improved a resumed task.

**The Board stopped being the expansion strategy.** The
[September reset](superpowers/plans/2026-09-08-cloud-prototype.md) preserved existing workflow data
while directing new selected work toward Linear. Candidates retain source dates and IDs, need
current relevance and acceptance criteria, and are published explicitly. Historical suggestions do
not automatically become an active backlog, and the adapter does not synchronize two boards.

## Measured, not asserted

The [Stream performance note](superpowers/plans/2026-07-28-stream-perf-notes.md) rejected its own
apparent 85× speedup: the 6,081 ms baseline ran on a loaded machine and the 71 ms comparison after a
restart on a quiet one. That was a confounded comparison. The same note disclosed that expand-all
at 500 rows took 164 ms on 2026-07-28, missing its sub-100 ms budget. The 2026-07-29 containment
follow-up measured 83/85/91 ms, median 85, with all 500 expanded rows retained and the last visible.
These are dated observations with a method and limitations, not promises for every machine.

[Recall telemetry](recall-observability.md) makes a similar distinction:

| Signal | What it establishes |
| --- | --- |
| `semantic_attempted` | An eligible topic reached the embedder availability check. |
| `semantic_completed` | Embedding, vector retrieval, and semantic admission completed. |
| `semantic_contributed` | At least one admitted, resolvable semantic hit entered fusion. |
| `semantic_returned` | Number of final service-page items overlapping admitted semantic hits. |

A hybrid call can contribute nothing; a contributing call can return no semantic item after the
page limit. Returned overlap is not proof that lexical retrieval would have missed the item.
The MCP clamp acts afterward, and service success proves neither receipt nor benefit.

For the showcase pass's dated test record, taken on 2026-09-17: the Java suite ran 593 tests with
0 failures and 0 errors on both macOS (11 skipped) and a Linux Temurin 21 container (14 skipped);
the skip difference is platform-gated and live-model-gated tests. Vitest passed 607 tests across
49 files on both. Counts are a dated observation of one tree, not a guarantee for every machine.

## The ledger recorded its own history

The 2026-09-17 project-scoped export contains 215 structured captures: 25 Decisions, 134 Handoffs,
55 Observations, and 1 Projection, written mostly by Codex and Claude; a Grok subagent that drafted
the 2026-06-28 task-queue design spec wrote two of the project-scoped captures as well. These are
scope counts, not a claim that every capture's subject is the codebase, and they keep moving: agents
working on this repository write to the same store. The first scoped capture was recorded
76 minutes after `8b33fcd` (2026-05-21); its content is not reproduced here.

One 2026-06-10 Decision, identified by `116aed2e`, records the positioning change directly:

> Black Box launch positioning pivots to: typed decisions (rejected-alternatives + confidence + open-loops) bundled with raw-event recording, summaries, and a web UI in one zero-dep local app — NOT "first local-first cross-agent MCP memory."

It recorded three rejected alternatives:

> Keep 'first cross-agent local memory' framing — refuted by prior art
>
> Compete with claude-mem on capture volume — lost cause at ~81k stars
>
> Reposition as production observability — wrong audience, Langfuse/Arize own it

These are historical quotations, not current dependency, popularity, or market-exclusivity claims.
The decision itself left verification of the star figure open. Its useful evidence is the recorded
refusal to keep a stronger positioning claim after finding prior art.

The one self-Projection, `bd602573` (2026-08-06, evening local time), allows a small retrospective scorecard:

| Recorded path | Confidence | Outcome evidenced in the repository |
| --- | --- | --- |
| Merge trajectory graph to main | 0.8 | Happened: `c65b4ea`, the `graph-development` tip the projection describes as six commits ahead of main, is an ancestor of `main`. The branch landed linearly, so no merge commit exists. It was on main by 2026-08-19, the day `7e16140` showcased the graph in the README. |
| Speed up the Timeline tab | 0.6 | Query changes landed in `38c6910` and `ec69b4a` (2026-08-14). |
| Tune trajectory heuristics from real use | 0.5 | Did not happen: the exported heuristic constants in `trajectory.ts` are unchanged since the projection; `8de6c21` (2026-08-14) and `bd89abf` (2026-08-20) changed other trajectory behavior. |
| Retire the parked `/graph` constellation page | 0.4 | Did not happen in this snapshot; the route remains in `frontend/src/index.tsx`. |

The two highest-confidence paths happened within two weeks and the two lowest did not. `7e16140` is
the showcase checkpoint, not itself a merge commit. This is **n=1**, not a calibration statistic or evidence of forecasting accuracy. Showing
an agent its proposed future can influence what gets built. Explicit outcome linkage and measured
continuation quality remain [possible futures](futures.md), not conclusions from this anecdote.
