#!/usr/bin/env python3
"""Three-arm, synthetic Black Box memory evaluation. Standard library only."""

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

from fixtures import TASKS, task
from grade import compare


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
ARMS = ("bare", "history", "blackbox")
WORKDIR = "/home/node/task"
WORKER_TAG = "blackbox-memory-benchmark-worker:local"
SERVER_TAG = "blackbox-memory-benchmark-server:local"
CODEX_FLAGS = ["--disable", "hooks", "--disable", "plugins", "--disable", "memories",
               "--disable", "apps", "--disable", "multi_agent",
               "--enable", "skip_host_skill_discovery", "-c", "project_doc_max_bytes=0",
               "-c", 'web_search="disabled"', "-c", 'developer_instructions=""']
# Host discovery and plugin flags do not disable Codex's five bundled system skills.
# Unknown additions fail the rendered-context audit instead of silently contaminating a run.
CODEX_FLAGS += ["-c", "skills.config=[" + ",".join(
    '{path="/home/node/.codex/skills/.system/' + name + '/SKILL.md",enabled=false}'
    for name in ("imagegen", "openai-docs", "plugin-creator", "skill-creator", "skill-installer")) + "]"]


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(argv, timeout=120, **kwargs):
    return subprocess.run([str(x) for x in argv], check=True, text=True,
                          capture_output=True, timeout=timeout, **kwargs).stdout.strip()


def schedule(tasks, arms, repeats, seed):
    pairs = [{"task": name, "repeat": repeat, "arm": arm,
              "fixture_seed": seed + repeat * 100 + TASKS.index(name)}
             for repeat in range(repeats) for name in tasks for arm in arms]
    random.Random(seed).shuffle(pairs)
    return pairs


def metrics(text):
    usage = []
    commands = {}
    turns = 0
    errors = []
    for line in text.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("type") == "turn.completed":
            turns += 1
            if isinstance(event.get("usage"), dict):
                usage.append(event["usage"])
        if event.get("type") in ("error", "turn.failed"):
            errors.append(event.get("type"))
        item = event.get("item", {})
        if event.get("type") == "item.completed" and item.get("type") == "command_execution":
            commands[item.get("id", str(len(commands)))] = item
    def total(field):
        return sum(x[field] for x in usage) if usage and all(field in x for x in usage) else None
    return {"completed_turns": turns, "error_events": errors,
            "command_calls": len(commands),
            "failed_commands": sum(x.get("exit_code") not in (0, None) for x in commands.values()),
            "input_tokens": total("input_tokens"), "cached_input_tokens": total("cached_input_tokens"),
            "output_tokens": total("output_tokens")}


def history_item(record):
    """Identical presentation for flat history and recall; provenance is kept separately."""
    return {k: record[k] for k in ("kind", "repo", "headline", "rationale", "alternatives",
                                  "openLoops", "nextAction") if record.get(k) is not None}


def public_test(fixture):
    # Public checks contain no hidden cases and cannot import the benchmark package.
    return ("import unittest\nimport solution\n\n"
            "class PublicTests(unittest.TestCase):\n"
            "    def test_example(self):\n"
            + ("        pages = " + repr(dict(fixture["public"][0]["pages"])) + "\n"
               "        actual = solution.collect_pages(lambda cursor: pages[cursor], 'wanted')\n"
               if fixture["name"] == "pagination" else
               "        actual = solution." + fixture["function"] + "(*" + repr(fixture["public"][0]["args"]) + ")\n")
            + "        self.assertEqual(actual, " + repr(fixture["public"][0]["expected"]) + ")\n"
            + "\nif __name__ == '__main__':\n    unittest.main()\n")


class Docker:
    def __init__(self, run_id):
        self.run_id = run_id
        self.owned = set()

    def create(self, image, args=(), docker_args=()):
        name = self.run_id + "-" + uuid.uuid4().hex[:8]
        command(["docker", "create", "--name", name, "--label", "blackbox.benchmark=" + self.run_id,
                 "--memory", "1536m", "--cpus", "2", "--pids-limit", "256",
                 "--cap-drop=ALL", "--security-opt=no-new-privileges", *docker_args, image, *args])
        self.owned.add(name)
        return name

    def remove(self, name):
        if name in self.owned:
            command(["docker", "rm", "-f", name])
            self.owned.remove(name)

    def close(self):
        for name in list(self.owned):
            try:
                self.remove(name)
            except (subprocess.SubprocessError, OSError):
                print("Cleanup required: docker rm -f " + name, file=sys.stderr)

    @contextlib.contextmanager
    def container(self, image, args=(), docker_args=()):
        name = self.create(image, args, docker_args)
        try:
            yield name
        finally:
            self.remove(name)


def build_images(output, codex_version):
    # Never compile into the checkout's target/: its JAR may be serving the live app.
    with tempfile.TemporaryDirectory(prefix="blackbox-benchmark-build-") as directory:
        staging = Path(directory)
        shutil.copy2(REPO / "pom.xml", staging / "pom.xml")
        shutil.copytree(REPO / "src", staging / "src")
        with (output / "maven-build.log").open("w") as log:
            subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=staging,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
        jar = staging / "target/sba-agentic-0.1.0.jar"
        jar_sha = sha(jar)
        # No VOLUME directive: server database lives only in this disposable container.
        (staging / "Dockerfile").write_text(
            'FROM eclipse-temurin:21-jre\nWORKDIR /app\nCOPY target/sba-agentic-0.1.0.jar /app/app.jar\n'
            'ENTRYPOINT ["java","-jar","/app/app.jar"]\n')
        (staging / ".dockerignore").write_text("*\n!target/\n!target/*.jar\n")
        with (output / "server-build.log").open("w") as log:
            subprocess.run(["docker", "build", "-t", SERVER_TAG, str(staging)], stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=600)
    with (output / "worker-build.log").open("w") as log:
        subprocess.run(["docker", "build", "-f", str(HERE / "Dockerfile.worker"),
                        "--build-arg", "CODEX_VERSION=" + codex_version, "-t", WORKER_TAG, str(HERE)],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    return jar_sha


class BlackBox:
    def __init__(self, docker, image):
        env = {"SBA_PORT": "8766", "SBA_BIND_ADDRESS": "0.0.0.0",
               "SBA_DATASOURCE_URL": "jdbc:sqlite:/tmp/benchmark.db",
               "SBA_ELASTICSEARCH_ENABLED": "false", "SBA_LOCAL_AI_ENABLED": "false",
               "SBA_MEMORY_EMBEDDING_ENABLED": "false", "SBA_ASK_EMBEDDING_ENABLED": "false",
               "SBA_SUMMARY_BACKEND": "local", "SBA_EDITOR_ENABLED": "false"}
        flags = ["--network", "bridge", "-p", "127.0.0.1::8766"]
        for key, value in env.items():
            flags += ["-e", key + "=" + value]
        self.name = docker.create(image, docker_args=flags)
        command(["docker", "start", self.name])
        binding = command(["docker", "port", self.name, "8766/tcp"])
        if not binding.startswith("127.0.0.1:") or binding.endswith(":8766"):
            raise RuntimeError("Server did not receive an isolated loopback port")
        self.base = "http://" + binding
        self.seconds = 0.0
        for _ in range(90):
            try:
                status = self.request("GET", "/api/status")
                if status["storage"]["events"] != 0:
                    raise RuntimeError("Disposable server must start with zero events")
                return
            except (OSError, ValueError, KeyError):
                time.sleep(1)
        raise RuntimeError("Disposable server did not become ready")

    def request(self, method, path, body=None):
        started = time.monotonic()
        request = urllib.request.Request(self.base + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None,
                                         headers={"Content-Type": "application/json",
                                                  "X-Blackbox-Purpose": "synthetic-benchmark"})
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
        self.seconds += time.monotonic() - started
        return result

    def recall(self, scope):
        query = urllib.parse.urlencode({"scope": scope, "withinHours": 168,
                                       "kinds": "decision,handoff", "limit": 50})
        result = self.request("GET", "/api/recall?" + query)
        if result.get("mode") != "lexical":
            raise RuntimeError("Expected explicitly isolated lexical recall")
        if any(item.get("repo") != scope for item in result.get("items", [])):
            raise RuntimeError("Recall leaked a different project scope")
        return result


def grade_solution(docker, image, solution, fixture, output, historical=False):
    output.mkdir(parents=True, exist_ok=True)
    execution = output / "execution"
    execution.mkdir()
    shutil.copy2(solution, execution / "solution.py")
    shutil.copy2(HERE / "grade.py", execution / "grade.py")
    cases = fixture["historical_cases" if historical else "cases"]
    dump(output / "cases.json", {"function": fixture["function"],
                               "cases": cases})
    # Only inputs enter the execution container. Expected answers stay on the host.
    dump(execution / "inputs.json", {"function": fixture["function"],
                                    "cases": [{k: v for k, v in c.items() if k not in ("expected", "name")} for c in cases]})
    with docker.container(image, ["python3", "-I", "/tmp/grading/grade.py",
                                  "/tmp/grading/solution.py", "/tmp/grading/inputs.json"],
                          ["--network", "none"]) as name:
        command(["docker", "cp", str(execution), name + ":/tmp/grading"])
        def failed_candidate(reason):
            return {"passed": False, "checks": [{"name": c["name"], "passed": False,
                                                 "error": reason} for c in cases]}
        try:
            completed = subprocess.run(["docker", "start", "-a", name], capture_output=True, text=True, timeout=25)
            state = json.loads(command(["docker", "inspect", "--format", "{{json .State}}", name]))
            if state.get("Status") != "exited" or state.get("Error"):
                raise RuntimeError("Grading container did not execute successfully")
            if completed.returncode != 0 and state["ExitCode"] == 0:
                raise RuntimeError("Docker failed to collect candidate output")
            result = (failed_candidate("Candidate process exited without a result")
                      if state["ExitCode"] != 0 else compare(cases, json.loads(completed.stdout)))
        except subprocess.TimeoutExpired:
            result = failed_candidate("Candidate exceeded grading time budget")
        except (ValueError, TypeError, AttributeError):
            result = failed_candidate("Candidate produced invalid observations")
        if [r["name"] for r in result.get("checks", [])] != [c["name"] for c in cases]:
            raise RuntimeError("Incomplete grading result")
        dump(output / "grade.json", result)
        return result


def seed_history(docker, image, box, output, seed):
    all_records = []
    for name in TASKS:
        fixture = task(name, seed - 1000 + TASKS.index(name))
        source = output / "history" / name
        source.mkdir(parents=True)
        measured = {}
        for variant in ("broken", "reference"):
            candidate = source / (variant + ".py")
            candidate.write_text(fixture["historical_" + variant])
            measured[variant] = grade_solution(docker, image, candidate, fixture,
                                               source / (variant + "-grade"), historical=True)
        if measured["broken"]["passed"] or not measured["reference"]["passed"]:
            raise RuntimeError("Historical fixture did not reproduce its claimed outcome")
        scope = "/synthetic/blackbox-memory/" + name
        common = {"source": "synthetic-benchmark", "clientSessionId": "history-" + name, "repo": scope}
        box.request("POST", "/api/decisions", dict(common, decision=fixture["lesson"],
                    rationale=fixture["rationale"], alternatives=fixture["alternatives"],
                    confidence=0.9, openLoops=[]))
        failures = [x["name"] for x in measured["broken"]["checks"] if not x["passed"]]
        box.request("POST", "/api/handoffs", dict(common,
                    contextSummary="SYNTHETIC AUTHORED HISTORY. " + fixture["lesson"]
                    + " Prior failed checks: " + ", ".join(failures)
                    + ". Corrected historical implementation passed its historical fixture.",
                    openLoops=[], nextAction="Revalidate this evidence against the current contract."))
        recalled = box.recall(scope)
        if len(recalled.get("items", [])) != 2:
            raise RuntimeError("Expected both synthetic captures to be recallable")
        dump(source / "recall.json", recalled)
        all_records.extend(history_item(item) for item in recalled["items"])
    return all_records


def check_prompt(raw):
    # This checks rendered context, rather than trusting --ephemeral as an isolation switch.
    parsed = json.loads(raw)
    lower = json.dumps(parsed).lower()
    forbidden = ["<skills_instructions>", "### available skills", "memory_summary",
                 "# agents.md instructions", "<user_instructions>"]
    found = [marker for marker in forbidden if marker in lower]
    if found:
        raise RuntimeError("Unexpected instructions in worker prompt: " + ", ".join(found))
    return {"sha256": hashlib.sha256(raw.encode()).hexdigest(), "forbidden_markers": found,
            "note": "Codex built-in system/developer instructions remain; personal context is excluded."}


def run_trial(docker, image, box, history, trial, args, output):
    started = time.monotonic()
    try:
        result = _run_trial(docker, image, box, history, trial, args, output)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        result = dict(trial, status="infrastructure_error", passed=False, recall_seconds=0,
                      error="Trial setup failed: " + str(exc)[:500], measured_agent=False)
    result["total_seconds"] = time.monotonic() - started
    directory = output / "trials" / (str(trial["repeat"]) + "-" + trial["task"] + "-" + trial["arm"])
    dump(directory / "result.json", result)
    return result


def _run_trial(docker, image, box, history, trial, args, output):
    fixture = task(trial["task"], trial["fixture_seed"])
    directory = output / "trials" / (str(trial["repeat"]) + "-" + trial["task"] + "-" + trial["arm"])
    workspace = directory / "input"
    workspace.mkdir(parents=True)
    (workspace / "README.md").write_text(fixture["spec"])
    (workspace / "solution.py").write_text(fixture["starter"])
    # docker cp owns new files as root; allow the unprivileged worker to edit this one fixture.
    (workspace / "solution.py").chmod(0o666)
    (workspace / "test_public.py").write_text(public_test(fixture))
    recall_seconds = 0.0
    selected = []
    if trial["arm"] == "history":
        selected = history
    elif trial["arm"] == "blackbox":
        started = time.monotonic()
        response = box.recall("/synthetic/blackbox-memory/" + trial["task"])
        recall_seconds = time.monotonic() - started
        dump(directory / "recall.json", response)
        selected = [history_item(item) for item in response["items"]]
    # Every arm has this same file and prompt; only its content changes.
    dump(workspace / "history.json", selected)
    prompt = ("Fix solution.py to satisfy the current contract in README.md. "
              "history.json contains historical evidence, if any; it may be irrelevant or stale. "
              "Run the public tests with python3 -m unittest -v. Hidden tests check the same contract. "
              "Only change solution.py. Do not install dependencies or use the network. "
              "Finish with a short explanation of the change and verification.")
    (directory / "prompt.txt").write_text(prompt + "\n")
    result = dict(trial, status="infrastructure_error", passed=False, recall_seconds=recall_seconds,
                  history_bytes=(workspace / "history.json").stat().st_size,
                  input_hashes={p.name: sha(p) for p in workspace.iterdir()},
                  model=args.model if args.mode == "run" else None, effort=args.effort,
                  measured_agent=args.mode == "run")
    started = time.monotonic()
    try:
        if args.mode == "smoke":
            candidate = directory / "candidate.py"
            candidate.write_text(fixture["reference"])
            result["agent_seconds"] = None
            result["metrics"] = None
        else:
            auth = Path(args.auth_file).expanduser().resolve()
            with docker.container(image, ["sleep", "infinity"],
                                  ["--network", "bridge", "--mount",
                                   "type=bind,src=" + str(auth) + ",dst=/home/node/.codex/auth.json,readonly"]) as name:
                command(["docker", "cp", str(workspace) + "/.", name + ":" + WORKDIR])
                command(["docker", "start", name])
                command(["docker", "exec", name, "test", "-r", "/home/node/.codex/auth.json"])
                context = command(["docker", "exec", name, "codex", *CODEX_FLAGS,
                                   "debug", "prompt-input", prompt], timeout=60)
                (directory / "rendered-context.json").write_text(context)
                result["prompt_audit"] = check_prompt(context)
                invocation = ["docker", "exec", name, "codex", "exec", *CODEX_FLAGS,
                              "--ignore-user-config", "--ignore-rules", "--ephemeral",
                              "--skip-git-repo-check", "--sandbox", "danger-full-access",
                              "--model", args.model, "-c", 'model_reasoning_effort="' + args.effort + '"',
                              "--json", "-o", WORKDIR + "/answer.txt", prompt]
                agent_start = time.monotonic()
                with (directory / "events.jsonl").open("w") as stdout, (directory / "stderr.log").open("w") as stderr:
                    completed = subprocess.run(invocation, stdout=stdout, stderr=stderr, timeout=args.timeout)
                result["agent_seconds"] = time.monotonic() - agent_start
                result["metrics"] = metrics((directory / "events.jsonl").read_text())
                result["worker_exit_code"] = completed.returncode
                if completed.returncode != 0 or result["metrics"]["completed_turns"] < 1 or result["metrics"]["error_events"]:
                    raise RuntimeError("Worker failed; see events.jsonl and stderr.log")
                candidate = directory / "candidate.py"
                command(["docker", "cp", name + ":" + WORKDIR + "/solution.py", str(candidate)])
                command(["docker", "cp", name + ":" + WORKDIR + "/answer.txt", str(directory / "answer.txt")])
        grade = grade_solution(docker, image, candidate, fixture, directory / "grading")
        result.update(status="graded", passed=grade["passed"], checks=grade["checks"],
                      candidate_sha256=sha(candidate))
    except subprocess.TimeoutExpired:
        result.update(status="timeout", error="Worker or grading time budget exceeded")
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        result["error"] = str(exc)[:500]
    result["total_seconds"] = time.monotonic() - started
    dump(directory / "result.json", result)
    return result


def report(output, results, mode):
    summary = {"mode": mode, "is_model_evidence": mode == "run", "arms": {}, "results": results}
    lines = ["# Black Box memory benchmark", "",
             "Synthetic pilot; no significance or generalization claim. "
             + ("Real model runs." if mode == "run" else "Infrastructure smoke only; reference code replaces the model."),
             "", "| Arm | Passed / scheduled | Infrastructure errors / timeouts | Input tokens | Output tokens |",
             "| --- | --- | --- | --- | --- |"]
    for arm in ARMS:
        rows = [r for r in results if r["arm"] == arm]
        if not rows:
            continue
        def token_total(field):
            values = [(r.get("metrics") or {}).get(field) for r in rows]
            return sum(values) if all(v is not None for v in values) else None
        data = {"scheduled": len(rows), "passed": sum(r["passed"] for r in rows),
                "errors": sum(r["status"] != "graded" for r in rows),
                "input_tokens": token_total("input_tokens"), "output_tokens": token_total("output_tokens")}
        summary["arms"][arm] = data
        lines.append(f"| {arm} | {data['passed']} / {len(rows)} | {data['errors']} | "
                     f"{data['input_tokens'] if data['input_tokens'] is not None else 'unknown'} | "
                     f"{data['output_tokens'] if data['output_tokens'] is not None else 'unknown'} |")
    lines += ["", "| Task / repeat | Arm | Outcome | Agent seconds | Recall milliseconds | Failed checks |",
              "| --- | --- | --- | --- | --- | --- |"]
    for row in sorted(results, key=lambda r: (r["task"], r["repeat"], r["arm"])):
        failures = ", ".join(x["name"] for x in row.get("checks", []) if not x["passed"])
        seconds = row.get("agent_seconds")
        lines.append(f"| {row['task']} / {row['repeat']} | {row['arm']} | "
                     f"{'pass' if row['passed'] else ('fail' if row['status'] == 'graded' else row['status'])} | "
                     f"{round(seconds, 2) if seconds is not None else 'n/a'} | "
                     f"{row['recall_seconds'] * 1000:.2f} | {failures} |")
    lines += ["", "Interpretation limits:", "",
              "- Histories are authored synthetic fixtures with measured historical outcomes, not agent-learned experience.",
              "- Black Box uses exact project-scoped lexical recall; this does not evaluate semantic retrieval.",
              "- Recall is supplied before the task, so this tests context utility, not whether an agent chooses to call MCP.",
              "- Flat history has the same source evidence across all fixture projects; recall filters it by project.",
              "- Tokens include supplied context. Retrieval latency is recorded separately. No dollar-price estimate is inferred.",
              "- Public/hidden checks are synthetic and small; changed-contract tests resistance to stale advice.",
              "- Failed command counts are observable tool failures, not a semantic measure of repeated mistakes.",
              "- Infrastructure failures remain in scheduled denominators. Inspect them separately from graded failures.",
              "- One repetition is a smoke-sized pilot. Use more repetitions and harder held-out tasks before conclusions."]
    dump(output / "report.json", summary)
    (output / "report.md").write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "smoke", "run"), nargs="?", default="plan")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--model", help="Required for model runs; no silent model substitution")
    parser.add_argument("--effort", choices=("low", "medium", "high", "xhigh"), default="medium")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--tasks", nargs="+", choices=TASKS, default=list(TASKS))
    parser.add_argument("--arms", nargs="+", choices=ARMS, default=list(ARMS))
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--codex-version", default="0.155.0")
    parser.add_argument("--skip-build", action="store_true", help="Reuse explicitly recorded local image IDs")
    parser.add_argument("--auth-file", default=str(Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))) / "auth.json"))
    args = parser.parse_args()
    if args.repeats < 1 or args.timeout < 1 or len(set(args.tasks)) != len(args.tasks) or len(set(args.arms)) != len(args.arms):
        parser.error("Use positive repeats/timeouts and unique tasks/arms")
    trials = schedule(args.tasks, args.arms, args.repeats, args.seed)
    if args.mode == "plan":
        print(json.dumps({"trials": trials, "model": args.model, "effort": args.effort,
                          "mode": "dry-run", "changes": "none", "arms": list(ARMS)}, indent=2))
        return 0
    if args.mode == "run" and (not args.model or not Path(args.auth_file).expanduser().is_file()):
        parser.error("run requires --model and an existing signed-in Codex auth file")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    run_id = "bb-memory-" + uuid.uuid4().hex[:12]
    output = (args.output or REPO / "target/benchmarks" / run_id).resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    docker = Docker(run_id)
    results = [dict(t, status="not_run", passed=False, recall_seconds=0,
                    error="Not attempted", measured_agent=False) for t in trials]
    report(output, results, args.mode)
    box = None
    try:
        print("Building isolated images..." if not args.skip_build else "Using existing benchmark images...", flush=True)
        jar_sha = None if args.skip_build else build_images(output, args.codex_version)
        worker = command(["docker", "image", "inspect", "--format", "{{.Id}}", WORKER_TAG])
        server = command(["docker", "image", "inspect", "--format", "{{.Id}}", SERVER_TAG])
        version = command(["docker", "run", "--rm", "--network", "none", worker, "codex", "--version"])
        manifest = {"run_id": run_id, "mode": args.mode, "model": args.model, "effort": args.effort,
                    "seed": args.seed, "schedule": trials, "worker_image": worker, "server_image": server,
                    "codex_version": version, "jar_sha256": jar_sha,
                    "server_built_from_current_source": not args.skip_build,
                    "git_head": command(["git", "rev-parse", "HEAD"], cwd=REPO),
                    "git_status": command(["git", "status", "--short"], cwd=REPO),
                    "benchmark_hashes": {p.name: sha(p) for p in HERE.glob("*.py")},
                    "timeout_seconds": args.timeout}
        dump(output / "manifest.json", manifest)
        box = BlackBox(docker, server)
        history = seed_history(docker, worker, box, output, args.seed)
        dump(output / "history.json", history)
        for index, trial in enumerate(trials, 1):
            print(f"[{index}/{len(trials)}] {trial['task']} / {trial['arm']} / repeat {trial['repeat']}", flush=True)
            result = run_trial(docker, worker, box, history, trial, args, output)
            results[index - 1] = result
            report(output, results, args.mode)
            print("  " + ("PASS" if result["passed"] else result["status"]), flush=True)
            # Auth/startup errors are not task performance; do not burn the rest of the pilot budget.
            if result["status"] == "infrastructure_error":
                break
        report(output, results, args.mode)
        print("Report: " + str(output / "report.md"), flush=True)
        return 0 if all(r["passed"] for r in results) else 1
    finally:
        report(output, results, args.mode)
        if box:
            try:
                (output / "server.log").write_text(command(["docker", "logs", box.name]))
            except (subprocess.SubprocessError, OSError):
                pass
        docker.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
