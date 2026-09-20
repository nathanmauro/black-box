#!/usr/bin/env python3
"""Measured predecessor histories and a gated, paired continuation study."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import subprocess
import sys
import time
import uuid

import benchmark as b
from continuation_fixtures import TASKS, fixture

ARMS = ("bare", "handoff", "blackbox", "misleading")
PROMPT = ("Complete the maintenance task described by README.md, docs/protocol.md and docs/change.md. "
          "solution.py is the previous implementation. history.json contains prior context, if any; "
          "it can be irrelevant or stale. Current repository contracts take precedence. "
          "Only change solution.py. Use the Python standard library; do not install dependencies "
          "or use the network. Run python3 -m unittest -v. Hidden tests check the documented contract. "
          "Finish with a concise handoff explaining the implementation, verification, pitfalls "
          "you actually encountered, and any unresolved work. Do not claim unperformed tests.")
CORRUPTIONS = {
    "export-checkpoint": "Previous export decision: an empty page ends traversal; cursors should be tested for truthiness.",
    "webhook-ledger": "Previous ledger decision: deleted accounts should be removed entirely; event IDs are globally unique across tenants.",
    "invoice-adjustments": "Previous billing decision: apply each percentage to the running adjusted amount and round only the final total.",
    "config-inheritance": "Previous configuration decision: use one global visited set so a shared ancestor is never applied twice; skip unknown parents.",
    "migration-routing": "Previous routing decision: reversible edges can be traversed in either direction and choosing the cheapest outgoing edge is sufficient.",
}


def schedule(repeats, seed, arms=ARMS):
    rng = random.Random(seed)
    blocks = [(name, repeat) for name in TASKS for repeat in range(repeats)]
    rng.shuffle(blocks)
    result = []
    for name, repeat in blocks:
        order = list(arms)
        rng.shuffle(order)
        result.extend({"task":name,"repeat":repeat,"arm":arm,
                       "fixture_seed":seed + 100 * repeat + TASKS.index(name)} for arm in order)
    return result


def public_test(f):
    lines = ["import unittest", "from solution import run", "", "class PublicTests(unittest.TestCase):"]
    for i, c in enumerate(f["public"]):
        lines += [f"    def test_{i}(self):", f"        self.assertEqual(run({c['args'][0]!r}), {c['expected']!r})"]
    lines += ["", "if __name__ == '__main__': unittest.main()"]
    return "\n".join(lines) + "\n"


def workspace(directory, f, starter, history):
    root = directory / "input"
    root.mkdir(parents=True)
    for path, content in f["files"].items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    (root / "solution.py").write_text(starter)
    (root / "solution.py").chmod(0o666)
    (root / "test_public.py").write_text(public_test(f))
    b.dump(root / "history.json", history)
    return root


def attempt(docker, image, f, starter, history, args, directory, prompt=PROMPT):
    """Keep a measured checkpoint even on timeout; never grade model code on the host."""
    started = time.monotonic()
    result = {"status":"infrastructure_error","passed":False,"measured_agent":args.mode == "run",
              "metrics":None,"agent_seconds":None,"model":args.model,"effort":args.effort}
    candidate = directory / "candidate.py"
    try:
        root = workspace(directory, f, starter, history)
        (directory / "prompt.txt").write_text(prompt + "\n")
        result["input_hashes"] = {str(p.relative_to(root)):b.sha(p) for p in root.rglob("*") if p.is_file()}
        result["history_bytes"] = (root / "history.json").stat().st_size
        if args.mode == "smoke":
            candidate.write_text(f["reference"])
            (directory / "answer.txt").write_text("SMOKE ONLY: authored reference, not an agent handoff.\n")
        else:
            auth = Path(args.auth_file).expanduser().resolve()
            with docker.container(image, ["sleep","infinity"], ["--network","bridge","--mount",
                                  "type=bind,src="+str(auth)+",dst=/home/node/.codex/auth.json,readonly"]) as name:
                b.command(["docker","cp",str(root)+"/.",name+":"+b.WORKDIR])
                b.command(["docker","start",name])
                b.command(["docker","exec",name,"test","-r","/home/node/.codex/auth.json"])
                context = b.command(["docker","exec",name,"codex",*b.CODEX_FLAGS,
                                     "debug","prompt-input",prompt],timeout=60)
                (directory / "rendered-context.json").write_text(context)
                result["prompt_audit"] = b.check_prompt(context)
                invocation = ["docker","exec",name,"codex","exec",*b.CODEX_FLAGS,
                              "--ignore-user-config","--ignore-rules","--ephemeral","--skip-git-repo-check",
                              "--sandbox","danger-full-access","--model",args.model,
                              "-c",'model_reasoning_effort="'+args.effort+'"',"--json",
                              "-o",b.WORKDIR+"/answer.txt",prompt]
                agent_start = time.monotonic()
                timed_out = False
                try:
                    with (directory / "events.jsonl").open("w") as out, (directory / "stderr.log").open("w") as err:
                        completed = subprocess.run(invocation,stdout=out,stderr=err,timeout=args.timeout)
                    result["worker_exit_code"] = completed.returncode
                except subprocess.TimeoutExpired:
                    timed_out = True
                    # Stop the actual worker before copying its checkpoint; killing docker exec alone
                    # does not guarantee the in-container model process stopped.
                    b.command(["docker","stop","--time","1",name],timeout=20)
                finally:
                    result["agent_seconds"] = time.monotonic() - agent_start
                    result["metrics"] = b.metrics((directory / "events.jsonl").read_text())
                b.command(["docker","cp",name+":"+b.WORKDIR+"/solution.py",str(candidate)])
                try:
                    b.command(["docker","cp",name+":"+b.WORKDIR+"/answer.txt",str(directory / "answer.txt")])
                except subprocess.CalledProcessError:
                    if not timed_out:
                        raise
                if timed_out:
                    result.update(status="timeout",error="Fixed model wall-clock budget exceeded")
                elif (completed.returncode != 0 or not result["metrics"]["completed_turns"]
                      or result["metrics"]["error_events"]):
                    raise RuntimeError("Codex inference failed; inspect saved events and stderr")
        grade = b.grade_solution(docker,image,candidate,f,directory / "grading")
        result.update(checks=grade["checks"],checkpoint_passed=grade["passed"],candidate_sha256=b.sha(candidate))
        if result["status"] != "timeout":
            result.update(status="graded",passed=grade["passed"])
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as exc:
        result.update(status="infrastructure_error",passed=False,error=str(exc)[:700])
    result["total_seconds"] = time.monotonic() - started
    b.dump(directory / "result.json",result)
    return result


def make_history(docker, image, box, args, output):
    histories = {}
    for index, name in enumerate(TASKS):
        print(f"[history {index+1}/5] {name}",flush=True)
        f = fixture(name,args.seed-20000+index,False)
        # Historical regression examples are public to the predecessor, not secret continuation feedback.
        f["public"] = f["cases"]
        directory = output / "histories" / name
        attempts, starter = [], f["starter"]
        for iteration in range(2):
            dest = directory / str(iteration)
            prompt = PROMPT if iteration == 0 else PROMPT + " The previous checkpoint failed historical validation. Recheck the public regression examples and repair it."
            result = attempt(docker,image,f,starter,[],args,dest,prompt)
            attempts.append(result)
            if result["status"] == "infrastructure_error":
                raise RuntimeError("History worker infrastructure failure: " + str(dest))
            if (dest / "candidate.py").is_file():
                starter = (dest / "candidate.py").read_text()
            if result["passed"]:
                break
        last = directory / str(len(attempts)-1)
        answer = (last / "answer.txt").read_text() if (last / "answer.txt").exists() else "No completed handoff; predecessor timed out."
        history = {"source":"prior Codex attempt" if args.mode == "run" else "authored smoke reference",
                   "task":name,"context":answer,
                   "verification":{"historical_contract_passed":attempts[-1]["passed"],
                                   "attempts":len(attempts)},
                   "caution":"Historical evidence only. Current repository contracts take precedence."}
        # Only historical pass status and the actual agent explanation enter memory. No hidden
        # continuation cases, expected values, grader output, or reference solutions enter it.
        canonical = json.dumps(history,sort_keys=True)
        scope = "/synthetic/continuation/" + name
        capture = box.request("POST","/api/handoffs",{
            "source":"continuation-benchmark","clientSessionId":box.name+"-"+name,"repo":scope,
            "contextSummary":canonical,"openLoops":[],"nextAction":"Revalidate against current repository contracts."})
        b.dump(directory / "capture.json",capture)
        recalled = recall_history(box,name,directory / "initial-recall.json")
        if recalled != history:
            raise RuntimeError("Capture/recall changed the useful handoff")
        (directory / "checkpoint.py").write_text(starter)
        b.dump(directory / "handoff.json",history)
        histories[name] = {"history":history,"starter":starter,"attempts":attempts,
                           "checkpoint_sha256":b.sha(directory / "checkpoint.py")}
        # Guard against tasks already solved by the inherited checkpoint.
        current = fixture(name,args.seed-10000+index,True)
        inherited = b.grade_solution(docker,image,directory / "checkpoint.py",current,directory / "inherited-grade")
        if inherited["passed"]:
            raise RuntimeError("Continuation already solved by predecessor: " + name)
    b.dump(output / "histories.json",histories)
    return histories


def recall_history(box, name, save_to):
    response = box.recall("/synthetic/continuation/" + name)
    b.dump(save_to,response)
    items = response.get("items",[])
    if len(items) != 1 or items[0].get("kind") != "handoff":
        raise RuntimeError("Expected exactly one scoped measured handoff")
    return json.loads(items[0]["headline"])


def useful_history(arm, history):
    if arm == "bare": return []
    if arm == "misleading":
        # Deliberate synthetic counterfactual, labeled as such in the manifest, never in production.
        # A separate control file records exactly what was changed.
        changed = dict(history)
        changed["context"] = CORRUPTIONS[history["task"]]
        changed["verification"] = {"historical_contract_passed":True,"attempts":1}
        return [changed]
    return [history]


def trial(docker,image,box,histories,row,args,output):
    directory = output / (str(row["repeat"])+"-"+row["task"]+"-"+row["arm"])
    directory.mkdir(parents=True)
    started = time.monotonic()
    recall_seconds = 0
    try:
        frozen = histories[row["task"]]
        history = frozen["history"]
        if row["arm"] == "blackbox":
            before = time.monotonic()
            history = recall_history(box,row["task"],directory / "recall.json")
            recall_seconds = time.monotonic()-before
            if history != frozen["history"]:
                raise RuntimeError("Recalled evidence differs from ordinary handoff baseline")
        f = fixture(row["task"],row["fixture_seed"],True)
        result = attempt(docker,image,f,frozen["starter"],useful_history(row["arm"],history),args,directory)
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as exc:
        result = {"status":"infrastructure_error","passed":False,"error":str(exc)[:700]}
    result.update(row,recall_seconds=recall_seconds,total_seconds=time.monotonic()-started)
    b.dump(directory / "result.json",result)
    return result


def aggregate(rows):
    result = {}
    for arm in dict.fromkeys(r["arm"] for r in rows):
        selected = [r for r in rows if r["arm"] == arm]
        item = {"scheduled":len(selected),"passed":sum(r["passed"] for r in selected),
                "statuses":{s:sum(r["status"]==s for r in selected) for s in sorted({r["status"] for r in selected})}}
        for field in ("input_tokens","cached_input_tokens","output_tokens","command_calls","failed_commands"):
            values = [(r.get("metrics") or {}).get(field) for r in selected]
            item[field] = sum(values) if all(v is not None for v in values) else None
        seconds = [r.get("agent_seconds") for r in selected]
        item["median_agent_seconds"] = statistics.median(seconds) if all(v is not None for v in seconds) else None
        result[arm] = item
    return result


def difficulty_gate(rows):
    if len(rows) != 5 or any(r["status"] not in ("graded","timeout") for r in rows):
        raise RuntimeError("Difficulty gate requires five completed model outcomes")
    passed = sum(r["passed"] for r in rows)
    return "ceiling_detected" if passed == 5 else "floor_detected" if passed == 0 else "evaluation"


def paired(rows):
    result = {}
    for arm in ARMS[1:]:
        counts = dict(both_pass=0,bare_only=0,memory_only=0,both_fail=0,incomplete=0)
        for name, repeat in sorted({(r["task"],r["repeat"]) for r in rows}):
            block = {r["arm"]:r for r in rows if (r["task"],r["repeat"])==(name,repeat)}
            a,c = block.get("bare"),block.get(arm)
            if not a or not c or any(r["status"] not in ("graded","timeout") for r in (a,c)):
                counts["incomplete"] += 1
            else:
                counts["both_pass" if a["passed"] and c["passed"] else "bare_only" if a["passed"] else "memory_only" if c["passed"] else "both_fail"] += 1
        result[arm] = counts
    return result


def report(output,state):
    state["screen_summary"] = aggregate(state["screen"])
    state["evaluation_summary"] = aggregate(state["evaluation"])
    state["paired_vs_bare"] = paired(state["evaluation"])
    b.dump(output / "report.json",state)
    lines = ["# Black Box continuation study","",f"Status: **{state['status']}**.","",
             "Five authored scenario families; measured agent histories in run mode. Exploratory, not a significance or RSI claim.",
             "Plain handoff and Black Box contain identical evidence. Misleading controls are deliberately fabricated counterfactuals.",""]
    if state.get("history_usage"):
        usage = state["history_usage"]["history"]
        lines += [f"History production: {usage['scheduled']} attempts; {usage['passed']} passed historical validation; "
                  f"{usage['input_tokens']} input and {usage['output_tokens']} output tokens; "
                  f"{state['history_seconds']:.2f} total seconds including grading/capture.", ""]
    for label,key in (("Difficulty screen","screen"),("Evaluation","evaluation")):
        lines += ["## "+label,"","| Arm | Pass / scheduled | Status counts | Input tokens | Output tokens | Median model seconds |",
                  "| --- | --- | --- | --- | --- | --- |"]
        for arm,item in state[key+"_summary"].items():
            lines.append(f"| {arm} | {item['passed']} / {item['scheduled']} | {item['statuses']} | {item['input_tokens']} | {item['output_tokens']} | {item['median_agent_seconds']} |")
    lines += ["","## Per-scenario outcomes","","| Phase | Task | Repeat | Arm | Result | Failed checks |","| --- | --- | --- | --- | --- | --- |"]
    for phase in ("screen","evaluation"):
        for row in state[phase]:
            failed = ", ".join(c["name"] for c in row.get("checks",[]) if not c["passed"])
            lines.append(f"| {phase} | {row['task']} | {row['repeat']} | {row['arm']} | {'pass' if row['passed'] else row['status']} | {failed} |")
    lines += ["","## Interpretation","",
              "- The five scenario families share frozen prior histories across repetitions; 60 runs are not 60 independent problems.",
              "- Separate seeds vary data identifiers, not entire repository architectures. These are authored miniature repositories.",
              "- No evaluation outcome is fed back into another worker's history or prompt.",
              "- Bare/handoff compares memory utility. Handoff/Black Box tests round-trip parity, not search superiority.",
              "- Misleading notes replace the handoff with a deliberately false claim; length is not matched, so this is a safety stress test, not a pure relevance ablation.",
              "- Model time excludes retrieval; end-to-end trial time includes it. History-generation costs are recorded separately.",
              "- The five bare screening runs are excluded from evaluation, which proceeds only at 1–4 screen passes.",
              "- Complete success is all private checks within the model budget. Infrastructure failures are separate from coding failures."]
    if state.get("error"): lines += ["", "Error: " + state["error"]]
    (output / "report.md").write_text("\n".join(lines)+"\n")


def verify_pairs(output,rows):
    for name, repeat in {(r["task"],r["repeat"]) for r in rows}:
        block = [r for r in rows if (r["task"],r["repeat"])==(name,repeat) and "input_hashes" in r]
        hashes = [{k:v for k,v in r["input_hashes"].items() if k != "history.json"} for r in block]
        if hashes and any(h != hashes[0] for h in hashes): raise RuntimeError("Unpaired task inputs")
        by_arm = {r["arm"]:r for r in block}
        if all(arm in by_arm for arm in ("handoff","blackbox")):
            if by_arm["handoff"]["input_hashes"]["history.json"] != by_arm["blackbox"]["input_hashes"]["history.json"]:
                raise RuntimeError("Useful memory arms did not receive identical bytes")
    b.dump(output / "paired-verification.json",{"checked_rows":len(rows),"matched":True})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode",choices=("plan","smoke","run"),default="plan",nargs="?")
    parser.add_argument("--model")
    parser.add_argument("--effort",default="medium",choices=("low","medium","high","xhigh"))
    parser.add_argument("--timeout",type=int,default=180)
    parser.add_argument("--repeats",type=int,default=3)
    parser.add_argument("--seed",type=int,default=20260918)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--auth-file",default=str(Path(os.environ.get("CODEX_HOME",str(Path.home()/".codex")))/"auth.json"))
    args = parser.parse_args()
    if args.timeout <= 0 or args.repeats <= 0: parser.error("Positive timeout and repeats required")
    screen = schedule(1,args.seed-10000,("bare",))
    evaluation = schedule(args.repeats,args.seed)
    if args.mode == "plan":
        print(json.dumps({"screen":screen,"evaluation":evaluation,"history_runs":"5 to 10",
                          "gate":"evaluate only at 1–4 of 5 screen passes","model":args.model},indent=2)); return 0
    if args.mode == "run" and (not args.model or not Path(args.auth_file).expanduser().is_file()):
        parser.error("Explicit --model and existing signed-in CLI auth required")
    b.command(["docker","info","--format","{{.ServerVersion}}"])
    run_id = "bb-continuation-"+uuid.uuid4().hex[:12]
    output = (args.output or b.REPO / "target/benchmarks" / run_id).resolve()
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    docker = b.Docker(run_id)
    def pending(rows): return [dict(r,status="not_run",passed=False) for r in rows]
    state = {"status":"preflight","mode":args.mode,"is_model_evidence":args.mode=="run",
             "screen":pending(screen),"evaluation":pending(evaluation)}
    box = None
    try:
        report(output,state)
        worker = b.command(["docker","image","inspect","--format","{{.Id}}",b.WORKER_TAG])
        server = b.command(["docker","image","inspect","--format","{{.Id}}",b.SERVER_TAG])
        controller_root = b.HERE
        hashes = {p.name:b.sha(p) for p in controller_root.glob("*.py")}
        snapshot = output / "controller-source"
        snapshot.mkdir()
        for p in b.HERE.glob("*.py"): shutil.copy2(p,snapshot / p.name)
        # Imported controller/fixtures are already in memory. All subsequent grader copies
        # come from this frozen source, never from a concurrently edited working tree.
        b.HERE = snapshot
        manifest = {"run_id":run_id,"mode":args.mode,"model":args.model,"effort":args.effort,
                    "timeout_seconds":args.timeout,"seed":args.seed,"screen_schedule":screen,"evaluation_schedule":evaluation,
                    "worker_image":worker,"server_image":server,"server_built_from_current_source":False,
                    "codex_version":b.command(["docker","run","--rm","--network","none",worker,"codex","--version"]),
                    "git_head":b.command(["git","rev-parse","HEAD"],cwd=b.REPO),
                    "git_status":b.command(["git","status","--short"],cwd=b.REPO),"source_hashes":hashes,
                    "misleading_control":{"origin":"synthetic counterfactual, NOT prior-agent evidence","claims":CORRUPTIONS},
                    "gate":{"min_pass":1,"max_pass":4,"screen_size":5},
                    "primary_metric":"all private checks pass within fixed wall-clock budget",
                    "scope":"memory utility and capture/recall fidelity; not retrieval superiority or RSI"}
        b.dump(output / "manifest.json",manifest)
        box = b.BlackBox(docker,server)
        state["status"] = "generating_histories"
        report(output,state)
        before = time.monotonic()
        histories = make_history(docker,worker,box,args,output)
        state["history_seconds"] = time.monotonic()-before
        state["history_usage"] = aggregate([dict(a,arm="history") for h in histories.values() for a in h["attempts"]])
        manifest["history_sha256"] = b.sha(output / "histories.json")
        b.dump(output / "manifest.json",manifest)
        for phase,rows in (("screen",screen),("evaluation",evaluation)):
            if phase == "evaluation" and args.mode == "run":
                decision = difficulty_gate(state["screen"])
                if decision != "evaluation":
                    state["status"] = decision
                    break
            state["status"] = phase
            report(output,state)
            for i,row in enumerate(rows):
                if any(b.sha(controller_root / name) != digest or b.sha(snapshot / name) != digest
                       for name,digest in hashes.items()):
                    raise RuntimeError("Controller source changed after experiment freeze")
                print(f"[{phase} {i+1}/{len(rows)}] {row['task']} / {row['arm']} / {row['repeat']}",flush=True)
                result = trial(docker,worker,box,histories,row,args,output / phase)
                state[phase][i] = result
                report(output,state)
                print("  "+("PASS" if result["passed"] else result["status"]),flush=True)
                if result["status"] == "infrastructure_error": raise RuntimeError("Trial infrastructure failure; see saved result")
            verify_pairs(output,state[phase])
        else:
            state["status"] = "completed"
        if any(b.sha(snapshot / name) != digest for name,digest in hashes.items()):
            raise RuntimeError("Frozen controller snapshot changed during execution")
        report(output,state)
        print("Report: "+str(output / "report.md"),flush=True)
        return 0
    except KeyboardInterrupt:
        state.update(status="interrupted",error="User or process interruption")
        raise
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as exc:
        state.update(status="infrastructure_error",error=str(exc)[:700])
        print("Stopped: "+state["error"],file=sys.stderr,flush=True)
        return 2
    finally:
        report(output,state)
        if box:
            try: (output / "server.log").write_text(b.command(["docker","logs",box.name]))
            except (OSError,subprocess.SubprocessError): pass
        docker.close()


if __name__ == "__main__":
    sys.exit(main())
