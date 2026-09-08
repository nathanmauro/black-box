#!/usr/bin/env python3
"""Entire acceptance CLI against a temporary HTTP fixture; AWS is always fake."""
import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit

SPEC = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("lightsail_verify.py"))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)
ACCOUNT = "123456789012"
TOKEN = "TestSecretMustNeverAppearInOutput0123456789"
URL = "https://blackbox-cloud.fixture.us-east-2.cs.amazonlightsail.com"
ARN = "arn:aws:secretsmanager:us-east-2:" + ACCOUNT + ":secret:blackbox-api-fixture"


class FakeAws:
    def __init__(self):
        self.account, self.version, self.url = ACCOUNT, 1, URL
        self.calls = []

    def __call__(self, *command):
        self.calls.append(command)
        if command[:2] == ("sts", "get-caller-identity"):
            return {"Account": self.account}
        if command[:2] == ("cloudformation", "describe-stacks"):
            return {"Stacks": [{"StackId": "arn:aws:cloudformation:us-east-2:" + ACCOUNT + ":stack/blackbox-cloud/fixture",
                                "StackStatus": "CREATE_COMPLETE",
                                "Tags": [{"Key": "Project", "Value": "black-box"},
                                         {"Key": "Purpose", "Value": "shared-container-prototype"}],
                                "Outputs": [{"OutputKey": key, "OutputValue": value} for key, value in
                                            {"Url": self.url, "ServiceName": "blackbox-cloud", "ApiSecretArn": ARN}.items()]}]}
        if command[:2] == ("lightsail", "get-container-services"):
            return {"containerServices": [{"containerServiceName": "blackbox-cloud", "url": self.url,
                                           "currentDeployment": {"version": self.version, "state": "ACTIVE", "containers": {
                                               "blackbox": {"image": ":blackbox-cloud.release." + str(self.version),
                                                            "environment": {"SECRET_MUST_NOT_BE_STORED": TOKEN}}}}}]}
        if command[:2] == ("secretsmanager", "get-secret-value"):
            return {"SecretString": json.dumps({"token": TOKEN})}
        raise AssertionError("Unexpected AWS command")


class AcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="blackbox-proof-fixture-")
        self.state = Path(self.directory.name) / "run.json"
        self.aws, self.items, self.posts, self.paths = FakeAws(), [], [], []
        self.fail_capture, self.corrupt_recall, self.redirect, self.sse = None, False, False, False
        self.rpc_methods, self.rpc_headers = [], []
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, body=None, headers=None):
                data = json.dumps(body).encode() if body is not None else b""
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                fixture.paths.append(self.path)
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    return self.reply(401)
                if fixture.redirect:
                    return self.reply(302, headers={"Location": "/credential-leak-target"})
                scope = parse_qs(urlsplit(self.path).query).get("scope", [None])[0]
                items = [dict(item) for item in fixture.items if item["repo"] == scope]
                if fixture.corrupt_recall and items:
                    items[0]["headline"] = "incorrect content"
                self.reply(200, {"items": items, "count": len(items)})

            def do_POST(self):
                fixture.paths.append(self.path)
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    return self.reply(401)
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/api/decisions":
                    # Journal must exist before even receiving this non-idempotent request.
                    journal = json.loads(fixture.state.read_text())
                    if journal["phase"] != "pending" or journal["decision"] != body["decision"]:
                        return self.reply(400)
                    fixture.posts.append(body)
                    if fixture.fail_capture != "dropped":
                        fixture.items.append({"eventId": "fixture-event-1", "source": body["source"], "kind": "decision",
                                              "clientSessionId": body["clientSessionId"], "repo": body["repo"], "headline": body["decision"]})
                    if fixture.fail_capture in ("committed", "dropped"):
                        return self.reply(500, {"privateServerError": TOKEN})
                    if fixture.fail_capture == "bad-json":
                        return self.reply(200, {"unexpected": TOKEN})
                    return self.reply(200, {"eventId": "fixture-event-1", "source": body["source"],
                                            "clientSessionId": body["clientSessionId"], "eventType": "decision"})
                fixture.rpc_methods.append(body["method"])
                fixture.rpc_headers.append(dict(self.headers.items()))
                if body["method"] == "initialize":
                    return self.reply(200, {"jsonrpc": "2.0", "id": body["id"], "result": {
                        "protocolVersion": "2025-03-26", "capabilities": {"tools": {}},
                        "serverInfo": {"name": "fixture", "version": "1"}}}, {"Mcp-Session-Id": "fixture-session"})
                if body["method"] == "notifications/initialized":
                    return self.reply(202)
                result = {"jsonrpc": "2.0", "id": body["id"], "result": {"tools": [{"name": "recall"}, {"name": "captureDecision"}]}}
                if fixture.sse:
                    data = (": ping\n\nevent: message\ndata: " + json.dumps(result) + "\n\n").encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                else:
                    self.reply(200, result)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.fixture_url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def run_cli(self, command, *extra):
        output, error = io.StringIO(), io.StringIO()
        args = [command, "--profile", "fixture", "--account-id", ACCOUNT, "--state", str(self.state), *extra]
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            code = verify.main(args, aws_factory=lambda *_: self.aws,
                               http_factory=lambda url, token: verify.Http(self.fixture_url, token))
        self.assertNotIn(TOKEN, output.getvalue() + error.getvalue())
        if self.state.exists():
            self.assertNotIn(TOKEN, self.state.read_text())
        return code, output.getvalue(), error.getvalue()

    def test_capture_once_then_recall_after_actual_version_change(self):
        code, output, error = self.run_cli("capture", "--apply")
        self.assertEqual(0, code, error)
        self.assertEqual("fixture-event-1", json.loads(output)["eventId"])
        state = json.loads(self.state.read_text())
        self.assertEqual(ARN, state["target"]["apiSecretArn"])
        self.assertEqual(0o600, self.state.stat().st_mode & 0o777)
        self.assertEqual(0, self.run_cli("capture", "--apply")[0])
        self.assertEqual(1, len(self.posts))
        self.assertEqual(1, self.run_cli("recall", "--after-redeploy")[0])
        self.aws.version = 2
        code, output, error = self.run_cli("recall", "--after-redeploy")
        self.assertEqual(0, code, error)
        self.assertTrue(json.loads(output)["afterRedeploy"])
        self.assertEqual(1, len(self.posts))

    def test_default_capture_is_read_only_without_secret_fetch_or_state(self):
        code, output, error = self.run_cli("capture")
        self.assertEqual(0, code, error)
        self.assertFalse(self.state.exists())
        self.assertFalse(self.paths)
        self.assertFalse(any(command[0] == "secretsmanager" for command in self.aws.calls))
        self.assertEqual("dry-run", json.loads(output)["mode"])

    def test_committed_500_is_recovered_only_by_readback(self):
        self.fail_capture = "committed"
        self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertEqual("pending", json.loads(self.state.read_text())["phase"])
        self.assertEqual(0, self.run_cli("capture", "--apply")[0])
        self.assertEqual(1, len(self.posts))

    def test_uncertain_missing_capture_never_blindly_retries(self):
        self.fail_capture = "dropped"
        for _ in range(3):
            self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertEqual(1, len(self.posts))
        self.assertEqual("pending", json.loads(self.state.read_text())["phase"])

    def test_bad_capture_response_preserves_pending_then_recovers(self):
        self.fail_capture = "bad-json"
        self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertEqual("pending", json.loads(self.state.read_text())["phase"])
        self.assertEqual(0, self.run_cli("capture", "--apply")[0])
        self.assertEqual(1, len(self.posts))

    def test_wrong_recalled_content_fails_even_when_event_id_matches(self):
        self.assertEqual(0, self.run_cli("capture", "--apply")[0])
        self.corrupt_recall = True
        code, _, error = self.run_cli("recall")
        self.assertEqual(1, code)
        self.assertIn("identity or content differs", error)

    def test_wrong_account_stops_before_any_other_read(self):
        self.aws.account = "999999999999"
        self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertEqual(1, len(self.aws.calls))
        self.assertFalse(self.state.exists())

    def test_url_guard_stops_before_secret_access(self):
        self.aws.url = "https://credential-catcher.example"
        self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertFalse(any(command[0] == "secretsmanager" for command in self.aws.calls))
        self.assertFalse(self.paths)

    def test_redirect_cannot_receive_bearer_token(self):
        self.assertEqual(0, self.run_cli("capture", "--apply")[0])
        self.redirect = True
        code, _, error = self.run_cli("recall")
        self.assertEqual(1, code)
        self.assertIn("HTTP 302", error)
        self.assertFalse(any("credential-leak-target" in path for path in self.paths))

    def test_state_in_git_checkout_rejected_before_post(self):
        (self.state.parent / ".git").mkdir()
        self.assertEqual(1, self.run_cli("capture", "--apply")[0])
        self.assertFalse(self.posts)

    def test_real_jsonrpc_initialize_notification_and_tool_list(self):
        code, output, error = self.run_cli("protocols")
        self.assertEqual(0, code, error)
        self.assertEqual(["initialize", "notifications/initialized", "tools/list"], self.rpc_methods)
        headers = {key.lower(): value for key, value in self.rpc_headers[-1].items()}
        self.assertEqual("fixture-session", headers["mcp-session-id"])
        self.assertEqual("2025-03-26", headers["mcp-protocol-version"])
        self.assertEqual(2, json.loads(output)["toolCount"])
        self.assertFalse(json.loads(output)["sseLongevityTested"])
        self.assertFalse(self.state.exists())

    def test_tools_list_can_arrive_as_a_real_sse_message(self):
        self.sse = True
        code, output, error = self.run_cli("protocols")
        self.assertEqual(0, code, error)
        self.assertEqual(["recall", "captureDecision"], json.loads(output)["tools"])


if __name__ == "__main__":
    unittest.main()
