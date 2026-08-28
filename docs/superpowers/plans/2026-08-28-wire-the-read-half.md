# Wire The Read Half — plan (2026-08-28)

**Why.** The 2026-08-28 audit (Black Box Observation `987bb5fc`, 22-agent workflow over a
`.backup` snapshot of the live corpus) found the write half of the loop working and the read half
never firing on its own: `scripts/hooks/sba-recall-hook.sh` has existed since 2026-06-10 and is
wired into no SessionStart hook; its 168h default returns 0 items for this repo; the server does
not log recalls; four hand-called `recallContext` results were rejected by Claude Code's ~100k-char
tool-result cap. Every adversarial reviewer converged on the same first move: wire what exists,
bound it, log each fire, measure for two weeks — before building any new backend.

**Slice (this branch).** Repo side only.

1. `scripts/hooks/sba-recall-hook.sh`
   - Defaults: `SBA_RECALL_WITHIN_HOURS` 168 → 720, `SBA_RECALL_LIMIT` 5 → 3 (`MAX_CHARS` stays 4000).
   - Pass `limit` to `/api/recall` so the server bounds the payload too.
   - Works unchanged for Claude Code and Codex `SessionStart` (plain stdout is injected as context by
     both). Optional `--client <name>` argument labels the log only.
   - Skip (exit 0, no output) when the payload `source` is `compact` (re-injection after compaction).
   - Append one TSV line per invocation to `${SBA_RECALL_LOG:-$HOME/.blackbox/recall.log}`:
     `ts, client, outcome, cwd, session_id, items, chars`. Outcomes: `ok`, `empty`, `unreachable`,
     `skipped:<reason>`. Logging must never fail the host turn.
2. `recallContext` (MCP only): optional `maxChars` (default 24,000). The adapter trims long text
   fields with a visible `… (+N chars)` suffix and drops trailing items until the result fits;
   `RecallResult` gains `truncated` (false on the REST path). Honest compression, no silent caps.
3. README: the recall hook is for Claude Code **and** Codex `SessionStart` (Codex has had
   SessionStart hooks since rust-v0.114.0, 2026-03-11); document both stanzas and the fire log;
   retire "Codex sessions should call `recallContext` by hand".
4. Tests: `scripts/test-recall-hook.sh` (fake-curl fixture like `test-agent-hook.sh`);
   `MemoryMcpToolsTest` clamp cases; contract snapshots updated.

**Deferred (explicitly).** Persisting `Recall` as a ledger event; `/api/resume`; bi-temporal
supersession; `query` separate from `scope`; SubagentStop auto-Handoff; CI re-enable (repo-level
GitHub setting, Nathan); editing `~/.claude/settings.json` / `~/.codex/hooks.json` (Nathan applies).

**Success metric.** After two weeks of `~/.blackbox/recall.log`: share of interactive session starts
that received a non-empty packet, per client, and whether agents act on it (handoff open loops
picked up, decisions not re-litigated).
