# Building Black Box with Black Box

Black Box is used during its own development. This is a dated account of that use, with a narrow
claim: captured context was retrieved, influenced a compatibility requirement, and was followed by
verification and a new handoff. It is not a controlled productivity result.

## A continuity loop observed on October 3, 2026

An earlier development session had worked on a macOS recall launcher. Its handoff described the
`run=1` URL convention, differences between its checkout and the running frontend, and unfinished
validation. During later project-continuity work, Codex retrieved that handoff through `recallContext`
and explicitly reconciled it before changing the recall page.

The resulting [implementation plan](superpowers/plans/2026-10-02-project-continuity.md) preserved
the launcher contract while introducing a separate project selector and question. Regression tests
and packaged browser journeys checked the retained behavior. New handoffs recorded what changed,
what was verified, the integration state, and the next useful action.

| Step | Evidence |
| --- | --- |
| Retrieve earlier context | A successful `recallContext` response included the prior launcher handoff. |
| Use the context | The coordinator acknowledged that history, and the plan explicitly retained `run=1` compatibility. |
| Check the implementation | The plan records integrated browser acceptance; [Recall page tests](../frontend/src/pages/__tests__/RecallPage.test.tsx) exercise launcher behavior. |
| Leave continuity | Subsequent handoffs recorded implementation, verification, remaining limits, and merged state. |

The captured records and transcript were checked privately for this account. They are not bundled
as a public dataset: the public sources above expose the implementation and verification contract,
not the entire private development history.

## What the audit counted

A bounded audit of the coordinator's development session, ending October 3 at 19:07:54 UTC, found
one successful `recallContext` request returning three handoffs and 46 successful `captureHandoff`
writes. One additional capture attempt failed. This excludes other agents, hooks, direct HTTP
requests, and later activity. Writing occurred much more often than retrieval in this slice.

Those counts establish use of the interface. They do not establish that every handoff was read,
that all useful context was captured, or that a task would have failed without Black Box. The
compatibility example supplies a traceable use case; it does not supply a counterfactual.

## How to judge the product

Functional checks should show that a capture survives, a later query can retrieve it, and its source
can be inspected. A stronger usefulness claim needs comparable resumed tasks, an ordinary
handoff/search baseline, and observed outcomes. The [resumption evaluation](real-resumption-evaluation.md)
and [comparison protocol](continuation-comparison-protocol.md) preserve that distinction.

The [earlier project history](evolution.md) records other dated observations, including negative
results and changed decisions. Historical counts and small samples remain snapshots, not current
totals or general performance promises.

[Back to documentation](README.md)
