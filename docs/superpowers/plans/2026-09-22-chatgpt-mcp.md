# ChatGPT Work MCP integration

## Safety contract

Use the running Black Box REST API as the sole record authority. Do not open its database,
rebuild its running JAR, deploy unrelated dirty changes, or expose its broad MCP/REST surface.
Add an independently supervised Python gateway, following the repository's operational-script
pattern. Use the official MCP SDK for protocol handling. Expose only search, complete event fetch,
bounded project context, and explicit append-only captures. No task, filesystem, or shell tools.

Prefer OpenAI Secure MCP Tunnel over public ingress. Verify the account UI, released client,
workspace mapping, and runtime permissions separately; do not invent a tunnel ID or claim an
end-to-end connection before ChatGPT calls a tool. Keep secrets outside source/logs: Keychain for
the runtime API key, and an owner-only local gateway credential when unattended Keychain access
requires a human prompt.

## Implementation and verification

1. Inspect existing storage, REST/MCP, configuration, dirty state, launchd, and official docs.
2. Add a restricted SDK adapter and durable idempotency receipt ledger. The upstream API does
   not deduplicate; reserve each request before dispatch and reconcile ambiguous outcomes using
   a unique canonical source/session marker. Never blindly resend an ambiguous write.
3. Test protocol initialization/discovery, actual REST mapping, limits/pagination, full records,
   invalid input, empty results, auth, concurrent retries, ambiguous writes, and restart recovery.
4. Install a private local runtime and launchd service with bounded logs and health commands.
5. Prepare a tunnel supervisor and secret-entry command, a reusable skill, and setup/runbook.
6. Exercise existing records and exactly one labeled test capture through MCP. Repeat after
   service restart, preserve the capture, and distinguish local/remote/ChatGPT proof.

## Inspection checkpoint

The existing Java server uses canonical relational storage, SQLite locally, optional secondary
Elasticsearch/embeddings, `/api/events` cursor pagination and `/api/events/{id}`. Its `/mcp` also
exposes workflow mutations unnecessary for ChatGPT. The running local deployment predates compact
retrieval changes. Keep it intact. Existing branch `rsi-box` has unrelated staged/unstaged work.

Official docs and live browser checked 2026-09-22: Platform tunnel settings is accessible, offers
Create tunnel, and has no tunnels. ChatGPT Plugins > Add > Create MCP App exposes Tunnel and
Server URL, with OAuth/API key/no-auth choices. Creation/association and runtime authorization
remain separate from this observed UI availability.

## Verified local result

Eight fixture tests passed, including actual HTTP MCP SDK initialization/discovery/tool calls,
authentication failures, pagination, full-event retrieval, validation, bounded oversized metadata,
concurrent retries, restart replay, lost-response recovery, and ambiguous-outcome refusal.
The installed launchd gateway passed real existing-data search/fetch, empty and invalid IDs,
bounded project context, pagination, missing/wrong auth, and one labeled synthetic capture.
Repeating the capture after restart returned the same event ID. Graceful restart and supervisor
SIGKILL recovery both passed with old children reaped. Proof files remain private in the runtime.

Review caught and fixed metadata budget overflow and child-process escape from launchd cleanup;
live testing also caught asynchronous bootout/start races. The Java service and its data were
not redeployed.

## Native tunnel checkpoint

The private Platform tunnel was created and associated with the selected ChatGPT workspace.
The secure dedicated-key connector rejected key creation; the user then explicitly selected reuse
of the existing key. It is stored in the tunnel's Keychain entry with no value in source/output.
The native client needed explicit JSON log format with its warn level; this was fixed in source
and the installed manager. Doctor passes, the client has successfully polled OpenAI, and launchd
restart recovered with a new supervisor PID and healthy/ready status.

ChatGPT app creation returned a generic failure. Browser request diagnostics established the exact
reason: tunnel listing returned HTTP 403, `Developer mode is required`. The Security and login
page confirmed Developer mode was off. The user subsequently explicitly approved enabling it;
the saved toggle was verified on, and Black Box Context was created and connected.

## Desktop ChatGPT proof

A real desktop ChatGPT Work conversation invoked the connected `search_records` tool with
`project:sba-agentic kind:decision`, limit 2, and fetched a returned event with `fetch_record`.
Two search records and a complete stored decision were returned. This establishes end-to-end
desktop read access through the native tunnel, beyond local/doctor/polling evidence. No capture was
made in this conversation. Capture/replay was verified locally earlier; a ChatGPT-originated write
and phone app/browser use remain unverified. The user reported phone failures before installation.
Actual account identifiers and the test conversation URL are retained in the private Black Box
handoff rather than committed as machine-specific public documentation.

## Routing correction (2026-09-23)

User superseded the original Todoist routing: tasks now belong in Linear. Note routing depends
on where the calling agent executes, not what the note is about. Agents executing on the Mac
use the local Obsidian vault; agents executing from a cloud server use Markdown in the verified
Drive folder syncing that same vault. The MCP server still runs on the Mac and writes only
Black Box events; it does not proxy Linear or Drive operations. Updated server instructions,
append_capture description, reusable/personal skill and guide, then redeployed the gateway.
Live MCP initialization and tool discovery verified the revised routing. Cloud Drive Markdown
writes and downstream sync remain separate unverified capabilities; missing access fails visibly.
