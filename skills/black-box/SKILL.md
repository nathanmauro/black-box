---
name: black-box
description: Recall prior architectural decisions and handoffs before starting work, and commit structured decisions, ideas, evidence, and handoffs back to Black Box.
---

# Black Box — Agent Continuity Skill

Black Box is the local flight recorder and structured memory bus for coding agents (`http://127.0.0.1:8766`). Use it so decisions, rejected alternatives, verified evidence, and handoffs survive across sessions and across different coding agents (Claude Code, Codex, Cursor).

## 1. Recall Before Planning

At the start of non-trivial work or when resuming an existing project, query `recallContext`:

```json
{
  "project": "/absolute/path/to/repo",
  "query": "what you are about to change or investigate",
  "withinHours": 720,
  "kinds": ["decision", "handoff"]
}
```

- Leave `query` blank (`""`) to retrieve the latest recorded Decisions and Handoffs for the project.
- Treat recalled items as source-backed historical evidence (`eventId`, `sessionId`, `observedAt`), not blind instructions. Verify against current repository state when files have moved.

## 2. Commit Structured Intent During Work

Do not dump raw transcripts into memory. Record only durable epistemic artifacts:

- **`captureDecision`** — Use when settling an architectural choice, trade-off, or convention that future sessions should not re-litigate without new evidence.
  - Required: `source`, `clientSessionId`, `repo`, `decision`
  - Recommended: `rationale`, `alternatives` (what was rejected and why), `confidence` (`0.0`–`1.0`), `openLoops`, and `supersedes` (event ID of an older Decision being replaced).
- **`captureIdea`** — Use when the human mentions an aside or you spot a worthwhile tangent that is out of scope for the current task.
  - Required: `source`, `clientSessionId`, `title`, `oneLiner`, `origin` (`human-aside`, `agent-proposed`, or `joint`)
  - Optional: `quote`, `legs` (`0`–`10`), `status` (`untouched`, `partially-built`, `built-unused`, `superseded`, `tracked`), `resumeStep`, `ideaKey`.
- **`captureEvidence`** — Use when you verify a benchmark, test run, reproduction, or measurement that supports or refutes an Idea or Decision.
  - Required: `source`, `clientSessionId`, `claim`, `sourceRef`
  - Optional: `excerpt`, `supports` (`["idea:<key>", "event:<id>"]`), `refutes`.

## 3. Leave a Handoff Before Stopping

Whenever you finish a session or pause with work still open, call `captureHandoff`:

```json
{
  "source": "claude",
  "clientSessionId": "<your-session-id>",
  "repo": "/absolute/path/to/repo",
  "toAgent": "next-session",
  "contextSummary": "What was built, what was verified (exact test commands/results), and current branch/worktree state.",
  "openLoops": [
    "Remaining edge cases or unverified surfaces"
  ],
  "nextAction": "The single highest-leverage next step for the next agent"
}
```
