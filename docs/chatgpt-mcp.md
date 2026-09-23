# ChatGPT Work and Black Box

This integration adds a private, restricted MCP gateway around the **running** Black Box REST API.
It does not replace the Java server, migrate its data, or expose its existing broad MCP tools.
The gateway uses the repository's Python operational-script convention and the official MCP SDK.
All record reads and writes use the existing API; SQLite receipts are only retry bookkeeping.

## Connection and current verification

The selected remote transport is **OpenAI Secure MCP Tunnel**. It makes outbound HTTPS connections
and forwards only to the restricted gateway at `http://127.0.0.1:8767/mcp`. That local URL cannot
be pasted as a public ChatGPT server URL. ChatGPT must select **Tunnel** and the real tunnel ID.

As checked on 2026-09-22, the signed-in account exposes Platform tunnel creation and ChatGPT's
Plugins > Add > Create MCP App > Tunnel controls. There were no existing tunnels. These observations
did not establish runtime-key permissions or a completed connection. Later that day a private tunnel
was created and configured, the user's existing API key was stored in Keychain by explicit request,
and the tunnel passed configuration checks and successfully polled OpenAI. The new dedicated-key
connector had rejected creation; no dedicated key was created. Run `./scripts/chatgpt-mcp status`
for the actual configured tunnel ID and both services' live health/readiness.

Local verification covers the SDK protocol, live canonical data, one labeled capture and its replay,
auth rejection, and service recovery. Local proof is saved privately under
`~/.local/share/blackbox-chatgpt/` in `last-smoke.json` and `recovery-proof.json`.

On 2026-09-22, after the user approved enabling Developer mode, **Black Box Context** was installed
in desktop ChatGPT. An actual **Work** conversation called `search_records` with
`project:sba-agentic kind:decision` and limit 2, received two records, and called `fetch_record` on
a returned ID with `complete: true`. This verifies desktop ChatGPT read access through the native
tunnel. That first test made no writes. After the origin-aware schema refresh on 2026-09-23, a
**Work** conversation ([test chat](https://chatgpt.com/c/6ab40206-0a58-83ea-9b94-2a32ab44af3c))
made an explicit, labeled synthetic `append_capture` call with `origin=chatgpt_work_voice` and no
project. It returned event `864de60f-e18b-4102-ae92-19ae937d802f`, replayed the identical call
with the same event ID, and fetched the stored record. The canonical record confirms the existing
voice project and `declaredVoiceOrigin=chatgpt_work_voice`. This verifies cloud Work capture routing
and replay, but the origin is **caller-declared**, not authenticated proof of a real voice session.
Mobile app/browser access remains unverified; the earlier phone attempts occurred before this
connection was installed.

## Tools and capture routing

| Tool | Behavior |
| --- | --- |
| `search_records` | Canonical lexical event search; stable IDs, 700-character excerpts, dates, source/project/session metadata; limit 1–50; cursor pagination |
| `fetch_record` | Complete stored event by UUID plus its session project; includes stored metadata/tool data; never reads a filesystem transcript |
| `project_context` | Verified canonical project group, including aliases; recent decisions/handoffs/observations/projections; limit 1–20 and a 4,000–24,000-character JSON budget |
| `append_capture` | Explicit observation/decision/handoff; 16,000-character text limit; required stable request key and conversation/group provenance; optional declared voice origin and original cwd |

Search supports the existing `source:`, `kind:`, `project:`, `project_exact:`, `project_group:`, `session:`, `since:`,
`until:`, and `last:` grammar. Keep the same query with each returned `next_cursor`. A last nonempty
page can have a cursor whose following page is empty. Search is lexical, not vector ranking.
Complete retrieval means complete **stored** data; ingestion may already have redacted/truncated it.
Responses exceeding 16 MiB fail explicitly rather than claiming incomplete data is complete.

Black Box holds session evidence, context, and explicit captures. **Tasks belong in Linear;
Todoist is retired.** Agents on the Mac write notes and ideas to Obsidian at `~/Notes/obsidian`.
Agents executing from a cloud server (including OpenAI cloud) use their Google Drive connector to write Markdown into the
verified Drive folder syncing that same vault, preserving its folder structure. Google Drive
is the cloud access path to the same Obsidian vault, not a separate notes collection.

These are instructions to the calling agent, including cloud ChatGPT. The MCP gateway itself
runs on the Mac through the tunnel and writes only Black Box events. It does not create Linear
issues or write Obsidian/Drive notes. The caller needs separate Linear/Drive tools and must verify
the actual vault folder and Markdown-write support. Never invent folder IDs, substitute Google
Docs, or redirect blocked notes/tasks into Black Box. Drive write success is separate from proof
of Mac/phone sync. Cloud Markdown writing and sync have not been verified by this integration.
MCP connection does not synchronize whole ChatGPT or Codex conversation histories.

For voice captures, an explicit destination wins. Otherwise, use a verified full repository path
for work genuinely owned by that repository; a passing topic mention does not establish ownership.
For projectless voice context, omit `project` and declare `origin` as `codex_voice`, `chatgpt_voice`,
or `chatgpt_work_voice`. Use `voice_unknown` only when voice intent is clear but its surface cannot
be verified. All four use the **same** existing canonical voice project
`/Users/nathan/Documents/Codex/2026-09-15/realtime-voice-chat`, with origin retained separately
as caller-declared metadata. This avoids three competing project histories. The source label
`chatgpt-work` identifies this gateway and does not authenticate ChatGPT Work as the caller.
Omitted origin plus omitted project fails closed; old clients with an explicit project remain
compatible. Include `original_cwd` when known and a real conversation/session ID in
`conversation_id`. Black Box groups exact dated Codex
voice directories under this project when `SBA_PROJECTS_VOICE_CANONICAL_SCOPE` is configured in the
local Black Box service. It preserves each raw session directory and event ID. For real-project
work, verify the full repository path and keep a reference to the originating voice session;
do not infer ownership from a project mentioned in conversation. `project_context` uses the
logical project group, so it includes reversible aliases.

On this Mac a reviewed manual alias `voice` points to that existing canonical path. It provides a
short lookup for `project_group:"voice"` without creating another project or changing raw event
paths. The alias can be removed with `DELETE /api/project-aliases?aliasKey=voice`; the canonical
project and its dated aliases remain. A fresh installation must inspect its catalog for a `voice`
collision before adding such an alias through `PUT /api/project-aliases`.

On this Mac the Black Box launchd service sets
`SBA_PROJECTS_VOICE_CANONICAL_SCOPE=/Users/nathan/Documents/Codex/2026-09-15/realtime-voice-chat`.
The resolver accepts only exact dated `~/Documents/Codex/YYYY-MM-DD/realtime-voice-chat[-N]`
and `~/Documents/Codex/YYYY-MM-DD-new-realtime-voice-chat` scopes. Historical matching scopes
are discovered at startup and new ones on ingestion. To reverse automatic grouping, remove the
environment setting from the Black Box launchd job and restart Black Box, then inspect
`/api/projects` for scopes with `source: "codex-voice"` and delete only those reviewed alias keys
through `DELETE /api/project-aliases?aliasKey=...`. Manual aliases are separate and remain
individually removable. This changes catalog grouping only; it never edits stored sessions/events.

`./scripts/chatgpt-mcp doctor` uses a temporary health port, so it can check the tunnel while the
background tunnel already owns its normal 8768 health listener. A successful doctor verifies the
tunnel's control-plane checks; it does not substitute for a fresh ChatGPT Work tool call.

One historical cloud capture was submitted with the bare project label `constellate` even though
its text concerns general voice orchestration. It remains searchable under its original event ID
and raw label. Do not globally alias that bare label to either the voice project or the Constellate
repository: a global alias would misroute a future capture with the other meaning. Use verified
full project paths for new captures; a record/session-specific correction is future work.

The reusable skill is [blackbox-context](../skills/blackbox-context/SKILL.md); its essential rules are
also advertised as MCP server/tool instructions. Copy the skill folder to `~/.codex/skills/` for
local discovery. ChatGPT does not load a local Codex skill automatically. Direct MCP works without
a plugin package; no plugin or skill has been publicly published.

## Install and connect

Prerequisites: the existing Black Box listener, macOS, and `uv`. The installer uses Python 3.12 and
hash-pinned dependencies. It copies only this gateway's files into a user-private runtime directory;
it never rebuilds the existing Java JAR. The default local listener must be free on 8767; tunnel
health/admin uses 8768. Configuration lives outside the repository in `config.json` in the runtime.

```sh
./scripts/chatgpt-mcp install
./scripts/chatgpt-mcp status
```

1. Open [Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels).
   Create **Black Box Context**, associate the intended Platform organization **and the target
   ChatGPT workspace**, and retain the actual returned tunnel ID. Do not guess workspace IDs.
   Creation needs Tunnels Read + Manage; use needs Read + Use. A visible Create button alone does
   not prove the final request will be permitted.
2. Create or select a runtime API key whose principal has Tunnels Read + Use for that organization.
   Prefer a dedicated least-privilege runtime key; do not use an admin key for the daemon. Enter it
   locally at the hidden prompt below, never into chat or a command argument. macOS may ask you to
   authorize its Keychain access; complete that prompt yourself.
3. Configure and start using the real ID (replace the placeholder; it is not a valid ID):

   ```sh
   ./scripts/chatgpt-mcp configure-tunnel YOUR_ACTUAL_TUNNEL_ID
   ./scripts/chatgpt-mcp set-secret tunnel
   ./scripts/chatgpt-mcp doctor
   ./scripts/chatgpt-mcp start
   ./scripts/chatgpt-mcp status
   ```

4. Wait for both services to report healthy and ready. The tunnel's local operator UI is
   `http://127.0.0.1:8768/ui`. Its existence is not proof of remote tool use.
5. In ChatGPT, enable Developer mode in **Settings > Security and login** if needed/allowed.
   Open [Plugins](https://chatgpt.com/plugins), **Add > Create MCP App** (the official guide may call
   Add the plus button). Name it **Black Box Context**, select **Tunnel**, and select/paste the real
   tunnel ID. Select **No authentication** for the app-level setting: tunnel organization/workspace
   permissions protect the remote route, and the tunnel injects the private local Bearer credential.
   This setting is appropriate only for this private tunnel design, not a public unauthenticated URL.
6. Complete the trust/connection confirmation, check the four tools above, and run the prompts below
   in a new conversation with the app enabled. Only a successful tool result in ChatGPT completes
   end-to-end verification. Refresh the connection after changing schemas/annotations/tool names.

Account login, role grants, workspace association, credential creation, and connection approval can
require user/admin action. A permission failure is not permission to switch organizations or bypass
controls. If account setup is unfinished, local development/testing can still be complete.

## Authentication and retry guarantees

The local listener binds only to `127.0.0.1`, requires Bearer auth for MCP requests, rejects invalid
credentials before protocol dispatch, and has no proxy-header trust. The MCP SDK validates local
Host/Origin. `/healthz` and `/readyz` return only status; OAuth metadata returns 404 because this is
not an OAuth server. The endpoint exposes exactly four tools: no deletion, shell, files, workflow
queues, or arbitrary upstream paths. Reads cover the existing Black Box corpus; this is a personal
single-user boundary, not tenant isolation or a project-based authorization system.

The gateway credential is generated into an owner-only `gateway-token` file inside a mode-0700
runtime directory. It never appears in source, command arguments, plist values, logs, or output.
The tunnel runtime key is stored as a generic macOS Keychain password under service
`blackbox-chatgpt-mcp`, account `tunnel`; the fixed Apple `security` reader retrieves it into the
child environment. Missing/locked credentials fail closed. This is the same local-user trust
boundary as the existing loopback Black Box service, not protection from arbitrary same-user code.
An unused `gateway` Keychain item from initial setup may remain; the service does not read it.

Captures use fixed source `chatgpt-work`, server ingestion time, an integration version, a unique
request marker, a payload digest, and client-supplied conversation provenance. The optional voice
origin and original cwd are caller-declared, not authenticated identity. They participate in the
idempotency digest, so retries must use exactly the same values. Legacy requests without these
fields retain their original digest. The conversation ID is not authenticated proof of a particular ChatGPT conversation. A separate canonical session per
logical capture makes retry reconciliation exact; the original conversation remains in metadata.

The gateway commits a receipt reservation **before** dispatch. Same key/same payload returns the
same event ID; same key/different payload fails. After a response is lost, it looks for the exact
source/session/digest in the canonical API and recovers the original ID. Concurrent callers cannot
dispatch twice. If no record is visible after an ambiguous attempt, it refuses to send again.
This favors duplicate prevention over automatic delivery: a crash before dispatch can leave a
pending receipt needing operator reconciliation. Do not change the key, remove the receipt, or
restore an older receipts backup to force another attempt. Preserve receipts during reinstall.

## Verification prompts

1. **Search:** “Use Black Box to search `project:sba-agentic kind:decision`. Return three event IDs,
   dates, project paths, and short excerpts. Do not create a capture.”
2. **Retrieve:** “Use Black Box to fetch the complete stored event for one ID from those results.
   Show its source, timestamp and metadata, then retrieve bounded context for its exact project.”
3. **Capture:** “Explicitly capture this Black Box observation: `[CHATGPT MCP TEST] Search and fetch
   succeeded in this ChatGPT conversation.` Use this conversation ID if available, otherwise a
   clearly labeled test grouping. Use idempotency key `chatgpt-blackbox-verification-v1`, repeat the
   exact same call once, and show that both calls return the same event ID. This is test evidence,
   not a task or Obsidian note.”

Use a fresh logical key for a genuinely new test. Reuse the exact old arguments when retrying it.

## Tests and operation

```sh
# Isolated fixture suite; never touches live Black Box.
uv run --python 3.12 --with pytest --with-requirements scripts/chatgpt_mcp/requirements.lock \
  python -m pytest scripts/chatgpt_mcp/test_gateway.py -q

# Real MCP read-only test against an existing recorded project path.
~/.local/share/blackbox-chatgpt/venv/bin/python scripts/chatgpt_mcp/smoke.py --project /actual/project/path
# Add --write-test or --write-voice-test only to authorize the respective fixed, labeled
# synthetic capture/replay. Repeated invocations reuse their original keys and arguments.

# Explicitly stop/start and kill/recover only this gateway's supervisor.
python3 scripts/chatgpt_mcp/test_service_recovery.py

./scripts/chatgpt-mcp status
./scripts/chatgpt-mcp stop
./scripts/chatgpt-mcp start
./scripts/chatgpt-mcp restart
./scripts/chatgpt-mcp update
./scripts/chatgpt-mcp update-tunnel
./scripts/chatgpt-mcp uninstall
```

`start`, `stop`, and `restart` also accept `gateway` or `tunnel`. Update copies reviewed gateway code
from this checkout after stopping the integration. It does not pull Git changes or deploy unrelated
Black Box work. `update-tunnel` downloads the latest official vendor release and checks its release
SHA-256 digest before replacement. The tested vendor client was v0.0.14. Dependency changes belong
in `requirements.in`; regenerate `requirements.lock` with `uv pip compile --generate-hashes`.

LaunchAgent labels are `com.nathan.blackbox-chatgpt.gateway` and `.tunnel`. They start at login,
restart after failure, throttle retries for 15 seconds, and check child health every 10 seconds.
Six consecutive failed liveness checks trigger restart. Logs contain supervisor lifecycle only,
rotated at 1 MiB with three backups per component; child output is drained without persistence.
This prevents third-party exceptions from leaking inputs or secrets. Readiness separately checks
Black Box reachability; an upstream outage does not intentionally restart Black Box.

**Availability:** these are per-user LaunchAgents. They stop at logout and are unavailable while the
Mac sleeps or is off; they resume at login/wake, subject to network and Keychain availability. No
power setting, automatic login, system LaunchDaemon, or cloud compute was configured.

Uninstall removes the two LaunchAgents and stops their processes. It deliberately retains private
runtime files, receipts and credentials so retry history is not lost. Disconnect the ChatGPT app,
retire its Platform tunnel and dedicated runtime key, then remove the local runtime directory and
the `blackbox-chatgpt-mcp` Keychain entries if permanently retiring the integration. Remove the
personal `~/.codex/skills/blackbox-context` folder if no longer wanted. Existing Black Box records,
including the labeled test capture, are preserved; this integration has no deletion tool.

## Troubleshooting and fallback evaluation

- **Gateway not ready:** check the original Black Box `/api/status`; this installer does not restart
  or rebuild it. Confirm ports 8767/8768 are free and runtime file ownership/permissions are intact.
- **Tunnel absent:** run configure-tunnel with the real ID. None was invented during setup.
- **Tunnel not ready:** run doctor, check Keychain access, outbound `api.openai.com:443`, runtime
  Read + Use, and the correct organization/workspace associations. New role grants can take time.
- **No tunnel listed in ChatGPT:** organization-only association is insufficient; check the actual
  target ChatGPT workspace and developer-mode access. Do not change account permissions silently.
- **Couldn't create MCP app / Retry loading tunnels:** verify Developer mode first. The observed
  ChatGPT tunnel-list request returned HTTP 403 with `Developer mode is required` while the UI
  exposed only a generic creation error. A visible Create MCP App form does not prove mode is enabled.
  Enabling the setting with user approval resolved this failure in the desktop test.
- **Doctor rejects log level:** the vendor client requires `--log.format json` alongside
  `--log.level warn`; the integration supplies both. Doctor validates configuration, while successful
  control-plane polling and an actual ChatGPT tool call are stronger, separate checks.
- **401 locally:** local clients need the gateway credential, not the upstream's broad agent token.
  The tunnel forwards a static header from an environment reference; the credential is not in argv.
- **Capture uncertain:** retry the same key/arguments later. Inspect the canonical source/session
  marker and private receipt before operator action. Never blindly resubmit with another key.
- **Tools changed:** restart/update the integration, then Refresh the MCP app in ChatGPT and use a
  new conversation. Local SDK success or tunnel readiness is not a ChatGPT tool-call receipt.

An authenticated public HTTPS facade is the fallback if native tunnel use is actually denied.
The inspected ChatGPT UI supports **API key** as well as OAuth; a future public route could use the
same restricted gateway with the supported API-key setting, after verifying header mapping and
remote rejection tests. Tailscale Serve currently provides private tailnet access; ChatGPT cloud
does not join the tailnet. Enabling Funnel on a shared route could expose unrelated existing paths,
so it was not changed. A dedicated Cloudflare Tunnel/HTTPS route would need a stable hostname,
credentials and verified authentication; a browser-only Access login is not an MCP auth solution.
Neither public ingress nor a separate cloud database/service was provisioned; the native tunnel
has authenticated successfully, so ChatGPT-side setup should be resolved before adding another route.

Sources checked 2026-09-22: [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels),
[Connect and test](https://developers.openai.com/plugins/deploy/connect-chatgpt),
[official tunnel-client releases](https://github.com/openai/tunnel-client/releases/latest),
[official Python SDK](https://github.com/modelcontextprotocol/python-sdk/tree/v1.x).
