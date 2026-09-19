# Local lifecycle rehearsal

Black Box has an **offline, fake-only** lifecycle CLI for rehearsing pause, resume, and destroy
of one dedicated deployment. It reads a desired-state manifest and a synthetic observation,
produces a plan, and can reconcile an in-memory copy. Python 3.9+ and the standard library are sufficient. No credentials are needed.

There is no cloud adapter, provider discovery, provision command, or apply mode. This command
cannot start, stop, delete, or deploy real infrastructure. It does not change the Black Box API,
local database, service, or the historical Lightsail scripts. The retired prototype cannot be
resumed by this tool. Production tenancy and lifecycle management remain unfinished.

## Try status, plan, and dry-run

Run from the repository root:

```sh
python3 scripts/lifecycle/lifecycle.py status \
  --manifest scripts/lifecycle/fixtures/paused.manifest.json \
  --observed scripts/lifecycle/fixtures/running.observed.json

python3 scripts/lifecycle/lifecycle.py plan \
  --manifest scripts/lifecycle/fixtures/paused.manifest.json \
  --observed scripts/lifecycle/fixtures/running.observed.json

python3 scripts/lifecycle/lifecycle.py reconcile --dry-run \
  --manifest scripts/lifecycle/fixtures/paused.manifest.json \
  --observed scripts/lifecycle/fixtures/running.observed.json
```

`status` reports desired and observed state, resource inventory, and the operation journal without
requiring destroy authorization. `plan` validates intent and authorization, then reports ordered
steps, the intent hash, the observed fingerprint, and warnings. `reconcile --dry-run` executes those
steps against an in-memory fake and emits `outcome`, the initial `plan`, and resulting `observed`.
All commands leave their input files unchanged; even the journal and simulated failures live only
in output. Repeated dry-runs against the same inputs produce the same result.

To continue a simulation, explicitly save the returned `observed` object into a new local fixture,
change `desiredState` and `operationId` in a copy of the manifest, and pass those files into the next
command. `RUNNING` requests resume, `PAUSED` requests pause, and `DESTROYED` requests destroy.
Use a new operation ID whenever intent changes; retries of the same intent keep the same ID.
No command changes the manifest's desired state implicitly.

Exit codes: `0` for status, a valid plan, or a successful simulation; `2` for invalid input, rejected
intent, missing authorization, or CLI usage; `3` for simulated execution failure or recovery required.
A successful `plan` does not prove execution readiness: storage/readiness checks can still fail.
Output always identifies `mode: local-fake`.

## Manifest and observation contracts

The checked-in [manifest](../scripts/lifecycle/fixtures/paused.manifest.json) is the version 1 example.
All fields are required and unknown fields are rejected:

| Field | Contract |
| --- | --- |
| `version`, `adapter`, `topology` | Exactly `1`, `fake`, and `dedicated`; shared-resource lifecycle is rejected |
| `deploymentId`, `workspaceId` | Match observation and ownership on every selected resource |
| `desiredState` | `RUNNING`, `PAUSED`, or `DESTROYED` |
| `operationId` | Stable for one intent; reuse with changed manifest or authorization fails |
| `release` | Immutable `sha256:` digest and positive integer `schemaVersion` |
| `resources` | Distinct exact compute/storage references; the complete owned resource set |
| `drainDeadlineSeconds` | Integer from 1 through 86400 |
| `backupRef` | A backup reference, or explicit `null`; no secrets or backup bodies |

The [observation fixture](../scripts/lifecycle/fixtures/running.observed.json) is an explicit synthetic
inventory, not provider evidence. It includes ownership, existence, compute state, admission/stream
state, storage readiness, deployed release, active-work counters, fence state, durable captures and
checkpoints, backup metadata, operation journal, recovery flag, and last failure. Unrelated inventory
entries are allowed and remain unchanged. A missing observation is **unknown**, not evidence that a
resource is absent: use an explicit `ABSENT` resource record for a known deletion. Status reports
`INCOMPLETE` when only one of the two resources is absent.

`drainSeconds` models a deterministic fake drain duration; no wall-clock sleeps occur. Successful
drain clears the active-work counters. Timeout clears them as fenced/abandoned work, retains already
durable captures/checkpoints, and records recovery required. The fake does not generate writes for
pending captures or prove that any real job/checkpoint committed. `durableCommitsConfirmed` and
`readiness` are injected facts; production adapters would need independently obtained evidence.
`authenticatedCaptureRecall` and `isolation` simulate readiness outcomes, not real HTTP/auth checks.
Backup verification is also fixture metadata, not actual backup or restore execution.

## Safety and retries

- Pause and destroy require `durableCommitsConfirmed: true` before planning or changing state,
  including when compute is already stopped or work counters are zero. Stopped compute does not
  prove accepted work committed. Rejection preserves all input evidence and counters; `status`
  remains available for inspection and does not certify durability. Confirmation is an injected
  fixture fact in this simulation, not a command that proves or repairs real durable storage.
- Pause closes new-work admission, drains accepted work, fences workers, closes streams, and stops
  compute. Between reconciliation attempts, an accepted worker can checkpoint while admission is
  closed if its fence remains valid. A deadline timeout stops compute with recovery required;
  resume and resource deletion remain blocked, preserving the last durable checkpoint.
- Resume requires existing dedicated compute and storage, compatible schema, storage readiness,
  the exact release digest, and successful simulated authenticated capture/recall and isolation
  checks before opening streams/admission. Schema migration is deliberately unsupported. Resume
  cannot recreate absent resources. Changing a paused release manifest does not wake compute;
  changing an active release requires an explicit pause first.
- Destroy requires a separate exact-target authorization document, even when resources are already
  absent. It drains and stops compute before deletion. `retain-backup` requires verified metadata
  bound to the same deployment, workspace, and storage. `discard` requires an explicit null backup.
  Neither option deletes backup entries. Retained resources may cost money on a future real platform.
- The reconciler observes again between actions. Observed resource/fence state drives replay,
  including when an action succeeded but its journal acknowledgment was lost. Repeated completed
  operations perform no infrastructure actions. An optional expected plan in the Python contract
  rejects changed observations or a tampered plan before mutation.
- The fake uses one in-memory adapter lock for each entire reconciliation attempt and for worker
  checkpoint validation, writing, and counter updates. A worker arriving during reconciliation
  waits, then validates the current fence before writing. A reconciler arriving during a checkpoint
  rejects the busy lock and can be retried. This coarse lock does not simulate workers progressing
  during drain; drain outcomes remain fixture facts. An interrupted journal blocks competing
  operation IDs, and failed operations may be retried with the same intent.
  This is not a cross-process lock, distributed lease, durable controller journal, or recovery UI.
  No unsafe override of recovery-required state is provided.

Example separate authorization for an explicitly discarded **synthetic** deployment:

```json
{
  "operationId": "demo-destroy-1",
  "deploymentId": "demo-deployment",
  "workspaceId": "demo-workspace",
  "resources": {
    "compute": "fake:demo:compute",
    "storage": "fake:demo:storage"
  },
  "dataPolicy": "discard",
  "backupRef": null
}
```

Match those fields in a copied manifest with `desiredState: DESTROYED`, then pass
`--destroy-authorization <local-file.json>` to `plan` or `reconcile --dry-run`.
This local fixture is not authorization for future real infrastructure changes.

## Verification and remaining work

```sh
python3 -m unittest discover -s scripts/lifecycle -p 'test_*.py'
```

The contracts exercise the actual CLI as subprocesses, prove input files do not change, and cover
pause/resume capture retention, deadline expiry, stale workers, readiness failures, release/schema
checks, exact destroy authorization, backup binding, unrelated resources, idempotency, serialization,
stale plans, interrupted steps, and lost acknowledgments. Engine tests deny network and child-process
creation. These tests require no infrastructure credentials and run locally on Python 3.9+.
The default `./scripts/verify.sh` command and Linux backend CI run this suite before Java.
The prototype's verification evidence and remaining boundaries are recorded in its
[implementation plan](superpowers/plans/2026-09-18-local-lifecycle-slice.md).

Future slices still need real adapter design, durable operation ownership, versioned migrations,
actual capture/recall and tenant-isolation checks, recoverable backup/restore proof, recovery policy,
provider-specific cost accounting, explicit provisioning authority, and deployment integration that
respects desired state. The historical deployment scripts do not yet consume this manifest.
