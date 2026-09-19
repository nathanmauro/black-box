# Local lifecycle manifest and reconciliation

Date: 2026-09-18
Status: offline prototype verified; isolated review refreshed on 2026-09-19

This slice models desired-state pause, resume, and destroy of a dedicated deployment using only
synthetic resources. It is not production tenancy or complete lifecycle management: no provider,
provisioning, migration engine, real job fencing, backup/restore, or deployment-script integration
is included. The [usage guide](../../local-lifecycle.md) defines the complete local contract.

## Safety contract

- Only local JSON and a fake infrastructure adapter; no cloud SDK, subprocess, credentials,
  network operations, or apply command in the lifecycle implementation.
- Validate schema, dedicated deployment/workspace identity, immutable release digest, exact
  resource ownership, and operation identity before planning any change. Shared resources fail closed.
- Desired PAUSED never starts compute or provisions missing resources. RUNNING requires existing
  resources and compatible schema; deleted environments require a future explicit provision path.
- Pause and destroy require confirmed durability before planning any action, even with stopped
  compute or zero work counters. Rejection preserves evidence and counters without creating a
  success journal entry. Pause closes admission, drains captures/jobs, and fences workers before
  stopping compute. Deadline expiry preserves durable captures/checkpoints and marks recovery required.
- Resume checks storage, release/schema compatibility, and simulated capture/recall/isolation
  readiness before opening admission. Recovery-required state blocks resume.
- Destroy needs separate confirmation bound to the operation, deployment, workspace, exact resources,
  and explicit retain-backup or discard choice. Retention requires a verified matching backup.
- Reconciliation re-observes between steps; interrupted/replayed actions are idempotent.
  One fake adapter lock serializes entire reconciliation attempts with atomic worker checkpoint
  validation/writes/counter updates; stale plans and stale worker fences fail closed.
- CLI inputs never change. Simulated observed state, journal, and failures appear only in output.

## Work and verification

1. Add strict desired-state/observed-state contracts, planner, and fake adapter.
2. Expose status, plan, and reconcile --dry-run with synthetic example fixtures.
3. Exercise the CLI and contracts for drain success/timeout, retained capture IDs, interrupted
   retries, stale plans/ownership, readiness failure, destroy gates, and no recreation.
4. Document the boundary and run the lifecycle suite plus existing offline cloud script tests.

## September 18 implementation report

- Added `scripts/lifecycle/lifecycle.py`, strict version 1 fixtures, and usage/safety contracts in
  `docs/local-lifecycle.md`; existing deploy scripts and services remain unchanged.
- 31 lifecycle contracts pass, including real CLI subprocess flows and unchanged-input checks.
- 43 existing offline cloud-script tests pass. Targeted `git diff --check` passes.
- Interrupted actions and lost journal acknowledgments converge without repeating fence changes.
- A destroy drain timeout stops compute but blocks deletion and preserves durable state.
- The initial slice proposed a lifecycle step for the then-existing CI workflow. Integration must
  use the current workflow, whose retired macOS matrix must not be restored implicitly.
- No AWS calls, resource provisioning, service restarts, or infrastructure application occurred.
- Remaining lifecycle work is documented in the usage guide; a future slice needs durable operation
  ownership and recovery-policy design before considering a real provider adapter.


## September 19 isolated review

The six prototype source, test, fixture, and documentation files were copied without changes from
an uncommitted September 18 slice into an isolated checkout of the reviewed application base.
Their initial SHA-256 hashes matched the coordinator's provenance receipt. The original checkout,
unrelated evaluation work, staged architecture plan, and historical retirement changes are outside
this checkpoint. The two fixture files remain byte-for-byte unchanged.

The review reproduced a false-success path: with compute already `STOPPED`, admission and streams
closed, pending work, and `durableCommitsConfirmed: false`, pause returned `SUCCEEDED` and cleared
work counters. The new regression failed before the implementation change (eight stopped-state
subcases and one CLI assertion). The same edge also accepted a no-op pause with zero counters.

The planner now requires confirmed durability before every pause/destroy attempt. It rejects the
request before journal or state mutation, preserving the captured evidence and counters. The same
check runs after each observed action; a lost confirmation during reconciliation records failure
without draining the remaining work. Resume, timeout fencing, exact destroy authorization, and
existing retry behavior are unchanged.

Verification commands and observed results:

- `python3 -B scripts/lifecycle/test_lifecycle.py LifecycleContracts.test_stopped_compute_cannot_hide_unconfirmed_durability CliContracts.test_stopped_unconfirmed_work_is_inspectable_but_cannot_reconcile`
  reproduced the regression before the fix.
- `python3 -B -m unittest discover -s scripts/lifecycle -p 'test_*.py'`: **34 tests passed**, including
  real CLI rejection, readable unchanged status, successful retry after confirmed fixture evidence,
  and loss of confirmation between actions.
- `python3 -B -m unittest discover -s scripts/cloud -p '*_test.py'`: **43 tests passed**, using fake
  AWS calls and temporary local HTTP fixtures; no provider operation ran.
- All three documented `status`, `plan`, and `reconcile --dry-run` CLI examples exited `0`, reported
  `mode: local-fake`, and produced the expected running status, ordered pause plan, and successful
  paused simulation. SHA-256 hashes of both input fixtures matched before and after each flow and
  still match the copied source receipt.
- Targeted whitespace validation passed. Local red/green output is retained as
  `/tmp/blackbox-lifecycle-durability-red.log`, `/tmp/blackbox-lifecycle-durability-green.log`,
  `/tmp/blackbox-lifecycle-cloud-green.log`, and `/tmp/blackbox-lifecycle-cli-green.log`.
- The Linux backend CI and default local verification command now run the lifecycle suite before
  Java. Fresh review verified their failure propagation and standard-library prerequisites; no
  additional platform jobs or infrastructure operations are introduced.

The simulator still has no provider, process execution, network client, persistent controller
journal, real durability probe, or recovery mechanism. A fixture's confirmation flag is injected
evidence, and changing it does not establish a real checkpoint or backup. No real deployment,
service, database, credentials, or infrastructure was changed by this review.


### Concurrent checkpoint correction

Fresh review identified a fence race in `complete_job`: validation preceded checkpoint writing
without holding the reconciler's lock. A deterministic regression used two real threads and paused
the worker immediately before its checkpoint assignment. Before the fix, destroy succeeded and
removed both resources; releasing the worker then recreated a checkpoint in absent storage and
changed `activeJobs` from zero to minus one. The new regression failed on that exact outcome.

Worker fence/count validation, checkpoint assignment, and decrement now share the existing adapter
lock with reconciliation. The regression proves that a checkpoint in progress prevents destroy
from advancing, accepted completion keeps the count nonnegative, a subsequent destroy succeeds,
and the old worker fence cannot write after destruction. Separate stale-fence and no-accepted-job
rejections verify lock release and unchanged state. Normal standalone completion after admission
closes is retained.

The fake holds this lock for the entire reconciliation attempt. Workers arriving during that
attempt wait and revalidate after it; this does not model workers progressing during a drain.
The guide now states that boundary explicitly. No provider, real worker, persistence mechanism,
or new lifecycle behavior is added.

Targeted concurrency, rejected-write lock-release, and accepted-checkpoint tests passed (3 tests).
Red/green output is retained locally in `/tmp/blackbox-lifecycle-concurrency-red.log` and
`/tmp/blackbox-lifecycle-concurrency-green.log`. The full lifecycle suite then passed **36 tests**
with output retained in `/tmp/blackbox-lifecycle-final-green.log`. All six owned files passed
whitespace validation, and both fixtures still match their initial source hashes.

The final coordinator rerun passed all 36 lifecycle tests. Fresh independent review accepted the
full slice after separately reproducing the race and rerunning all three correction tests. The
reviewed changes are isolated from unrelated canonical work and based on the current main branch.
