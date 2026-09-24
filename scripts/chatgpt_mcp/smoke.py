#!/usr/bin/env python3
"""Real MCP smoke; read-only unless a write-test flag is explicitly supplied."""
import argparse
import asyncio
import json
import logging
from pathlib import Path
import sys
from uuid import uuid4

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from manage import ROOT, gateway_token, load_config

TEST_KEY = "blackbox-chatgpt-mcp-install-test-v1"
TEST_TEXT = "[INTEGRATION TEST] Black Box ChatGPT MCP capture and idempotency verification. " \
            "Synthetic test evidence only; this does not prove a ChatGPT-originated tool call."
VOICE_TEST_KEY = "blackbox-chatgpt-mcp-voice-routing-test-v1"
VOICE_TEST_TEXT = "[INTEGRATION TEST] Unified voice-project routing with declared origin. " \
                  "Synthetic local MCP test evidence; not proof of ChatGPT Work voice origin."
logging.disable(logging.CRITICAL)


async def smoke(url, token, project, write_test=False, write_voice_test=False, voice_project=None):
    headers = {"Authorization": "Bearer " + token}
    async with httpx.AsyncClient(headers=headers, timeout=40, trust_env=False) as http:
        async with streamable_http_client(url, http_client=http) as (read, write, _):
            async with ClientSession(read, write) as session:
                init = await session.initialize()
                tools = await session.list_tools()
                names = {t.name for t in tools.tools}
                assert names == {"search_records", "fetch_record", "project_context", "append_capture"}
                async def call(name, args, error=False):
                    result = await session.call_tool(name, args)
                    assert bool(result.isError) == error, (name, "unexpected result status")
                    return result.structuredContent if not error else None
                search = await call("search_records", {"query": 'project_exact:"' + project + '"', "limit": 2})
                assert search["results"], "No existing project records found"
                event_id = search["results"][0]["id"]
                record = await call("fetch_record", {"event_id": event_id})
                assert record["record"]["id"] == event_id and record["complete"]
                empty = await call("search_records", {"query": "absent-" + uuid4().hex, "limit": 2})
                assert empty["count"] == 0
                await call("fetch_record", {"event_id": "bad"}, error=True)
                await call("fetch_record", {"event_id": "00000000-0000-4000-8000-000000000000"}, error=True)
                context = await call("project_context", {"project": project, "max_chars": 4000})
                assert len(json.dumps(context, ensure_ascii=False, separators=(",", ":"))) <= 4000
                report = {"initialize": init.serverInfo.name, "tools": sorted(names), "existing_record": event_id,
                          "empty_and_invalid": "passed", "bounded_context": "passed"}
                if search.get("next_cursor"):
                    page = await call("search_records", {"query": 'project_exact:"' + project + '"',
                                     "limit": 2, "cursor": search["next_cursor"]})
                    assert not {x["id"] for x in page["results"]} & {x["id"] for x in search["results"]}
                    report["pagination"] = "passed"
                if write_test:
                    args = {"idempotency_key": TEST_KEY, "conversation_id": "integration-test-local-mcp-client",
                            "project": project, "text": TEST_TEXT, "kind": "observation"}
                    first = await call("append_capture", args)
                    second = await call("append_capture", args)
                    assert first["event_id"] == second["event_id"] and second["replayed"]
                    stored = await call("fetch_record", {"event_id": first["event_id"]})
                    assert stored["record"]["text"] == TEST_TEXT
                    assert stored["record"]["source"] == "chatgpt-work"
                    report["capture"] = {"event_id": first["event_id"], "retry_same_id": True,
                                         "first_was_replay": first["replayed"]}
                if write_voice_test:
                    args = {"idempotency_key": VOICE_TEST_KEY,
                            "conversation_id": "integration-test-local-mcp-client-voice-routing",
                            "text": VOICE_TEST_TEXT, "kind": "observation", "origin": "chatgpt_work_voice"}
                    first = await call("append_capture", args)
                    second = await call("append_capture", args)
                    assert first["event_id"] == second["event_id"] and second["replayed"]
                    stored = await call("fetch_record", {"event_id": first["event_id"]})
                    assert stored["record"]["text"] == VOICE_TEST_TEXT
                    assert stored["record"]["metadata"]["declaredVoiceOrigin"] == "chatgpt_work_voice"
                    assert stored["project"] == voice_project  # The operator-configured canonical voice project.
                    report["voice_capture"] = {"event_id": first["event_id"], "retry_same_id": True,
                                               "first_was_replay": first["replayed"],
                                               "origin_is_caller_declared": True}
    async with httpx.AsyncClient(trust_env=False) as http:
        for auth in ({}, {"Authorization": "Bearer deliberately-wrong"}):
            result = await http.post(url, headers=auth, json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
            assert result.status_code == 401
    report["missing_and_wrong_auth"] = "passed"
    report["verification_origin"] = "local SDK client; not ChatGPT"
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--write-test", action="store_true")
    parser.add_argument("--write-voice-test", action="store_true")
    args = parser.parse_args()
    config = load_config()
    if args.write_voice_test and not config.get("voice_project"):
        print("Voice write test needs a configured voice project: run configure-voice-project first", file=sys.stderr)
        sys.exit(2)
    try:
        report = asyncio.run(smoke("http://127.0.0.1:%d/mcp" % config["port"],
                                   gateway_token(), args.project, args.write_test, args.write_voice_test,
                                   config.get("voice_project")))
        print(json.dumps(report, indent=2))
        (ROOT / "last-smoke.json").write_text(json.dumps(report, indent=2) + "\n")
    except BaseException as error:
        print("Smoke failed (details suppressed to protect record content): " + type(error).__name__, file=sys.stderr)
        sys.exit(1)
