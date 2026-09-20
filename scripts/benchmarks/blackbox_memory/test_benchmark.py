import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import benchmark
from fixtures import TASKS, task
from grade import compare, grade


def module(code):
    result = types.ModuleType("fixture")
    exec(code, result.__dict__)
    return result


class FixtureTests(unittest.TestCase):
    def test_references_pass_and_starters_reproduce_failures(self):
        for name in TASKS:
            for seed in (1, 42, 91):
                with self.subTest(name=name, seed=seed):
                    fixture = task(name, seed)
                    self.assertTrue(grade(module(fixture["reference"]), fixture["function"], fixture["cases"])["passed"])
                    self.assertFalse(grade(module(fixture["starter"]), fixture["function"], fixture["cases"])["passed"])

    def test_history_is_reproduced_under_its_own_contract(self):
        for name in TASKS:
            fixture = task(name, 23)
            self.assertFalse(grade(module(fixture["historical_broken"]), fixture["function"], fixture["historical_cases"])["passed"])
            self.assertTrue(grade(module(fixture["historical_reference"]), fixture["function"], fixture["historical_cases"])["passed"])

    def test_old_contract_success_is_current_contract_failure(self):
        fixture = task("changed-contract", 12)
        candidate = module(fixture["historical_reference"])
        self.assertTrue(grade(candidate, fixture["function"], fixture["historical_cases"])["passed"])
        self.assertFalse(grade(candidate, fixture["function"], fixture["cases"])["passed"])

    def test_pagination_repeated_cursor_is_a_measured_failure(self):
        fixture = task("pagination", 3)
        candidate = module("def collect_pages(fetch_page, kind):\n    fetch_page(None)\n    return fetch_page(None)\n")
        results = grade(candidate, fixture["function"], fixture["cases"])
        self.assertFalse(results["passed"])
        self.assertIn("Repeated cursor", results["checks"][0]["error"])

    def test_mutating_inputs_cannot_pass_by_return_value_alone(self):
        fixture = task("changed-contract", 3)
        candidate = module("def deduplicate(events):\n    events.clear()\n    return []\n")
        results = grade(candidate, fixture["function"], fixture["cases"])
        self.assertIn("Input was mutated", results["checks"][0]["error"])

    def test_public_tests_do_not_include_hidden_expectations(self):
        for name in TASKS:
            fixture = task(name, 3)
            public = benchmark.public_test(fixture)
            compile(public, "test_public.py", "exec")
            self.assertNotIn("historical", public)
            for case in fixture["cases"][len(fixture["public"]):]:
                self.assertNotIn(case["name"], public)


class ExperimentTests(unittest.TestCase):
    def test_candidate_syntax_errors_and_stdout_do_not_break_grading_protocol(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = task("rounding", 4)
            inputs = [{k: v for k, v in c.items() if k not in ("expected", "name")} for c in fixture["cases"]]
            benchmark.dump(root / "inputs.json", {"function": fixture["function"], "cases": inputs})
            for code, expected in [("def invalid syntax", False),
                                   ("def invoice_total(lines):\n    return set()\n", False),
                                   ('print("debug during import")\n' + fixture["reference"], True)]:
                (root / "solution.py").write_text(code)
                result = subprocess.run([sys.executable, str(benchmark.HERE / "grade.py"),
                                         str(root / "solution.py"), str(root / "inputs.json")],
                                        capture_output=True, text=True, check=True)
                self.assertEqual(compare(fixture["cases"], json.loads(result.stdout))["passed"], expected)

    def test_host_comparison_ignores_candidate_claim_of_success(self):
        result = compare([{"name": "real-check", "expected": 42}],
                         [{"passed": True, "actual": 0, "unchanged": True}])
        self.assertFalse(result["passed"])

    def test_retrieval_setup_error_is_recorded_as_trial_result(self):
        class BrokenBox:
            def recall(self, scope):
                raise OSError("fixture server unavailable")
        with tempfile.TemporaryDirectory() as directory:
            args = types.SimpleNamespace(mode="smoke", model=None, effort="medium")
            trial = {"task": "pagination", "repeat": 0, "arm": "blackbox", "fixture_seed": 42}
            result = benchmark.run_trial(None, "unused", BrokenBox(), [], trial, args, Path(directory))
            self.assertEqual(result["status"], "infrastructure_error")
            self.assertTrue((Path(directory) / "trials/0-pagination-blackbox/result.json").is_file())
            self.assertIn("total_seconds", result)

    def test_randomized_schedule_is_reproducible_and_paired(self):
        rows = benchmark.schedule(TASKS, benchmark.ARMS, 3, 42)
        self.assertEqual(rows, benchmark.schedule(TASKS, benchmark.ARMS, 3, 42))
        self.assertNotEqual(rows, benchmark.schedule(TASKS, benchmark.ARMS, 3, 43))
        self.assertEqual(len(rows), 27)
        for name in TASKS:
            for repeat in range(3):
                pair = [r for r in rows if r["task"] == name and r["repeat"] == repeat]
                self.assertEqual({r["arm"] for r in pair}, set(benchmark.ARMS))
                self.assertEqual(len({r["fixture_seed"] for r in pair}), 1)

    def test_absent_usage_is_unknown_not_free(self):
        self.assertIsNone(benchmark.metrics("")["input_tokens"])
        self.assertIsNone(benchmark.metrics('{"type":"turn.completed"}')["output_tokens"])

    def test_metrics_do_not_double_count_streamed_commands(self):
        item = {"id": "c1", "type": "command_execution", "exit_code": 1}
        events = [{"type": "item.started", "item": item},
                  {"type": "item.completed", "item": item},
                  {"type": "item.completed", "item": item},
                  {"type": "turn.completed", "usage": {"input_tokens": 80, "output_tokens": 20}}]
        result = benchmark.metrics("\n".join(json.dumps(e) for e in events))
        self.assertEqual(result["command_calls"], 1)
        self.assertEqual(result["failed_commands"], 1)
        self.assertEqual(result["input_tokens"], 80)
        self.assertIsNone(result["cached_input_tokens"])

    def test_prompt_audit_rejects_personal_context(self):
        for marker in ("<skills_instructions>", "### Available skills", "MEMORY_SUMMARY",
                       "# AGENTS.md instructions", "<user_instructions>"):
            with self.subTest(marker=marker), self.assertRaises(RuntimeError):
                benchmark.check_prompt(json.dumps({"content": marker}))
        self.assertEqual(benchmark.check_prompt('{"content":"standard Codex context"}')["forbidden_markers"], [])

    def test_recall_fails_closed_on_cross_project_content(self):
        box = object.__new__(benchmark.BlackBox)
        box.request = lambda *args: {"mode": "lexical", "items": [{"repo": "/wrong"}]}
        with self.assertRaises(RuntimeError):
            box.recall("/synthetic/test")

    def test_recall_rejects_unexpected_semantic_backend(self):
        box = object.__new__(benchmark.BlackBox)
        box.request = lambda *args: {"mode": "semantic", "items": []}
        with self.assertRaises(RuntimeError):
            box.recall("/synthetic/test")

    def test_history_and_recall_use_identical_presentation(self):
        record = {"kind": "decision", "repo": "/synthetic/test", "headline": "A choice",
                  "rationale": "A reason", "eventId": "random-id", "observedAt": "now", "score": 0.9}
        rendered = benchmark.history_item(record)
        self.assertNotIn("eventId", rendered)
        self.assertNotIn("score", rendered)
        self.assertEqual(rendered["rationale"], "A reason")

    def test_cleanup_only_removes_owned_containers_even_on_error(self):
        docker = benchmark.Docker("test-owned")
        with patch.object(benchmark, "command", return_value="id") as run:
            with self.assertRaises(ValueError):
                with docker.container("image") as name:
                    raise ValueError("fixture")
            self.assertEqual(docker.owned, set())
            run.assert_called_with(["docker", "rm", "-f", name])
            calls = run.call_count
            docker.remove("someone-elses-container")
            self.assertEqual(run.call_count, calls)

    def test_report_keeps_failures_and_missing_usage_in_denominator(self):
        with tempfile.TemporaryDirectory() as directory:
            rows = [{"task": "rounding", "repeat": 0, "arm": "bare", "passed": True,
                     "status": "graded", "recall_seconds": 0, "metrics": {"input_tokens": 50, "output_tokens": 10}},
                    {"task": "rounding", "repeat": 1, "arm": "bare", "passed": False,
                     "status": "timeout", "recall_seconds": 0}]
            benchmark.report(Path(directory), rows, "run")
            data = json.loads((Path(directory) / "report.json").read_text())
            self.assertEqual(data["arms"]["bare"]["scheduled"], 2)
            self.assertEqual(data["arms"]["bare"]["passed"], 1)
            self.assertEqual(data["arms"]["bare"]["errors"], 1)
            self.assertIsNone(data["arms"]["bare"]["input_tokens"])


if __name__ == "__main__":
    unittest.main()
