# Local real-work resumption evaluation

## Scope and safety contract

Implement a separate standard-library Python harness for five historical real-work checkpoint
resumptions. Preserve the existing synthetic benchmark, unrelated dirty work, live services,
credentials, and canonical event history. Use only explicit loopback HTTP endpoints, disable
proxies and redirects, and never invoke cloud inference, shell commands from model output, or
transcript ingestion. Private manifests, handoffs, prompts, responses, and grades belong in a
new owner-only directory outside Git; public output is an allowlist of numeric metrics and
fixed labels. No deployment, push, or service restart.

## Frozen comparison

Each pair uses identical task instructions, current source evidence, model, sampling settings,
and output schema. Only the recall arm receives the historical handoff. Fetch each selected
handoff by its exact event ID through real lexical recall; validate ID/kind and reject missing,
truncated, or unexpected results. This tests supplied recall context, not retrieval ranking.
Freeze five distinct event IDs, explicit historical cutoffs, independently sourced expected
facts, model settings, source hashes, and counterbalanced arm order before inference.

The bounded task is reconstructing a historical checkpoint against current repository evidence
and selecting its next safe verification step. The worker returns only predefined fact fields;
exact-match grading counts correct, missing, and incorrect assertions. Null means abstention.
The answer key and its evidence citations never enter model prompts. This is a real-work
resumption audit, not autonomous implementation or proof of accepted/executed follow-up actions.

## Usefulness gate

Report five-task paired wins/ties/losses, full-checkpoint success, supported fact coverage,
incorrect assertions, missing facts, latency, and model-reported usage (unknown stays null).
Report infrastructure/model failures and unattempted trials explicitly. Do not drop failures.

The previously proposed product gate also requires 20 historical candidates, an ordinary
latest-handoff/search baseline, >=70% useful supported suggestions, <10% stale/duplicate
suggestions, and >=3 accepted actions missed by that baseline. The present comparison cannot
clear that broader gate. Its verdict must remain `not_cleared` with the missing evidence named,
regardless of a favorable checkpoint score.

## Verification

Use fake local servers first. Test privacy boundaries, redirects, proxy bypass, malformed input,
missing recall, output filtering, scoring, failure accounting, shared-context equality, frozen
schedule, and permissions. Then run all ten local-model trials against five real handoffs.
Review the aggregate report and preserve private provenance locally. Leave a scoped handoff.

## Observed result

Implemented `scripts/evaluation/resumption_eval.py`, tests, user documentation, and a sanitized
aggregate JSON. Sixteen tests passed, including real loopback fake-server use and malformed recall
regressions. The first runtime attempt failed to load a model before any graded trial. Retained it;
ran the frozen cases through installed MLX weights in a temporary offline loopback process, then
stopped that process. No rubric tuning occurred between attempts.

The completed local run graded all ten trials: recalled handoff 17/20 exact schema matches and 3/5 complete
checkpoints; baseline 12/20 and 0/5. Four pair improvements, one tie. Full usefulness gate remains
not cleared for the prespecified missing evidence. Private manifests and full artifacts remain
outside the checkout with owner-only permissions; only aggregate data belongs in this repo.

Post-run interpretation audit: one baseline nonmatch was numeric 720 versus the required string,
not a wrong factual value. Preserved the frozen strict scorer and all original results; documented
this limitation explicitly. No answer-key, prompt, or rubric changes were made after inference.
