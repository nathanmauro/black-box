"""Offline CLI end-to-end tests: no real credentials, AWS, or Linear writes."""

import contextlib
import copy
import fcntl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

SCRIPT = Path(__file__).with_name("blackbox_linear.py")
FIXTURE = Path(__file__).with_name("recall-example.json")
TEAM = "df8f8e42-ff3c-4b6a-89f8-fc4e9e83179a"
PROJECT = "1b16c530-ed72-488f-9d64-1c6e076415b4"
FAKE_KEY = "offline-fake-key-never-a-real-credential"


class FakeLinear:
    def __init__(self):
        self.issues = {}
        self.calls = []
        self.creates = 0
        self.failure = None
        self.fail_create_number = 1
        self.schema_has_id = True
        self.lookup_more = False
        self.project_team = TEAM
        self.recall = json.loads(FIXTURE.read_text())
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, data, status=200):
                raw = json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                    self.wfile.write(raw)

            def do_GET(self):
                fixture.calls.append(("GET", self.path))
                self.respond(fixture.recall)

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                query, variables = payload["query"], payload["variables"]
                fixture.calls.append((query, variables))
                if self.headers.get("Authorization") != FAKE_KEY:
                    self.respond({"error": "auth failed"}, 401)
                    return
                if "query Teams" in query:
                    self.respond({"data": {"teams": {"nodes": [{"id": TEAM, "name": "Offline", "key": "OFF"}], "pageInfo": {"hasNextPage": False}}}})
                elif "query Team(" in query:
                    self.respond({"data": {"team": {"id": TEAM, "name": "Offline", "key": "OFF"}}})
                elif "query CreateSchema" in query:
                    names = ["teamId", "title", "description"] + (["id"] if fixture.schema_has_id else [])
                    self.respond({"data": {"__type": {"inputFields": [{"name": n} for n in names]}}})
                elif "query Project(" in query:
                    self.respond({"data": {"project": {"id": PROJECT, "name": "Black Box", "teams": {"nodes": [{"id": fixture.project_team}], "pageInfo": {"hasNextPage": False}}}}})
                elif "query FindCandidate" in query:
                    wanted = variables["filter"]
                    found = [i for i in fixture.issues.values() if i["team"]["id"] == wanted["team"]["id"]["eq"] and wanted["description"]["contains"] in i["description"]]
                    self.respond({"data": {"issues": {"nodes": found, "pageInfo": {"hasNextPage": fixture.lookup_more}}}})
                elif "query Reconcile" in query:
                    self.respond({"data": {"issue": fixture.issues.get(variables["id"])}})
                elif "mutation CreateIssue" in query:
                    fixture.creates += 1
                    value = variables["input"]
                    mode = fixture.failure if fixture.creates == fixture.fail_create_number else None
                    if mode in ("http", "graphql", "not-created", "malformed"):
                        if mode == "http":
                            self.respond({"secret": FAKE_KEY}, 503)
                        elif mode == "graphql":
                            self.respond({"errors": [{"message": FAKE_KEY}], "data": {"issueCreate": {"success": True}}})
                        elif mode == "malformed":
                            self.respond({"data": {"issueCreate": {"success": True, "issue": {"id": value["id"]}}}})
                        else:
                            self.respond({"data": {"issueCreate": {"success": False}}})
                        return
                    self.server.testcase.assertEqual(uuid.UUID(value["id"]).version, 4)
                    issue = {"id": value["id"], "identifier": "OFF-" + str(fixture.creates), "url": "https://linear.app/example/issue/OFF-1", "description": value["description"], "team": {"id": value["teamId"]}}
                    fixture.issues[issue["id"]] = issue
                    if mode == "disconnect":
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    if mode == "timeout":
                        time.sleep(0.25)
                    if mode == "partial-success":
                        self.respond({"data": {"issueCreate": {"success": True, "issue": issue}}, "errors": [{"message": "partial"}]})
                    else:
                        self.respond({"data": {"issueCreate": {"success": True, "issue": issue}}})
                else:
                    self.respond({"errors": [{"message": "unexpected query"}]})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self, testcase):
        self.server.testcase = testcase
        self.thread.start()
        return "http://127.0.0.1:" + str(self.server.server_port)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class CliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.review = self.root / "review.json"
        self.state = self.root / "state.sqlite3"
        self.fake = FakeLinear()
        self.endpoint = self.fake.start(self)
        self.env = {**os.environ, "LINEAR_API_KEY": FAKE_KEY}

    def tearDown(self):
        self.fake.close()
        self.directory.cleanup()

    def run_cli(self, *arguments, ok=True, env=None):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, arguments)], env=env or self.env,
                                capture_output=True, text=True, timeout=10)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn(FAKE_KEY, result.stdout + result.stderr)
        return result

    def prepare(self, fixture=None):
        source = FIXTURE
        if fixture:
            source = self.root / "recall.json"
            source.write_text(json.dumps(fixture))
        self.run_cli("preview", "--input", source, "--output", self.review)
        data = json.loads(self.review.read_text())
        for candidate in data["candidates"]:
            candidate["relevanceConfirmed"] = True
            candidate["relevanceNote"] = "This fixture remains unresolved and is selected for the offline test."
            candidate["acceptanceCriteria"] = ["An interrupted request is recovered once.", "A repeated request creates no duplicate."]
        self.review.write_text(json.dumps(data))
        self.ids = [c["id"] for c in data["candidates"]]
        return data

    def publish(self, *extras, ok=True, apply=True, ids=None):
        arguments = ["publish", "--candidates", self.review, "--state", self.state, "--team", TEAM,
                     "--endpoint", self.endpoint]
        for cid in ids or self.ids:
            arguments.extend(["--select", cid])
        if apply:
            arguments.append("--apply")
        return self.run_cli(*arguments, *extras, ok=ok)

    def test_preview_deduplicates_preserves_sources_and_never_claims_relevance(self):
        result = self.run_cli("preview", "--input", FIXTURE)
        data = json.loads(result.stdout)
        self.assertEqual(len(data["candidates"]), 1)
        c = data["candidates"][0]
        self.assertFalse(c["relevanceConfirmed"])
        self.assertEqual(c["acceptanceCriteria"], [])
        self.assertEqual({e["field"] for e in c["evidence"]}, {"nextAction", "openLoops[0]"})
        self.assertEqual(c["evidence"][0]["observedAt"], "2026-09-08T12:00:00Z")
        self.assertEqual(c["evidence"][0]["eventId"], "example-event-1")
        self.assertEqual(len(self.fake.calls), 0)

    def test_live_recall_path_and_stable_identity(self):
        result = self.run_cli("preview", "--blackbox-url", self.endpoint, "--scope", "/repos/example")
        other = self.run_cli("preview", "--input", FIXTURE)
        self.assertEqual(json.loads(result.stdout)["candidates"], json.loads(other.stdout)["candidates"])
        self.assertIn("/api/recall?scope=%2Frepos%2Fexample", self.fake.calls[0][1])

    def test_no_action_invalid_evidence_and_truncated_warning(self):
        fixture = copy.deepcopy(self.fake.recall)
        fixture["truncated"] = True
        fixture["items"][0]["nextAction"] = "None — merged to default branch."
        fixture["items"][0]["openLoops"] = []
        fixture["items"].append({"eventId": "missing-date", "nextAction": "Do something"})
        data = self.prepare(fixture)
        self.assertEqual(data["candidates"], [])
        self.assertTrue(any("truncated" in w for w in data["warnings"]))
        self.assertTrue(any("Skipped invalid" in w for w in data["warnings"]))

    def test_preview_refuses_overwrite(self):
        self.prepare()
        before = self.review.read_text()
        self.run_cli("preview", "--input", FIXTURE, "--output", self.review, ok=False)
        self.assertEqual(self.review.read_text(), before)

    def test_dry_run_needs_no_credentials_and_prints_exact_body(self):
        self.prepare()
        result = self.publish(apply=False)
        plan = json.loads(result.stdout)["plannedIssues"][0]
        self.assertIn("2026-09-08T12:00:00Z", plan["description"])
        self.assertEqual(len(self.fake.calls), 0)
        self.assertFalse(self.state.exists())
        self.env.pop("LINEAR_API_KEY")
        self.publish(apply=False)

    def test_explicit_selection_and_review_required(self):
        data = self.prepare()
        self.publish(ids=["not-a-candidate"], ok=False)
        data["candidates"][0]["relevanceConfirmed"] = False
        self.review.write_text(json.dumps(data))
        self.publish(ok=False)
        data["candidates"][0]["relevanceConfirmed"] = True
        data["candidates"][0]["acceptanceCriteria"] = []
        self.review.write_text(json.dumps(data))
        self.publish(ok=False)
        self.assertEqual(len(self.fake.calls), 0)

    def test_publish_once_repeat_no_duplicate_exact_dry_run(self):
        self.prepare()
        plan = json.loads(self.publish(apply=False).stdout)["plannedIssues"][0]
        self.publish()
        result = self.publish()
        self.assertIn("already-published", result.stdout)
        self.assertEqual(self.fake.creates, 1)
        payload = next(v["input"] for q, v in self.fake.calls if "mutation" in q)
        self.assertEqual(payload["description"], plan["description"])
        self.assertEqual(payload["title"], plan["title"])
        self.assertNotIn("stateId", payload)
        self.assertNotIn("createdAt", payload)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o600)

    def test_remote_marker_recovers_missing_state(self):
        self.prepare()
        self.publish()
        self.state.unlink()
        self.assertIn("recovered-existing", self.publish().stdout)
        self.assertEqual(self.fake.creates, 1)

    def test_project_is_verified_and_passed_to_creation(self):
        self.prepare()
        plan = json.loads(self.publish("--project", PROJECT, apply=False).stdout)["plannedIssues"][0]
        self.assertEqual(plan["projectId"], PROJECT)
        self.publish("--project", PROJECT)
        payload = next(v["input"] for q, v in self.fake.calls if "mutation" in q)
        self.assertEqual(payload["projectId"], PROJECT)
        self.assertIn("already-published", self.publish("--project", PROJECT).stdout)
        self.assertEqual(self.fake.creates, 1)

    def test_project_in_another_team_blocks_creation(self):
        self.prepare()
        self.fake.project_team = str(uuid.uuid4())
        self.publish("--project", PROJECT, ok=False)
        self.assertEqual(self.fake.creates, 0)
        self.assertFalse(self.state.exists())

    def test_remote_lookup_incomplete_fails_closed(self):
        self.prepare()
        self.fake.lookup_more = True
        self.publish(ok=False)
        self.assertEqual(self.fake.creates, 0)

    def test_remote_multiple_markers_fail_closed(self):
        self.prepare()
        self.publish()
        duplicate = copy.deepcopy(next(iter(self.fake.issues.values())))
        duplicate["id"] = str(uuid.uuid4())
        self.fake.issues[duplicate["id"]] = duplicate
        self.state.unlink()
        self.publish(ok=False)
        self.assertEqual(self.fake.creates, 1)

    def test_timeout_after_create_reconciles_without_second_mutation(self):
        self.prepare()
        self.fake.failure = "timeout"
        self.publish("--timeout", "0.05", ok=False)
        self.assertIn("reconciled", self.publish().stdout)
        self.assertEqual(self.fake.creates, 1)

    def test_partial_graphql_success_reconciles(self):
        self.prepare()
        self.fake.failure = "partial-success"
        self.publish(ok=False)
        self.assertIn("reconciled", self.publish().stdout)
        self.assertEqual(self.fake.creates, 1)

    def test_http_graphql_rejection_malformed_and_absent_outcome_never_retry(self):
        self.prepare()
        for failure in ("http", "graphql", "not-created", "malformed", "disconnect"):
            with self.subTest(failure=failure):
                if self.state.exists():
                    self.state.unlink()
                self.fake.issues.clear()
                self.fake.creates = 0
                self.fake.failure = failure
                self.publish(ok=False)
                self.publish(ok=failure == "disconnect")
                self.assertEqual(self.fake.creates, 1)

    def test_partial_batch_leaves_first_published_and_second_pending(self):
        fixture = copy.deepcopy(self.fake.recall)
        fixture["items"][0]["openLoops"] = ["Verify a second unresolved behavior"]
        self.prepare(fixture)
        self.fake.failure = "disconnect"
        self.fake.fail_create_number = 2
        self.publish(ok=False)
        result = self.publish()
        self.assertIn("already-published", result.stdout)
        self.assertIn("reconciled", result.stdout)
        self.assertEqual(self.fake.creates, 2)

    def test_changed_payload_not_duplicated(self):
        data = self.prepare()
        self.publish()
        data["candidates"][0]["title"] = "A changed review title"
        self.review.write_text(json.dumps(data))
        self.publish(ok=False)
        self.assertEqual(self.fake.creates, 1)

    def test_source_strings_are_data_not_commands_or_graphql(self):
        fixture = copy.deepcopy(self.fake.recall)
        marker_path = self.root / "must-not-exist"
        action = f"$(touch {marker_path}) `touch {marker_path}`\n```\nmutation {{ deleteEverything }}\n```"
        fixture["items"][0]["nextAction"] = action
        fixture["items"][0]["openLoops"] = []
        data = self.prepare(fixture)
        data["candidates"][0]["title"] = "Review the malicious fixture safely"
        self.review.write_text(json.dumps(data))
        self.publish()
        self.assertFalse(marker_path.exists())
        query, variables = next((q, v) for q, v in self.fake.calls if "mutation" in q)
        self.assertNotIn("deleteEverything", query)
        self.assertIn(action, variables["input"]["description"])
        self.assertIn("````text", variables["input"]["description"])

    def test_schema_missing_id_or_wrong_team_blocks_create(self):
        self.prepare()
        self.fake.schema_has_id = False
        self.publish(ok=False)
        self.fake.schema_has_id = True
        self.publish("--team", str(uuid.uuid4()), ok=False)
        self.assertEqual(self.fake.creates, 0)

    def test_foreign_endpoint_rejected_before_credentials_or_network(self):
        self.prepare()
        self.publish("--endpoint", "https://example.invalid/graphql", ok=False)
        self.assertEqual(len(self.fake.calls), 0)

    def test_lock_prevents_concurrent_publication(self):
        self.prepare()
        with open(str(self.state) + ".lock", "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.publish(ok=False)
        self.assertEqual(self.fake.creates, 0)

    def test_teams_is_read_only(self):
        result = self.run_cli("teams", "--endpoint", self.endpoint)
        self.assertEqual(json.loads(result.stdout)["teams"][0]["id"], TEAM)
        self.assertEqual(self.fake.creates, 0)

    def test_aws_secret_reference_uses_argv_and_never_prints_key(self):
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        args_file = self.root / "aws-args.json"
        fake_aws = fake_bin / "aws"
        fake_aws.write_text("#!" + sys.executable + "\nimport json, os, sys\n"
                            "open(os.environ['TEST_AWS_ARGS'], 'w').write(json.dumps(sys.argv[1:]))\n"
                            "print(json.dumps({'api-key': " + repr(FAKE_KEY) + "}))\n")
        fake_aws.chmod(0o700)
        env = {**self.env, "PATH": str(fake_bin) + os.pathsep + self.env["PATH"], "TEST_AWS_ARGS": str(args_file)}
        env.pop("LINEAR_API_KEY")
        marker_path = self.root / "never-executed"
        reference = f"example/secret-$(touch {marker_path})"
        self.run_cli("teams", "--endpoint", self.endpoint, "--secret-id", reference,
                     "--secret-key", "api-key", "--region", "us-east-2", "--profile", "fixture", env=env)
        arguments = json.loads(args_file.read_text())
        self.assertEqual(arguments[arguments.index("--secret-id") + 1], reference)
        self.assertEqual(arguments[arguments.index("--region") + 1], "us-east-2")
        self.assertEqual(arguments[arguments.index("--profile") + 1], "fixture")
        self.assertFalse(marker_path.exists())

    def test_unselected_candidate_is_not_published(self):
        fixture = copy.deepcopy(self.fake.recall)
        fixture["items"][0]["openLoops"] = ["An unrelated candidate to leave alone"]
        self.prepare(fixture)
        self.publish(ids=[self.ids[0]])
        self.assertEqual(self.fake.creates, 1)
        description = next(iter(self.fake.issues.values()))["description"]
        self.assertNotIn("An unrelated candidate", description)


if __name__ == "__main__":
    unittest.main()
