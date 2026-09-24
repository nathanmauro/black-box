import asyncio
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import threading
import time
from uuid import uuid4

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
import pytest
import uvicorn

from gateway import BlackBox, GatewayError, canonical, create_app
import manage
from manage import tunnel_command


@pytest.fixture
def upstream(tmp_path):
    items = [{"id": str(uuid4()), "sessionId": str(uuid4()), "source": "codex",
              "clientSessionId": "fixture-session", "text": "existing evidence " + "x" * 2000,
              "eventType": "Decision", "metadata": {"detail": "complete metadata"},
              "observedAt": "2026-09-22T10:00:00Z", "cwd": "/fixture/project", "sessionTitle": "Fixture"}]
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, data, status=200):
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(data).encode())

        def do_GET(self):
            from urllib.parse import urlparse, parse_qs
            parsed = urlparse(self.path)
            params = {k: v[0] for k, v in parse_qs(parsed.query).items()}
            requests.append(("GET", parsed.path, params))
            if parsed.path == "/api/events":
                matches = items[:]
                q = params.get("q", "")
                if "session:" in q:
                    marker = q.split("session:")[1].split()[0]
                    matches = [x for x in items if x["clientSessionId"] == marker and x["source"] == "chatgpt-work"]
                elif "absent" in q:
                    matches = []
                if params.get("before"):
                    index = next((i for i, x in enumerate(matches) if x["observedAt"] + "|" + x["id"] == params["before"]), -1)
                    matches = matches[index + 1:]
                matches = matches[:int(params["limit"])]
                self.respond({"items": matches, "nextBefore": matches[-1]["observedAt"] + "|" + matches[-1]["id"] if matches else None})
            elif parsed.path.startswith("/api/events/"):
                record = next((x for x in items if x["id"] == parsed.path.split("/")[-1]), None)
                self.respond(record, 200 if record else 404)
            elif parsed.path.startswith("/api/sessions/"):
                self.respond({"cwd": "/fixture/project"})
            else:
                self.respond({}, 404)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            requests.append(("POST", self.path, body))
            event = {**body, "id": str(uuid4()), "sessionId": str(uuid4()), "observedAt": "2026-09-22T12:00:00Z"}
            items.insert(0, event)
            self.respond({"eventId": event["id"], "sessionId": event["sessionId"]})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    voice_project = str(tmp_path / "Documents/Codex/2026-01-01/realtime-voice-chat")  # Example, never a real path.
    backend = BlackBox("http://127.0.0.1:%d" % server.server_port, tmp_path / "receipts.db",
                       voice_project=voice_project)
    yield backend, items, requests
    backend.http.close()
    server.shutdown()
    server.server_close()


def test_search_full_fetch_pagination_context(upstream):
    backend, items, requests = upstream
    page = backend.search("existing", 1)
    assert len(page["results"][0]["excerpt"]) == 700
    assert page["results"][0]["excerpt_truncated"]
    assert backend.fetch(items[0]["id"])["record"]["text"] == items[0]["text"]
    assert not backend.search("existing", 1, page["next_cursor"])["results"]
    assert not backend.search("absent")["results"]
    for _ in range(12):
        items.append({**items[0], "id": str(uuid4())})
    result = backend.context("/fixture/project", 20, 4000)
    assert len(canonical(result)) <= 4000 and result["truncated"]
    assert 'project_group:"/fixture/project" kind:decision,handoff,observation,projection' == requests[-1][2]["q"]
    with pytest.raises(GatewayError, match="Invalid event ID"):
        backend.fetch("../../etc/passwd")
    with pytest.raises(GatewayError, match="not found"):
        backend.fetch(str(uuid4()))
    with pytest.raises(GatewayError, match="Invalid cursor"):
        backend.search("existing", cursor="malicious")
    with pytest.raises(GatewayError, match="Invalid project"):
        backend.context('/project" source:other')


def test_tunnel_doctor_uses_ephemeral_health_port():
    config = {"tunnel_id": "tunnel_fixture1234", "port": 8767, "tunnel_health_port": 8768}
    doctor = tunnel_command(config, "doctor")
    run = tunnel_command(config, "run")
    assert doctor[doctor.index("--health.listen-addr") + 1] == "127.0.0.1:0"
    assert run[run.index("--health.listen-addr") + 1] == "127.0.0.1:8768"


def capture(backend, key="request-1234", text="[TEST] explicit observation"):
    return backend.append(key, "conversation-fixture", "/fixture/project", text)


def test_retry_conflict_restart_and_lost_ledger(upstream, tmp_path):
    backend, items, requests = upstream
    first = capture(backend)
    assert not first["replayed"]
    again = BlackBox(str(backend.http.base_url), backend.ledger)
    assert capture(again) == {**first, "replayed": True}
    fresh = BlackBox(str(backend.http.base_url), tmp_path / "new-ledger.db")
    assert capture(fresh) == {**first, "replayed": True}
    for candidate in (backend, fresh):
        with pytest.raises(GatewayError, match="different arguments"):
            capture(candidate, text="changed")
    assert len([r for r in requests if r[0] == "POST"]) == 1
    assert items[0]["metadata"]["conversationId"] == "conversation-fixture"


@pytest.mark.parametrize("origin", ["chatgpt_voice", "chatgpt_work_voice", "codex_voice", "voice_unknown"])
def test_known_voice_origin_uses_one_project_with_separate_provenance(upstream, origin):
    backend, items, requests = upstream
    args = ("voice-" + origin, "real-conversation-id", None,
            "[TEST] Discussed /fixture/mentioned-repo, but did no verified repo work")
    first = backend.append(*args, origin=origin, original_cwd="/original/voice/location")
    assert backend.append(*args, origin=origin, original_cwd="/original/voice/location") == {
        **first, "replayed": True}
    saved = items[0]
    assert saved["cwd"] == backend.voice_project
    assert saved["source"] == "chatgpt-work"  # Gateway provenance, not the declared voice surface.
    assert saved["metadata"]["declaredVoiceOrigin"] == origin
    assert saved["metadata"]["originalCwd"] == "/original/voice/location"
    assert saved["metadata"]["conversationId"] == "real-conversation-id"
    assert len([request for request in requests if request[0] == "POST"]) == 1
    with pytest.raises(GatewayError, match="different arguments"):
        backend.append(*args, origin=origin, original_cwd="/changed/location")


def test_explicit_project_wins_and_unknown_origin_never_guesses(upstream):
    backend, items, _ = upstream
    backend.append("explicit-repo-key", "conversation-fixture", "/fixture/verified-repo",
                   "[TEST] real work in verified repo", origin="chatgpt_voice")
    assert items[0]["cwd"] == "/fixture/verified-repo"
    assert items[0]["metadata"]["declaredVoiceOrigin"] == "chatgpt_voice"
    with pytest.raises(GatewayError, match="Project is required"):
        backend.append("unknown-origin-key", "conversation-fixture", None, "[TEST] unknown origin")
    with pytest.raises(GatewayError, match="Unknown voice origin"):
        backend.append("invented-origin-key", "conversation-fixture", None, "[TEST] unknown", origin="guess")


def test_voice_capture_without_configured_voice_project_fails_closed(upstream, tmp_path):
    backend, items, requests = upstream
    unconfigured = BlackBox(str(backend.http.base_url), tmp_path / "unconfigured.db")
    assert unconfigured.voice_project is None
    before = len(items)
    with pytest.raises(GatewayError, match="No canonical voice project is configured"):
        unconfigured.append("no-voice-project-key", "conversation-fixture", None,
                            "[TEST] projectless voice capture", origin="chatgpt_voice")
    assert len(items) == before and not [request for request in requests if request[0] == "POST"]
    with unconfigured.db() as db:
        assert db.execute("SELECT count(*) FROM receipts").fetchone()[0] == 0  # No reservation, nothing to replay.
    unconfigured.append("explicit-project-key", "conversation-fixture", "/fixture/verified-repo",
                        "[TEST] explicit project still works", origin="chatgpt_voice")
    assert items[0]["cwd"] == "/fixture/verified-repo"
    with pytest.raises(ValueError, match="voice project"):
        BlackBox(str(backend.http.base_url), tmp_path / "bad.db", voice_project='bad "quoted" path')
    unconfigured.http.close()


def test_configure_voice_project_validates_and_saves(tmp_path, monkeypatch):
    monkeypatch.setattr(manage, "ROOT", tmp_path / "runtime")
    monkeypatch.setattr(manage, "CONFIG", tmp_path / "runtime/config.json")
    manage.save_config({"upstream": "http://127.0.0.1:8766", "port": 8767})
    for bad in ("relative/voice/path", '/quoted/"path"', ""):
        with pytest.raises(RuntimeError, match="absolute recorded path"):
            manage.configure_voice_project(bad)
    assert "voice_project" not in manage.load_config()
    example = str(tmp_path / "Documents/Codex/2026-01-01/realtime-voice-chat")
    manage.configure_voice_project(example + "\n")
    saved = manage.load_config()
    assert saved["voice_project"] == example and saved["port"] == 8767  # Existing keys preserved.


def test_oversized_metadata_does_not_hide_context(upstream):
    backend, items, _ = upstream
    items[0]["clientSessionId"] = "x" * 5000
    items[0]["source"] = "s" * 5000
    result = backend.context("/fixture/project", 10, 4000)
    assert len(canonical(result)) <= 4000
    assert result["records"][0]["metadata_truncated"]
    assert result["next_cursor"]


def test_concurrent_retries_dispatch_once(upstream):
    backend, items, requests = upstream
    def attempt(_):
        try:
            return capture(backend)
        except GatewayError as e:
            assert "uncertain" in str(e)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(attempt, range(16)))
    assert len([r for r in requests if r[0] == "POST"]) == 1
    assert capture(backend)["replayed"]


def test_committed_write_with_lost_response_recovers(upstream, monkeypatch):
    backend, items, requests = upstream
    original = backend.request
    def lose_response(method, path, **kwargs):
        value = original(method, path, **kwargs)
        if method == "POST":
            raise GatewayError("simulated lost response after upstream commit")
        return value
    monkeypatch.setattr(backend, "request", lose_response)
    with pytest.raises(GatewayError):
        capture(backend)
    restarted = BlackBox(str(backend.http.base_url), backend.ledger)
    assert capture(restarted)["replayed"]
    assert len([r for r in requests if r[0] == "POST"]) == 1


def test_unknown_outcome_fails_closed(upstream, monkeypatch):
    backend, items, requests = upstream
    original = backend.request
    def fail_before_response(method, path, **kwargs):
        if method == "POST":
            raise GatewayError("ambiguous network failure")
        return original(method, path, **kwargs)
    monkeypatch.setattr(backend, "request", fail_before_response)
    with pytest.raises(GatewayError):
        capture(backend)
    restarted = BlackBox(str(backend.http.base_url), backend.ledger)
    with pytest.raises(GatewayError, match="uncertain"):
        capture(restarted)
    assert not [r for r in requests if r[0] == "POST"]


def test_real_mcp_protocol_auth_and_tools(upstream):
    backend, items, requests = upstream
    token = "fixture-secret-" + "a" * 40
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(backend, token), log_level="critical"))
    thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True)
    thread.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(.01)
    url = "http://127.0.0.1:%d" % port
    try:
        for headers in ({}, {"Authorization": "Bearer wrong"}):
            assert httpx.post(url + "/mcp", headers=headers, json={}).status_code == 401
        assert httpx.get(url + "/healthz").status_code == 200
        assert httpx.get(url + "/readyz").status_code == 200
        assert httpx.get(url + "/.well-known/oauth-protected-resource").status_code == 404

        async def exercise():
            async with httpx.AsyncClient(headers={"Authorization": "Bearer " + token}) as http:
                async with streamable_http_client(url + "/mcp", http_client=http) as (read, write, _):
                    async with ClientSession(read, write) as session:
                        init = await session.initialize()
                        assert init.serverInfo.name == "Black Box Context"
                        tools = await session.list_tools()
                        assert len(tools.tools) == 4
                        assert {t.name for t in tools.tools} == {"search_records", "fetch_record", "project_context", "append_capture"}
                        for tool in tools.tools:
                            assert tool.annotations.readOnlyHint == (tool.name != "append_capture")
                            assert not tool.annotations.destructiveHint
                        capture_tool = next(tool for tool in tools.tools if tool.name == "append_capture")
                        assert "project" not in capture_tool.inputSchema["required"]
                        assert "origin" in capture_tool.inputSchema["properties"]
                        search = await session.call_tool("search_records", {"query": "existing", "limit": 1})
                        assert not search.isError and search.structuredContent["results"][0]["id"] == items[0]["id"]
                        record = await session.call_tool("fetch_record", {"event_id": items[0]["id"]})
                        assert record.structuredContent["record"]["metadata"]["detail"] == "complete metadata"
                        for name, args in [("fetch_record", {"event_id": "bad"}),
                                           ("search_records", {"query": "a", "limit": 51}),
                                           ("delete_record", {}),
                                           ("append_capture", {"idempotency_key": "test-1234", "conversation_id": "x",
                                                               "project": "/fixture/project", "text": "task", "kind": "task"})]:
                            assert (await session.call_tool(name, args)).isError
                        args = {"idempotency_key": "protocol-test", "conversation_id": "fixture", "project": "/fixture/project",
                                "text": "[TEST] MCP fixture capture"}
                        a = await session.call_tool("append_capture", args)
                        b = await session.call_tool("append_capture", args)
                        assert not a.isError and not b.isError
                        assert a.structuredContent["event_id"] == b.structuredContent["event_id"]
                        assert b.structuredContent["replayed"]
                        voice_args = {"idempotency_key": "protocol-voice-test", "conversation_id": "voice-fixture",
                                      "text": "[TEST] Projectless voice capture", "origin": "voice_unknown"}
                        voice = await session.call_tool("append_capture", voice_args)
                        assert not voice.isError
                        assert items[0]["cwd"] == backend.voice_project
                        assert items[0]["metadata"]["declaredVoiceOrigin"] == "voice_unknown"
        asyncio.run(exercise())
    finally:
        server.should_exit = True
        thread.join(timeout=5)
        sock.close()


def test_upstream_cannot_be_arbitrary_remote(tmp_path):
    for url in ("https://example.com", "http://169.254.169.254", "http://127.0.0.1:8766/files"):
        with pytest.raises(ValueError):
            BlackBox(url, tmp_path / "unused.db")
