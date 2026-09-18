# Possible futures

What an agent records changes the evidence the next agent sees, which can change the next
decision. That is a hypothesis about a feedback loop, not a proven learning system. A record can
preserve useful reasoning; it can also preserve a stale assumption or make a speculative path
seem inevitable. The next experiments should distinguish those outcomes.

Every proposal below is **Not shipped**. Existing primitives are named separately from the work
that would be built. Success and stop criteria are proposed evaluation gates, not observed results
or commitments to implement. Recall remains on demand by design; none requires a SessionStart
hook or turns the server into an agent runner.

## Not shipped — supersession and use provenance

**What exists today.** Decisions carry rationale, rejected alternatives, confidence, and open
loops; Handoffs carry continuation context. Captured events have stable IDs, and UI recall links
can open their source event. The current capture contracts are in
[`CaptureDecisionRequest`](../src/main/java/dev/nathan/sbaagentic/recording/CaptureDecisionRequest.java)
and [`CaptureHandoffRequest`](../src/main/java/dev/nathan/sbaagentic/recording/CaptureHandoffRequest.java).
[Recall observability](recall-observability.md) measures retrieval but deliberately excludes
recalled event IDs and contents. It is not an evidence-use ledger.

**What would be built.** An explicit relation would record that one decision supersedes another,
with the reason and effective time, preserving both records. A separate use record would let an
agent or reviewer identify which evidence informed a later decision or action. Retrieval would
distinguish current intent from historical intent without silently deleting old reasoning.
The provenance design would need its own disclosure and access rules; existing telemetry should
not quietly expand into content or identity logging.

**Success criteria.** On a reviewed set of changed decisions, recall returns the currently
applicable choice while preserving an inspectable path to the older one. A reviewer can follow
each recorded use to an actual source and distinguish a client declaration from independently
observed use. Stale advice decreases against the current retrieval baseline.

**Stop criteria.** Stop or narrow the experiment if agents routinely invent use links, if
supersession cannot be established without rewriting history, or if the extra records expose
more private context than the continuation benefit warrants. Merely drawing more edges is not
success. This proposal does not make captured instructions authoritative over current user intent.

## Not shipped — a projection-to-outcome loop

**What exists today.** `captureProjection` records one to five agent-written possibilities, each
with an optional confidence, plus an optional basis for the set; see
[`MemoryMcpTools`](../src/main/java/dev/nathan/sbaagentic/memory/internal/adapter/in/mcp/MemoryMcpTools.java).
[`trajectory.ts`](../frontend/src/lib/trajectory.ts) turns captures into a graph of history and
possible next paths. Its text-overlap handling is not an explicit outcome relation. Projections
are recalled lexically; semantic recall covers Decisions, Handoffs, and Observations only.
[Evidence-to-Linear](linear-integration.md) can propose source-linked candidates for explicit
selection, and task completion can create a normal Handoff.

**What would be built.** A reviewer could mark a proposal selected, declined, superseded, or
resolved and link its outcome back to the original Projection and later evidence. Selection
would retain the original date, wording, and confidence. An unselected possibility would remain
speculative. The server would store these facts, while an external agent or person executes work.

**Success criteria.** A reviewer can trace a selected proposal through work to a verified outcome
without inferring links from similar text. On a prospectively recorded set, the evaluation reports
selected and unselected paths, partial results, and failures. It distinguishes an intervention
(the displayed proposal helped cause the outcome) from an independent prediction.

**Stop criteria.** Stop if links are mostly retrospective storytelling, if the system rewards
vague proposals that cannot be falsified, or if reviewers cannot agree on outcomes. The four-path
[2026-08-06 scorecard](evolution.md#the-ledger-recorded-its-own-history) is n=1; it cannot calibrate
confidence or establish predictive value. A ghost on the graph must not become selected work
without an explicit decision.

## Not shipped — measured continuation quality

**What exists today.** The optional
[`recall hook`](../scripts/hooks/sba-recall-hook.sh) supplies a bounded packet, while MCP
[`RecallResultClamp`](../src/main/java/dev/nathan/sbaagentic/memory/internal/adapter/in/mcp/RecallResultClamp.java)
bounds larger on-demand results. Exact-event navigation lets a reviewer inspect a source.
The [read-half plan](superpowers/plans/2026-08-28-wire-the-read-half.md) and
[recall evaluation fixture](../src/test/resources/eval/recall-queries.json) provide starting points.
Current [telemetry](recall-observability.md) separates attempts, completions, contributions, and
returned overlap; it does not measure decisions made after retrieval.

**What would be built.** Run a bounded continuation study on realistic resumed tasks, comparing
Black Box recall with a latest-handoff packet and ordinary search. Fix the evaluation protocol and
rubric before reviewing outcomes. Keep the target evidence out of evaluation-writing sessions
where practical, and disclose contamination when the corpus contains its own test questions.
Use on-demand retrieval as a valid condition, rather than treating automatic startup injection as
mandatory. Record what was requested, delivered, and actually used as distinct observations.

**Success criteria.** Independent review finds fewer missed constraints, stale recommendations,
unsupported claims, and repeated investigations, with useful next actions grounded in source
evidence. Report sample size, failures, retrieval latency, and context cost alongside improvements.
A retrieval-score improvement is useful only if it survives the continuation comparison.

**Stop criteria.** Stop or redesign if benefits disappear against the latest-handoff baseline,
if added context makes stale advice more persuasive, or if the evaluator can recover the expected
answer from leaked fixtures. Empty results and unsuccessful continuations belong in the report.
No productivity claim should be inferred from capture totals or successful API calls.

## Not shipped — resilient shared continuity

**What exists today.** The [PostgreSQL profile](postgres-backend.md) supports a separate canonical
shared database, and [authentication](authentication.md) provides one trusted workspace with a
browser credential and agent bearer token. Task claims and completion Handoffs have transactional
ownership rules. The [Linear adapter](../scripts/linear/blackbox_linear.py) persists a pending
publication identity before sending a write and reconciles ambiguous responses; that is a bounded
external-write precedent, not a general capture outbox.

**What would be built.** Start with stable capture identities, a durable outbox, explicit delivery
acknowledgements, and retry/deduplication behavior for disconnected clients. Add workspace access
boundaries only for a demonstrated sharing need. Multi-host workers would require explicit
leases or fencing and recovery semantics, not merely a shared task table. Local/cloud history
synchronization would be a separate design with conflict and deletion rules.

**Success criteria.** Fault-injected disconnects and retries preserve acknowledged captures without
duplicate effects. Workspace isolation tests deny cross-workspace reads and writes. Recovery
cannot grant stale workers authority or discard the only copy of completed work. A real shared
workflow benefits enough to justify the operational cost and new failure modes.

**Stop criteria.** Stop before adding distributed machinery if one authoritative server meets the
need. Stop expansion if reconciliation cannot be explained or tested, if isolation requires
trusting caller-supplied scope, or if worker fencing is incomplete. PostgreSQL alone supplies none
of those guarantees. Bundled hooks and the runner currently lack bearer-header wiring; even the
existing authenticated surface needs a compatible client.

## Not shipped — the cloud prototype as a reproducible rejected alternative

**What exists today.** The tracked [CloudFormation template](../infra/aws/lightsail.json),
[`Dockerfile.cloud`](../Dockerfile.cloud), and [deployment scripts](../scripts/cloud/) document a
single-owner managed AWS prototype, documented in [docs/lightsail-prototype.md](lightsail-prototype.md);
it is not a public service. The committed
[2026-09-08 acceptance record](superpowers/plans/2026-09-08-cloud-prototype.md) describes the
experiment's deployment and its checks. Deletion receipts stay in private operator notes, so this
page asserts no current live-resource or billing state.

The template records a historical base estimate of USD 31.20/month, one managed container,
managed PostgreSQL, and three separately generated credentials through Secrets Manager references.
That is the estimate encoded in the experiment, not a current price quote or a total-cost promise.
Source inspection found no embedded credential values, account IDs, or literal resource ARNs;
resource names and generated-secret references are configuration, not deployed identities.
The template retains the database and secrets on deletion, so deleting a stack is not by itself
proof of complete resource disposal.

**What would be built.** Turn the proposed retrospective, “the retired cloud prototype as a
reproducible rejected alternative,” into a public, sanitized experiment record: motivation,
acceptance evidence, measured cost, reasons for rejection, and verified disposition. Preserve the
build recipe and record what a replay needs, including current bundle/image choices and credentials.
The unshipped work is that complete retrospective and replay contract, not the already committed
prototype code. This page makes no retirement-date or live-resource assertion.

**Success criteria.** A reviewer can reconstruct what was tested, why a shared service was
considered, what it cost at the time, and why that exact approach was set aside. An isolated replay
can verify capture, authenticated recall, restart persistence, and its own explicit cleanup scope
without private deployment IDs or the owner's transcript corpus.

**Stop criteria.** Do not republish private operational exports to fill evidence gaps. Stop a replay
before provisioning if cost, teardown ownership, or retained resources are unclear. Stop the
retrospective at the verified evidence boundary if disposition is not ready for publication.
Historical deployment success is not an invitation to use an available hosted product.

## Rejected directions — Not shipped

These are boundaries on further work, not claims that existing features have been removed.
[NEXT.md](../NEXT.md) records the decision not to extract the runner, and the
[September reset](superpowers/plans/2026-09-08-cloud-prototype.md) records the board and
historical-backlog limits. The other reasons, including the cited 2026-08-06 trajectory decision,
are stated here.

| Not shipped direction | Reason to keep it rejected |
| --- | --- |
| Extract the runner into another service/repository as the next architecture project | It already has an isolated module boundary; extraction does not establish better continuity. |
| Add a read-side backend before usage demonstrates uptake | First evaluate actual retrieval and continuation. The shipped service telemetry does not justify an outcome ledger by itself. |
| Expand the Board into a second general-purpose execution system | Preserve existing tasks; revalidate evidence and explicitly select work in Linear. |
| Generate speculative futures on the server | The trajectory design and its 2026-08-06 implementation decision favored agent-written possibilities: Black Box records what an agent thought, with provenance. |
| Treat every old open loop as current work | Source dates, present relevance, acceptance criteria, and explicit selection must survive the conversion. |
| Treat a graph or a successful recall as proof of improvement | Measure continuation outcomes and retain negative evidence. |
