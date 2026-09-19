#!/usr/bin/env python3
"""Offline lifecycle planning. This module deliberately has no real infrastructure adapter."""
import argparse
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
from threading import Lock
from typing import Protocol


class ContractError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ContractError(message)


def fields(value, required, optional=()):
    require(isinstance(value, dict), "Expected an object")
    require(set(required) <= value.keys(), "Missing fields: " + ", ".join(sorted(set(required) - value.keys())))
    require(value.keys() <= set(required) | set(optional), "Unknown fields: " + ", ".join(sorted(value.keys() - set(required) - set(optional))))


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}", value),
            "Invalid identity or reference")


def integer(value, minimum=0):
    require(type(value) is int and value >= minimum, "Expected integer >= " + str(minimum))


def release(value):
    fields(value, ("digest", "schemaVersion"))
    require(isinstance(value["digest"], str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value["digest"]),
            "Release must be an immutable sha256 digest")
    integer(value["schemaVersion"], 1)


def validate_manifest(m):
    fields(m, ("version", "adapter", "deploymentId", "workspaceId", "topology", "desiredState",
               "operationId", "release", "resources", "drainDeadlineSeconds", "backupRef"))
    require(type(m["version"]) is int and m["version"] == 1, "Unsupported manifest version")
    require(m["adapter"] == "fake", "Only the fake adapter is supported")
    require(m["topology"] == "dedicated", "Shared infrastructure lifecycle is not supported")
    for key in ("deploymentId", "workspaceId", "operationId"):
        identifier(m[key])
    require(m["desiredState"] in ("RUNNING", "PAUSED", "DESTROYED"), "Invalid desired state")
    release(m["release"])
    fields(m["resources"], ("compute", "storage"))
    for value in m["resources"].values():
        identifier(value)
    require(len(set(m["resources"].values())) == 2, "Resource references must be distinct")
    integer(m["drainDeadlineSeconds"], 1)
    require(m["drainDeadlineSeconds"] <= 86400, "Drain deadline exceeds one day")
    if m["backupRef"] is not None:
        identifier(m["backupRef"])
    return m


def validate_observed(s):
    fields(s, ("version", "deploymentId", "workspaceId", "resources", "admissionOpen", "streamsOpen",
               "storageReady", "release", "activeJobs", "inflightCaptures", "drainSeconds",
               "durableCommitsConfirmed", "fence", "workersFenced", "recoveryRequired", "readiness", "captures",
               "checkpoints", "backups", "operations", "lastFailure"))
    require(type(s["version"]) is int and s["version"] == 1, "Unsupported observed version")
    for key in ("deploymentId", "workspaceId"):
        identifier(s[key])
    require(isinstance(s["resources"], dict), "Expected resource inventory")
    for ref, resource in s["resources"].items():
        identifier(ref)
        fields(resource, ("kind", "deploymentId", "workspaceId", "shared", "state"))
        require(resource["kind"] in ("compute", "storage"), "Invalid resource kind")
        require(resource["state"] in (("RUNNING", "STOPPED", "ABSENT") if resource["kind"] == "compute"
                                       else ("PRESENT", "ABSENT")), "Invalid resource state")
        for key in ("deploymentId", "workspaceId"):
            identifier(resource[key])
        require(type(resource["shared"]) is bool, "Expected shared boolean")
    for key in ("admissionOpen", "streamsOpen", "storageReady", "durableCommitsConfirmed", "workersFenced", "recoveryRequired"):
        require(type(s[key]) is bool, "Expected boolean: " + key)
    for key in ("activeJobs", "inflightCaptures", "drainSeconds", "fence"):
        integer(s[key])
    release(s["release"])
    fields(s["readiness"], ("authenticatedCaptureRecall", "isolation"))
    require(all(type(v) is bool for v in s["readiness"].values()), "Expected readiness booleans")
    for key in ("captures", "checkpoints", "backups", "operations"):
        require(isinstance(s[key], dict), "Expected object: " + key)
    for ref, backup in s["backups"].items():
        identifier(ref)
        fields(backup, ("deploymentId", "workspaceId", "storageRef", "verified"))
        for key in ("deploymentId", "workspaceId", "storageRef"):
            identifier(backup[key])
        require(type(backup["verified"]) is bool, "Expected backup verification boolean")
    for op_id, op in s["operations"].items():
        identifier(op_id)
        fields(op, ("intent", "steps", "status"))
        require(isinstance(op["intent"], str) and re.fullmatch(r"[a-f0-9]{64}", op["intent"]), "Invalid intent hash")
        require(isinstance(op["steps"], list) and all(isinstance(v, str) for v in op["steps"]), "Invalid operation steps")
        require(op["status"] in ("IN_PROGRESS", "SUCCEEDED", "FAILED"), "Invalid operation status")
    if s["lastFailure"] is not None:
        fields(s["lastFailure"], ("operationId", "message"))
        identifier(s["lastFailure"]["operationId"])
        require(isinstance(s["lastFailure"]["message"], str), "Invalid failure message")
    return s


def fingerprint(value):
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def observed_state(m, s):
    compute = s["resources"][m["resources"]["compute"]]["state"]
    storage = s["resources"][m["resources"]["storage"]]["state"]
    if compute == "ABSENT" and storage == "ABSENT":
        return "DESTROYED"
    if compute == "ABSENT" or storage == "ABSENT":
        return "INCOMPLETE"
    if s["recoveryRequired"]:
        return "RECOVERY_REQUIRED"
    if s["lastFailure"]:
        return "FAILED"
    if compute == "STOPPED" and not s["admissionOpen"] and not s["streamsOpen"]:
        return "PAUSED"
    if compute == "RUNNING" and s["admissionOpen"]:
        return "RUNNING"
    return "DRAINING" if m["desiredState"] != "RUNNING" else "STARTING"


def validate_targets(m, s):
    for key in ("deploymentId", "workspaceId"):
        require(m[key] == s[key], "Observed " + key + " does not match manifest")
    for kind, ref in m["resources"].items():
        require(ref in s["resources"], "Missing resource observation: " + ref)
        resource = s["resources"][ref]
        require(resource["kind"] == kind and not resource["shared"]
                and all(resource[key] == m[key] for key in ("deploymentId", "workspaceId")),
                "Resource ownership mismatch or shared resource: " + ref)
    # An incomplete target list must not strand owned resources during destruction.
    owned = {ref for ref, r in s["resources"].items()
             if r["deploymentId"] == m["deploymentId"] and r["workspaceId"] == m["workspaceId"]}
    require(owned == set(m["resources"].values()), "Manifest must name the exact owned resource set")


def validate_destroy(m, s, auth):
    require(auth is not None, "Destroy requires separate exact-target authorization")
    fields(auth, ("operationId", "deploymentId", "workspaceId", "resources", "dataPolicy", "backupRef"))
    for key in ("operationId", "deploymentId", "workspaceId", "resources", "backupRef"):
        require(auth[key] == m[key], "Destroy authorization does not match " + key)
    require(auth["dataPolicy"] in ("retain-backup", "discard"), "Explicit destroy data policy required")
    if auth["dataPolicy"] == "discard":
        require(m["backupRef"] is None, "Discard requires an explicit null backupRef")
    else:
        backup = s["backups"].get(m["backupRef"])
        require(backup is not None and backup["verified"]
                and backup["deploymentId"] == m["deploymentId"]
                and backup["workspaceId"] == m["workspaceId"]
                and backup["storageRef"] == m["resources"]["storage"],
                "Destroy retention requires a verified backup for this exact storage")


def plan(m, s, authorization=None):
    validate_manifest(m)
    validate_observed(s)
    validate_targets(m, s)
    if m["desiredState"] == "DESTROYED":
        validate_destroy(m, s, authorization)
    else:
        require(authorization is None, "Destroy authorization is only valid for DESTROYED")
    intent = fingerprint({"manifest": m, "authorization": authorization})
    existing = s["operations"].get(m["operationId"])
    require(existing is None or existing["intent"] == intent, "Operation ID was reused with different intent")
    require(not any(key != m["operationId"] and value["status"] == "IN_PROGRESS"
                    for key, value in s["operations"].items()), "Another operation is in progress")
    compute = s["resources"][m["resources"]["compute"]]["state"]
    storage = s["resources"][m["resources"]["storage"]]["state"]
    steps = []
    if m["desiredState"] == "RUNNING":
        require(compute != "ABSENT" and storage != "ABSENT", "Resume cannot provision or recreate absent resources")
        require(not s["recoveryRequired"], "Recovery required before resume")
        require(s["release"]["schemaVersion"] == m["release"]["schemaVersion"],
                "Schema migration is not supported in this slice")
        healthy = (compute == "RUNNING" and s["admissionOpen"] and s["streamsOpen"]
                   and s["storageReady"] and not s["workersFenced"]
                   and s["release"] == m["release"] and all(s["readiness"].values()))
        if not healthy:
            require(not s["admissionOpen"] and not s["streamsOpen"], "Pause before changing an active release or repairing readiness")
            steps.append("check_storage")
            if compute != "RUNNING" or s["release"] != m["release"] or s["workersFenced"]:
                steps.append("start_exact_release")
            steps.extend(("verify_readiness", "open_admission"))
    else:
        # Stopped compute is not evidence that accepted work committed durably.
        # Validate before draining counters or recording a successful no-op pause.
        require(s["durableCommitsConfirmed"], "Durable commits are not confirmed; refuse pause/destroy")
        if s["admissionOpen"]:
            steps.append("close_admission")
        if s["activeJobs"] or s["inflightCaptures"]:
            steps.append("drain")
        # Recovery state is an explicit durable fence from a prior drain timeout.
        if not s["workersFenced"] and (compute != "ABSENT" or s["activeJobs"] or s["inflightCaptures"]):
            steps.append("fence_workers")
        if s["streamsOpen"]:
            steps.append("close_streams")
        if compute == "RUNNING":
            steps.append("stop_compute")
        if m["desiredState"] == "PAUSED":
            require(compute != "ABSENT" and storage != "ABSENT", "Pause cannot recreate absent resources")
        else:
            timeout_expected = ((s["activeJobs"] or s["inflightCaptures"])
                                and s["drainSeconds"] > m["drainDeadlineSeconds"])
            if not s["recoveryRequired"] and not timeout_expected:
                if compute != "ABSENT":
                    steps.append("delete_compute")
                if storage != "ABSENT":
                    steps.append("delete_storage")
    return {"intent": intent, "observedFingerprint": fingerprint(s), "operationId": m["operationId"],
            "desiredState": m["desiredState"], "observedState": observed_state(m, s),
            "steps": steps, "dataPolicy": authorization["dataPolicy"] if authorization else None,
            "warnings": ["Simulation only; retained storage/backups can still incur costs in real infrastructure."]
            + (["Drain deadline will expire; preserve durable state and require recovery before resume or deletion."]
               if (s["activeJobs"] or s["inflightCaptures"]) and s["drainSeconds"] > m["drainDeadlineSeconds"] else [])
            + (["Recovery required; resume and resource deletion are blocked."] if s["recoveryRequired"] else [])}


class InfrastructureAdapter(Protocol):
    """Observation and serialized, guarded mutation port; only the in-memory fake implements it."""
    lock: Lock

    def observe(self) -> dict: ...
    def perform(self, action: str, manifest: dict) -> None: ...


class FakeInfrastructure:
    def __init__(self, observed):
        self.state = deepcopy(validate_observed(observed))
        self.lock = Lock()
        self.calls = []

    def observe(self):
        return deepcopy(self.state)

    def complete_job(self, worker_fence, job_id, checkpoint):
        # Fence validation and the durable-state model update must be atomic with
        # reconciliation; an old worker cannot write after drain/fence/destroy.
        with self.lock:
            require(worker_fence == self.state["fence"] and not self.state["workersFenced"] and not self.state["recoveryRequired"], "Stale worker fence")
            require(self.state["activeJobs"] > 0, "No accepted jobs")
            self.state["checkpoints"][job_id] = checkpoint
            self.state["activeJobs"] -= 1

    def perform(self, action, m):
        s = self.state
        compute = s["resources"][m["resources"]["compute"]]
        storage = s["resources"][m["resources"]["storage"]]
        self.calls.append(action)
        if action == "close_admission":
            s["admissionOpen"] = False
        elif action == "drain":
            if s["drainSeconds"] > m["drainDeadlineSeconds"]:
                s["fence"] += 1
                s["workersFenced"] = True
                s["recoveryRequired"] = True
                s["lastFailure"] = {"operationId": m["operationId"], "message": "Drain deadline expired; recovery required"}
            s["activeJobs"] = s["inflightCaptures"] = 0
        elif action == "fence_workers":
            if not s["workersFenced"]:
                s["fence"] += 1
                s["workersFenced"] = True
        elif action == "close_streams":
            require(s["durableCommitsConfirmed"], "Unconfirmed durable commits")
            s["streamsOpen"] = False
        elif action == "stop_compute":
            require(not s["admissionOpen"] and not s["streamsOpen"] and not s["activeJobs"]
                    and not s["inflightCaptures"] and s["durableCommitsConfirmed"] and s["workersFenced"], "Compute is not drained")
            compute["state"] = "STOPPED"
        elif action == "check_storage":
            require(storage["state"] == "PRESENT" and s["storageReady"], "Storage is not ready")
        elif action == "start_exact_release":
            require(storage["state"] == "PRESENT" and s["storageReady"], "Storage is not ready")
            compute["state"] = "RUNNING"
            s["release"] = deepcopy(m["release"])
            s["workersFenced"] = False
        elif action == "verify_readiness":
            require(all(s["readiness"].values()), "Authenticated capture/recall or isolation readiness failed")
        elif action == "open_admission":
            require(compute["state"] == "RUNNING" and s["storageReady"] and not s["recoveryRequired"]
                    and not s["workersFenced"] and s["release"] == m["release"]
                    and all(s["readiness"].values()), "Readiness gates failed")
            s["admissionOpen"] = s["streamsOpen"] = True
        elif action == "delete_compute":
            require(compute["state"] in ("STOPPED", "ABSENT"), "Compute must be stopped before destroy")
            compute["state"] = "ABSENT"
        elif action == "delete_storage":
            require(compute["state"] == "ABSENT" and not s["admissionOpen"]
                    and not s["streamsOpen"] and s["durableCommitsConfirmed"], "Unsafe storage deletion")
            storage["state"] = "ABSENT"
            s["storageReady"] = False
            s["captures"].clear()
            s["checkpoints"].clear()
        else:
            raise ContractError("Unknown action: " + action)


def reconcile_fake(m, adapter: InfrastructureAdapter, authorization=None, expected_plan=None, interrupt_after=None):
    """Mutate only a fake in memory. An interruption is a simulated process loss after an action."""
    require(type(adapter) is FakeInfrastructure, "Reconciliation is restricted to the in-memory fake")
    require(adapter.lock.acquire(blocking=False), "Deployment is locked")
    try:
        initial = plan(m, adapter.observe(), authorization)
        if expected_plan is not None:
            require(initial == expected_plan, "Stale or altered plan; re-plan before reconciliation")
        s = adapter.state
        op = s["operations"].setdefault(m["operationId"], {"intent": initial["intent"], "steps": [], "status": "IN_PROGRESS"})
        op["status"] = "IN_PROGRESS"
        performed = 0
        try:
            # Re-observe after every action. Validated non-mutating gates run once per attempt.
            completed = set()
            while True:
                current = plan(m, adapter.observe(), authorization)
                remaining = [step for step in current["steps"] if step not in completed]
                if not remaining:
                    break
                step = remaining[0]
                adapter.perform(step, m)
                op["steps"].append(step)
                completed.add(step)
                performed += 1
                if interrupt_after is not None and performed >= interrupt_after:
                    return {"outcome": "INTERRUPTED", "plan": initial, "observed": adapter.observe()}
            op["status"] = "FAILED" if s["recoveryRequired"] else "SUCCEEDED"
            if not s["recoveryRequired"]:
                s["lastFailure"] = None
            return {"outcome": "RECOVERY_REQUIRED" if s["recoveryRequired"] else "SUCCEEDED",
                    "plan": initial, "observed": adapter.observe()}
        except ContractError as error:
            op["status"] = "FAILED"
            s["lastFailure"] = {"operationId": m["operationId"], "message": str(error)}
            return {"outcome": "FAILED", "plan": initial, "observed": adapter.observe()}
    finally:
        adapter.lock.release()


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field: " + key)
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=no_duplicate_keys,
                      parse_constant=lambda value: require(False, "Invalid JSON constant: " + value))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "plan", "reconcile"))
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--observed", required=True, type=Path, help="Local fake observation fixture")
    parser.add_argument("--destroy-authorization", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "reconcile" and not args.dry_run:
        parser.error("reconcile requires --dry-run; apply is not implemented")
    if args.command != "reconcile" and args.dry_run:
        parser.error("--dry-run is only valid with reconcile")
    try:
        m = validate_manifest(read_json(args.manifest))
        s = validate_observed(read_json(args.observed))
        validate_targets(m, s)
        auth = read_json(args.destroy_authorization) if args.destroy_authorization else None
        if args.command == "status":
            require(auth is None, "status does not accept destroy authorization")
            result = {"desiredState": m["desiredState"], "observedState": observed_state(m, s),
                      "observed": s}
        elif args.command == "plan":
            result = plan(m, s, auth)
        else:
            result = reconcile_fake(m, FakeInfrastructure(s), auth)
        print(json.dumps({"mode": "local-fake", "inputFilesChanged": False, **result}, indent=2, sort_keys=True))
        return 3 if result.get("outcome") in ("FAILED", "RECOVERY_REQUIRED") else 0
    except (ContractError, OSError, ValueError) as error:
        print(json.dumps({"mode": "local-fake", "error": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
