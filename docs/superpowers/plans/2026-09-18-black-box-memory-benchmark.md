# Black Box memory benchmark

## Question and scope

Does recalling structured prior experience improve a fixed coding agent's verified results or
resource use compared with no history and a plain history file? This is a synthetic pilot of
existing capture/recall, not a claim of recursive self-improvement or a new policy optimizer.

## Safety and experiment contract

- Build the current server source in a disposable directory; never replace the served JAR.
- Run a disposable Docker Black Box server with fresh SQLite, an ephemeral loopback port, no host
  home/repo/database mount, and all optional model/embedding/editor integrations disabled.
- Each worker gets a fresh container, only the synthetic task and assigned history, and the one
  read-only Codex authentication file explicitly selected for the signed-in CLI pilot. No host
  skills, instructions, memory, hooks, MCP config, or sibling trials are mounted.
- Run hidden grading in a separate network-disabled container with no credentials. Only the
  candidate solution file crosses from the worker into grading.
- Use identical model, effort, task, test seed, and time budget across arms. Randomize execution
  order with a recorded seed. Never seed a trial's result into another arm's history.
- Generate historical fixture outcomes by actually grading authored failing and reference
  solutions. Label these as synthetic authored experience, not prior autonomous agent learning.
- Record pass/fail, named regression checks, tool/failed-command counts, reported tokens, latency,
  retrieval overhead, input hashes, source/JAR/image provenance, and failure state. Missing usage
  is unknown, never zero. Retain failures in denominators; do not claim significance from a pilot.
- Default to a plan/dry run. Separate explicit smoke and model-run commands. No publishing,
  deployment, production captures, or edits to agent policy files.

## Independently verifiable slices

1. Add three deterministic Python tasks: cursor pagination, decimal invoice rounding, and a
   changed deduplication contract where prior advice is stale. Add private grading and tests.
2. Add isolated server/worker/grader lifecycle, three-arm scheduling, artifact reporting, and
   unit tests for experiment correctness, cleanup, and error classification.
3. Exercise the full pipeline without a model, then run a bounded signed-in Codex pilot.
4. Document commands, evidence boundaries, observed results, and the next useful experiment.

## Status

- Initial inspection: Docker is available; native Codex CLI is 0.155.0. Nathan selected the
  signed-in CLI for real model comparisons. Existing runner/demo scripts are useful patterns,
  but the demo rebuilds the live JAR and omits the separate memory-embedding disable switch;
  this benchmark therefore owns a disposable lifecycle.
- Implemented `scripts/benchmarks/blackbox_memory/` plus `docs/memory-benchmark.md` and a README link.
- Verification: 18 Python tests; nine full Docker smoke trials; explicit malformed-code,
  abrupt-exit, and nonserializable-return Docker grading proofs; nine real Codex trials.
- The initial real-run preflight correctly stopped before inference because bundled skills were
  still present. Per-skill exclusions ending in `SKILL.md` fixed this; every pilot worker passed
  the rendered-context audit. General Codex instructions remain in all arms.
- Pilot: GPT-6 Astra, medium effort, seed 42, one repetition. Bare, flat history, and Black Box
  each passed 3/3 tasks. All arms made 12 shell commands and recorded zero failed commands.
  Reported input tokens (including cached input): bare 107441; history 121324; Black Box 117780.
  This establishes pipeline operation, not a memory benefit or statistical significance.
- Evidence is in ignored `target/benchmarks/codex-pilot-clean/`, including `report.md`, raw trial
  artifacts, manifest, and paired-input/context verification. The interrupted preflight and
  separate smoke reports are retained rather than overwritten.
- Cleanup verified: no benchmark-labeled containers remain; the ordinary local API is healthy
  and its served JAR retains its prior modification time. No deployment or publication occurred.
- Next experiment: harder held-out continuation tasks with measured histories produced by real
  agents, then multiple paired repetitions. Treat outcome-link/policy-learning work as a separate
  slice; the current benchmark deliberately exercises existing capture/recall only.
