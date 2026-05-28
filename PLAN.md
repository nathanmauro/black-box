# SBA Agentic Public Showcase Handoff

## Goal

Make `sba-agentic` safe and compelling as a public repo without overselling unfinished behavior.

The showcase story should be:

- Local-first control plane for agent sessions, prompts, tool events, manual notes, MCP capture, search, and summaries.
- Codex and Claude hook payloads can land in one SQLite-backed timeline.
- The web UI shows live sessions, events, search, health, and summaries.
- Optional Elasticsearch extends search without replacing SQLite as the source of truth.
- Current session titles are seeded from the first captured prompt or explicit metadata title.

## Current Proof

- The app runs on `localhost:8766` with API, web UI, CLI, MCP, SQLite storage, and optional local AI summary support.
- Hook capture is implemented through `scripts/hooks/sba-agent-hook.sh`.
- `UserPromptSubmit` events provide enough text for useful session titles.
- `EventIngestService.titleFor(...)` uses `metadata.title`, then first event text, then tool/event fallback, compacted to 96 characters.
- `EventRepository.findOrCreateSession(...)` stores that title only when a `(source, clientSessionId)` session is first created.
- This means the current behavior is title seeding, not a later smart retitle/update system.

## Showcase Readiness Gates

- Public docs must avoid machine-specific claims. Absolute local paths are allowed only as examples or clearly marked local setup notes.
- Hook config must be documented as local/opt-in. Do not commit private `.codex`, `.claude`, database, IDE, or runtime state.
- README should include a crisp "why this exists" section, quickstart, architecture sketch, and privacy boundary.
- Add demo-safe sample data or a deterministic smoke script so visitors can see value without private session history.
- Add at least one screenshot or short GIF of the sessions/timeline/search flow.
- Add tests around title seeding and any future retitle behavior before calling it smart session naming.
- Keep SQLite as the canonical store in the public story; Elasticsearch is optional indexing.

## Implementation Plan

1. Package the first-run demo.
   - Add a `scripts/demo-seed.sh` or documented curl sequence that creates a fake Codex session, tool event, and manual observation.
   - Keep the demo data free of private paths, tokens, customer names, and personal session text.
   - Verify the UI shows a useful title seeded from the first prompt.

2. Harden docs for public readers.
   - Add a short architecture diagram to README.
   - Split local-machine operator notes from public setup docs.
   - Document supported ingestion paths: HTTP, CLI, hook, MCP.
   - Add a privacy section explaining what is stored and what is not redacted yet.

3. Add title behavior coverage.
   - Test metadata title wins.
   - Test first-line prompt title seeding.
   - Test tool fallback.
   - Test title truncation.
   - Explicitly document that later events do not retitle existing sessions today.

4. Add showcase assets.
   - Capture one clean screenshot of the session list and timeline using demo data.
   - Add it under a public-safe docs/assets path.
   - Reference it from README.

5. Decide the next product slice.
   - Option A: explicit retitle endpoint or MCP tool.
   - Option B: early-session retitle rules that replace weak fallback titles only.
   - Option C: local AI title suggestion with user-visible confirmation.

## Verification Checklist

- `mvn test`
- `mvn -q -DskipTests package`
- `curl -fsS http://localhost:8766/api/status | jq`
- Demo event write through `/api/events`
- Demo search through `/api/search`
- Hook smoke test through `scripts/hooks/sba-agent-hook.sh`
- `git status --short` is clean except intentional handoff branch state

## Git Handoff Flow

Use this repo flow for the showcase prep:

1. Commit the current docs and plan on a `codex/...` branch.
2. Push the branch and open a PR against `main`.
3. Merge the PR after local verification.
4. Pull or fast-forward `main`.
5. Create the next handoff branch from updated `main`:

```bash
git switch main
git pull --ff-only
git switch -c codex/sba-agentic-showcase-handoff
git push -u origin codex/sba-agentic-showcase-handoff
```

## Next Session Prompt

Use this when starting the next implementation session:

```text
We are preparing /Users/nathan/Developer/proj/sba-agentic for a public showcase repo. Start from PLAN.md. Keep local hook config opt-in and do not commit private .codex/.claude/database/IDE state. First package a demo-safe seed flow, add tests for title seeding behavior, and update README with a public quickstart, architecture sketch, privacy boundary, and screenshot plan. Verify with mvn test and a local API smoke test.
```
