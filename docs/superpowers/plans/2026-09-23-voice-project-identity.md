# Voice project identity and cloud capture routing

The live catalog on 2026-09-23 had 21 separate dated Codex voice groups (one already held five manual aliases) and one bare `constellate` group with a single ChatGPT Work event. That event is about voice/Codex orchestration, not Constellate repository work. The existing voice group rooted at `2026-09-15/realtime-voice-chat` is the canonical identity on this Mac.

Safety contract:

- Keep raw session cwd, event IDs, conversation IDs, and capture text untouched. Alias rows only affect logical grouping and group queries.
- Match the exact dated Codex voice folder shapes under the current user's `Documents/Codex`; do not infer project identity from topic or a bare basename.
- Require an explicit configured voice canonical scope. With no configuration, product behavior remains unchanged. A manual alias takes precedence over discovery.
- A disabled voice rule stops new automatic grouping; its automatically created aliases can then be removed through the existing alias API. Existing manual aliases remain individually reversible.
- Keep ChatGPT Work's idempotency digest and event marker tied to the caller's original arguments. Never silently normalize capture arguments on retry.
- Use `project_group` for bounded MCP project context so the canonical voice identity retrieves all aliased scopes.

Implementation and verification:

1. Extend verified alias discovery with exact voice-path matching and a separately identifiable source. Add narrow integration tests for startup-like discovery, live ingestion, raw provenance, grouping, and reversal.
2. Configure the local launchd service with the already verified voice canonical scope. Build and restart the local Black Box service; preserve the existing database and tunnel.
3. Confirm the catalog shrinks, combined count is conserved, old scope URLs and event UUIDs still resolve, and a new dated voice capture groups under the canonical key.
4. Update ChatGPT MCP instructions and bounded context to use the canonical project path and group semantics. Run gateway tests, deploy/restart only the gateway, refresh Work tool descriptions, and separate local proof from actual cloud tool use.

Observed result on 2026-09-23: local service restart grouped the 21 dated voice catalog entries into one existing project, retaining 65 sessions and adding 20 `codex-voice` alias rows alongside five earlier manual rows. The pre-restart 7,061 voice events remained; live ingestion added new events while checks ran. A ChatGPT Work `project_context` call on the canonical key returned stable IDs with the original `2026-09-22/realtime-voice-chat-2` paths, proving the refreshed cloud connection used the group query. The tunnel doctor passes while the running tunnel keeps port 8768.

The one bare `constellate` cloud capture (`f25d77dd-1361-4274-b421-83587fc411a3`) has voice-orchestration text and remains a separate one-event scope. A global `constellate` alias was tried and immediately removed because it would misroute future genuine Constellate captures. Its stored event was never rewritten. A future correction needs a record/session-scoped reversible override rather than another global alias; the real Constellate repository group remains distinct.
