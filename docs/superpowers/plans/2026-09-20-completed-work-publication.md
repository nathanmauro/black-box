# Publish completed evaluation and retirement documentation

## Scope

Reconcile completed Black Box work against the remote repository and publish the remaining
reviewable source and documentation. The September 19 capture/recovery and offline lifecycle
changes are already merged through PRs #30 and #31, with successful main CI at `9e6c06b`.
Do not reapply their older local predecessors.

This change publishes the two existing evaluation harnesses, synthetic fixtures, tests, user
documentation, historical plans, and allowlisted numerical checkpoint aggregate. It also publishes
the existing cloud retirement documentation so the historical prototype is not described as live.
Offline harness tests join the local verification command and backend CI; they do not invoke
models, Docker or cloud services.

## Publication boundary

- Include only the explicit source/documentation paths reviewed for this change.
- Keep raw trial artifacts, private manifests, prompts, transcripts, credentials, local databases,
  local configuration and ignored build outputs out of Git.
- Preserve canonical dirty files, older lifecycle copies and formatting-only Java changes.
- Do not rerun model studies, change scores, provision infrastructure, deploy, bypass PR review or
  clean up worktrees as part of reconciliation.
- Historical plans record the authority and outcomes at their original execution time. This
  publication does not retrospectively change their results or imply a new experiment occurred.

## Acceptance

1. Both offline harness suites and CLI plan/help entry points pass in the isolated candidate.
2. A fresh read-only review checks correctness, privacy and evidence boundaries.
3. Relative documentation links resolve; whitespace and shell syntax checks pass.
4. Outgoing commits contain only reviewed paths, with the repository owner's sole authorship.
5. Published branch ref is verified against the local commit and a PR carries the review context.

## Evidence boundaries

The synthetic initial pilot passed all three tasks in every arm. The continuation screen reached
5/5 bare-agent success, so the predeclared gate stopped all 60 comparison runs. The local historical
checkpoint pilot scored 17/20 versus 12/20 exact field-and-schema matches, but did not establish
usefulness over ordinary handoff/search or accepted follow-up actions. Preserve those limits.

## Reconciliation verification — September 20

- All 27 benchmark/continuation and 16 private-evaluation contract tests passed in the isolated
  publication checkout. They use authored fixtures and local fake servers, not model inference.
- Both real CLI `plan` entry points, evaluator `--help`, shell syntax and staged whitespace checks
  passed. Existing relative documentation links resolve.
- Retained pilot manifests match all four initial-pilot source hashes and all seven continuation
  source hashes. The proposed numerical aggregate exactly matches the retained successful private
  report; failed attempts and private evidence remain outside the publication set.
- Independent final source/privacy review found no blocker in the explicit 25-path allowlist.
  Provider research remains a
  dated September 15 observation; this reconciliation did not query or mutate AWS resources.

Publication and integration are authorized through the normal pull-request workflow after final
review and CI. This is separate from deployment; the running service is not changed here.
