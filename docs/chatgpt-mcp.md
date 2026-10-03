# ChatGPT Work and Black Box

> **Audience and status:** optional, macOS-only, single-user integration for people who want
> ChatGPT Work to read and append Black Box records. It is a working setup the maintainer runs, not a
> hosted service or a published ChatGPT app. You need your own OpenAI Platform organization with
> Secure MCP Tunnel access, a ChatGPT workspace that allows Developer mode, and an API key you
> create. Everything else in Black Box works without it.

This integration adds a private, restricted MCP gateway around the **running** Black Box REST API.
It does not replace the Java server, migrate its data, or expose its existing broad MCP tools.
The gateway uses the repository's Python operational-script convention and the official MCP SDK.
All record reads and writes use the existing API; SQLite receipts are only retry bookkeeping.

**Data egress.** Anything a tool returns, including searched excerpts and complete fetched
records, is sent through the OpenAI tunnel to ChatGPT and is handled under your OpenAI account's
terms. Reads cover the whole Black Box corpus, not one project.

## Connection model

The selected remote transport is **OpenAI Secure MCP Tunnel**. It makes outbound HTTPS connections
and forwards only to the restricted gateway at `http://127.0.0.1:8767/mcp`. That local URL cannot
be pasted as a public ChatGPT server URL. ChatGPT must select **Tunnel** and the real tunnel ID.
The gateway runs on your Mac: it is a loopback listener with a local receipt ledger, not an
independent cloud queue, so ChatGPT can reach it only while the Mac is awake and both services are
running.

The maintainer's installation has verified, in desktop ChatGPT Work conversations, search and
complete fetch over the tunnel, and an explicit labeled capture whose identical replay returned the
same event ID. Mobile app and browser access have not been verified. Run
`./scripts/chatgpt-mcp status` for your own configured tunnel ID and both services' health and
readiness; only a successful tool call in ChatGPT proves your connection end to end.

## Tools and capture routing

| Tool | Behavior |
| --- | --- |
| `search_records` | Canonical lexical event search; stable IDs, 700-character excerpts, dates, source/project/session metadata; limit 1–50; cursor pagination |
| `fetch_record` | Complete stored event by UUID plus its session project; includes stored metadata/tool data; never reads a filesystem transcript |
| `project_context` | Verified canonical project group, including aliases; recent decisions/handoffs/observations/projections/ideas/evidence; limit 1–20 and a 4,000–24,000-character JSON budget |
| `append_capture` | Explicit observation/decision/handoff/idea/evidence; 16,000-character text limit; required stable request key and conversation/group provenance; optional declared voice origin and original cwd |

Gateway Idea and Evidence captures are text-only: use the first line as the title or claim and
include provenance in the text. They do not populate structured `sourceRef`, `supports`, or
`refutes` fields. For typed Evidence links, use the local [`captureEvidence` or `/api/evidence`
contract](agent-integration.md). The gateway cannot append a Projection.

Search supports the existing `source:`, `kind:`, `project:`, `project_exact:`, `project_group:`, `session:`, `since:`,
`until:`, and `last:` grammar. Keep the same query with each returned `next_cursor`. A last nonempty
page can have a cursor whose following page is empty. Search is lexical, not vector ranking.
Complete retrieval means complete **stored** data; ingestion may already have redacted/truncated it.
Responses exceeding 16 MiB fail explicitly rather than claiming incomplete data is complete.

Black Box holds session evidence, context, and explicit captures. It is not a task tracker or a
notes app. Tell the calling agent to put tasks and durable notes in whatever systems you already
use (the maintainer uses Linear and an Obsidian vault). The gateway writes only Black Box events: it
cannot create tasks or notes, and the caller needs separate tools for those. Never redirect a
blocked task or note into Black Box. MCP connection does not synchronize whole ChatGPT or Codex
conversation histories.

For voice captures, an explicit destination wins. Otherwise, use a verified full repository path
for work genuinely owned by that repository; a passing topic mention does not establish ownership.
For projectless voice context, omit `project` and declare `origin` as `codex_voice`, `chatgpt_voice`,
or `chatgpt_work_voice`. Use `voice_unknown` only when voice intent is clear but its surface cannot
be verified. All four use the **same** canonical voice project, which the operator configures on
the gateway with `./scripts/chatgpt-mcp configure-voice-project <path>` (stored as `voice_project`
in the runtime `config.json`; for example `/path/to/voice-project`), with origin
retained separately as caller-declared metadata. Without that setting a projectless voice capture
fails closed. This avoids three competing project histories. The source label
`chatgpt-work` identifies this gateway and does not authenticate ChatGPT Work as the caller.
Omitted origin plus omitted project fails closed; old clients with an explicit project remain
compatible. Include `original_cwd` when known and a real conversation/session ID in
`conversation_id`. Optional grouping of dated Codex voice directories has an additional path
requirement, described below. It preserves each raw session directory and event ID. For real-project
work, verify the full repository path and keep a reference to the originating voice session;
do not infer ownership from a project mentioned in conversation. `project_context` uses the
logical project group, so it includes reversible aliases.

Optionally, add a manual alias such as `voice` that points to the canonical voice path. It provides a
short lookup for `project_group:"voice"` without creating another project or changing raw event
paths. The alias can be removed with `DELETE /api/project-aliases?aliasKey=voice`; the canonical
project and its dated aliases remain. Inspect the catalog for a `voice` collision before adding such an
alias through `PUT /api/project-aliases`.

To group dated Codex voice directories automatically, choose an **existing absolute dated Codex
voice-directory path under the Black Box JVM user's home** for both the gateway's voice project
and the server's `SBA_PROJECTS_VOICE_CANONICAL_SCOPE`. For example, expand
`$HOME/Documents/Codex/2026-09-24/realtime-voice-chat` as that user and use the resulting absolute
path, with the date of the actual directory. The server rejects a generic path such
as `/path/to/voice-project` for this setting, although the gateway accepts it as a capture destination.
The resolver accepts only exact dated `~/Documents/Codex/YYYY-MM-DD/realtime-voice-chat[-N]`
and `~/Documents/Codex/YYYY-MM-DD-new-realtime-voice-chat` scopes. Historical matching scopes
are discovered at startup and new ones on ingestion. To reverse automatic grouping, remove the
environment setting from the Black Box server's configuration and restart it, then inspect
`/api/projects` for scopes with `source: "codex-voice"` and delete only those reviewed alias keys
through `DELETE /api/project-aliases?aliasKey=...`. Manual aliases are separate and remain
individually removable. This changes catalog grouping only; it never edits stored sessions/events.

`./scripts/chatgpt-mcp doctor` uses a temporary health port, so it can check the tunnel while the
background tunnel already owns its normal 8768 health listener. A successful doctor verifies the
tunnel's control-plane checks; it does not substitute for a fresh ChatGPT Work tool call.

Avoid bare project labels such as a repository's short name. A capture made with an ambiguous
label stays searchable under that raw label, and globally aliasing it later can misroute future
captures that meant something else. Use verified full project paths; a record-specific correction
tool does not exist yet.

The reusable skill is [blackbox-context](../skills/blackbox-context/SKILL.md); its essential rules are
also advertised as MCP server/tool instructions. Copy the skill folder to `~/.codex/skills/` for
local discovery. ChatGPT does not load a local Codex skill automatically. Direct MCP works without
a plugin package; no plugin or skill has been publicly published.

## Install and connect

Prerequisites: macOS, `uv`, a working `/usr/bin/python3` for the launcher, and an existing Black Box
listener on loopback HTTP. The shipped launcher supplies no upstream bearer token, so it cannot
connect to an authentication-enabled Black Box listener. Keep existing authentication in place;
that setup needs upstream authentication support before this launcher can be used.

The installer provisions a Python 3.12 environment with hash-pinned dependencies, separately from
the launcher's system Python. It copies only this gateway's files into a user-private runtime directory;
it never rebuilds the existing Java JAR. The default local listener must be free on 8767; tunnel
health/admin uses 8768. Configuration lives outside the repository in `config.json` in the runtime.

```sh
./scripts/chatgpt-mcp install
./scripts/chatgpt-mcp configure-voice-project /path/to/voice-project   # optional
./scripts/chatgpt-mcp status
```

`configure-voice-project` is optional: it stores the canonical voice project as `voice_project` in
`config.json` so captures that declare a voice `origin` without a `project` have a destination.
If you also enable the server's optional dated-directory grouping, use its valid dated canonical
path here too, as described above. Re-run `update` (or `restart gateway`) after changing the gateway
setting. Without it, such captures fail closed; remove the
key from `config.json` and restart to disable the fallback again.

The following vendor UI and permissions steps describe the desktop setup verified on 2026-09-23;
check the available controls in your own organization and workspace.

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
   not a task or note.”

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

LaunchAgent labels are `com.nathan.blackbox-chatgpt.gateway` and `.tunnel`; the prefix is currently
fixed in `scripts/chatgpt_mcp/manage.py` for every installation. They start at login,
restart after failure, throttle retries for 15 seconds, and check child health every 10 seconds.
Six consecutive failed liveness checks trigger restart. Logs contain supervisor lifecycle only,
rotated at 1 MiB with three backups per component; child output is drained without persistence.
This prevents third-party exceptions from leaking inputs or secrets. Readiness separately checks
Black Box reachability; an upstream outage does not intentionally restart Black Box.

**Availability:** these are per-user LaunchAgents. They stop at logout and are unavailable while the
Mac sleeps or is off; they resume at login/wake, subject to network and Keychain availability. The
installer does not change power settings, enable automatic login, install a system LaunchDaemon, or
provision cloud compute.

Uninstall removes the two LaunchAgents and stops their processes. It deliberately retains private
runtime files, receipts and credentials so retry history is not lost. Disconnect the ChatGPT app,
retire its Platform tunnel and dedicated runtime key, then remove the local runtime directory and
the `blackbox-chatgpt-mcp` Keychain entries if permanently retiring the integration. Remove your copied
`~/.codex/skills/blackbox-context` folder if no longer wanted. Existing Black Box records,
including the labeled test capture, are preserved; this integration has no deletion tool.

## Troubleshooting and fallback evaluation

- **Gateway not ready:** check the original Black Box `/api/status`; this installer does not restart
  or rebuild it. Confirm ports 8767/8768 are free and runtime file ownership/permissions are intact.
- **Tunnel absent:** run configure-tunnel with the real ID. The installer never invents one.
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

An authenticated public HTTPS facade is the fallback if native tunnel use is actually denied. It is
not supplied. ChatGPT's app settings support **API key** as well as OAuth, so a future public route
could front the same restricted gateway with an API key, after verifying header mapping and remote
rejection tests. Private-network tools such as a tailnet do not help, because ChatGPT's cloud cannot
join them, and exposing a shared reverse-proxy route can publish unrelated paths. A dedicated HTTPS
route needs a stable hostname, credentials, and verified authentication; a browser-only login page
is not an MCP authentication solution. Resolve ChatGPT-side tunnel setup before adding another route.

ChatGPT and Platform menu names above were checked against the vendor UI and docs on 2026-09-22 and
may change. Sources: [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels),
[Connect and test](https://developers.openai.com/plugins/deploy/connect-chatgpt),
[official tunnel-client releases](https://github.com/openai/tunnel-client/releases/latest),
[official Python SDK](https://github.com/modelcontextprotocol/python-sdk/tree/v1.x).
