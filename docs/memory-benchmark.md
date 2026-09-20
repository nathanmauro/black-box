# Testing whether Black Box memory helps

The Docker benchmark compares a fixed Codex agent on the same synthetic coding tasks with three
different context conditions. It evaluates existing capture and recall. It does not implement
Dream-RSI, train a model, optimize a policy, or establish general productivity gains.

| Arm | Supplied history |
| --- | --- |
| `bare` | Empty history file |
| `history` | Plain JSON containing the complete synthetic history across all fixture projects |
| `blackbox` | The same source evidence filtered through real project-scoped Black Box recall |

Every arm receives the same task prompt, current contract, starter code, public test, model, effort,
and timeout. The history file's contents are the only intentional prompt-context difference.
Trials use fresh containers; no preceding conversation, personal instructions, skills, plugins,
hooks, MCP configuration, or memory is mounted. Codex's built-in instructions and tools remain.
The benchmark audits rendered startup context and fails before inference if personal-context
markers appear. This is a clean Codex comparison, not a raw-model/API comparison.

Codex's bundled system skills require explicit per-skill exclusions even when host discovery and
plugins are disabled. For the pinned CLI, the exclusion paths end in `SKILL.md`. The initial audit
caught this distinction before inference; unknown skills introduced by another CLI version cause
the same fail-closed audit rather than silently entering the experiment.

## Requirements and commands

Docker must be running. The host needs Python 3.9+, Java 21, and Maven 3.9+. The worker image pins
Codex CLI 0.155.0; `--codex-version` selects another version when building. Initial builds download
runtime dependencies. Real model runs use an existing Codex sign-in and consume its normal usage.
No OpenAI Platform API key is required.

From the repository root:

```bash
# Inspect the nine-trial schedule. No files, containers, or model calls.
python3 scripts/benchmarks/blackbox_memory/benchmark.py plan --model gpt-6-astra

# Test benchmark logic without Docker or model calls.
python3 -m unittest discover -s scripts/benchmarks/blackbox_memory -p 'test_*.py' -v

# Build isolated images and test capture -> recall -> task setup -> grading -> report.
# Authored reference code replaces the model; this is infrastructure evidence only.
python3 scripts/benchmarks/blackbox_memory/benchmark.py smoke

# Run one repetition of all three tasks and three arms (nine fresh agent sessions).
python3 scripts/benchmarks/blackbox_memory/benchmark.py run \
  --skip-build --model gpt-6-astra --effort medium --repeats 1
```

Use an available model explicitly; there is no model fallback. Increase `--repeats` only when
ready to spend the additional model budget. `--tasks pagination` or `--arms bare blackbox` narrows
a run. `--timeout 180` bounds each agent invocation. `--seed` controls paired fixture variants and
execution order. `--output` chooses a **new** result directory; existing directories are refused.
The default location is ignored `target/benchmarks/<run-id>/`.

`--skip-build` uses existing local benchmark images and records their immutable image IDs. It does
not claim those images reflect the current checkout. Omit it after changing server code or the
worker Dockerfile. Python fixtures/controller/grader are loaded from the current checkout in either
case and their hashes are recorded. The script never invokes a deployment command or rebuilds the
JAR used by the normal local service.

The default auth path is the current Codex home's `auth.json`. `--auth-file` selects another existing
sign-in file. It is mounted read-only only into model workers; the benchmark does not print or copy
it into reports. Container readability is checked without reading its value into tool output. A
keyring-only sign-in or an unreadable/expired auth file may need a normal Codex login or an explicit
readable auth path; the benchmark never changes permissions on the host credential.

## Tasks and historical evidence

- **Pagination:** continue across empty/nonmatching pages and treat cursor strings as opaque.
- **Invoice rounding:** exact decimal arithmetic, rounding each line before summation, and credits.
- **Changed contract:** a historical globally unique identifier rule is valid in v1 but wrong in
  the current multi-tenant v2 contract. This tests whether prior success overrides current evidence.

Histories are **authored synthetic fixtures**, not transcripts of prior autonomous model runs.
Before capturing them, the harness executes a deliberately broken historical implementation and
a corrected implementation against the historical contract. It refuses to proceed unless failure
and success are reproduced. It then stores a Decision and Handoff through the real REST API and
verifies both can be recalled under the correct project. No current candidate results are added to
that corpus during a comparison. Training/history fixture seeds differ from current task seeds.

The history arm receives all of these same records, rendered with the same fields as the recall
arm. Thus it is a simple baseline for preserving information without a recall service. With only
three tiny projects this is a modest distractor test, not a large-corpus retrieval benchmark.

## Isolation and grading

The server is built from a copy of `pom.xml` and `src/` in a temporary directory. Its container uses
fresh SQLite in its writable layer, an automatically assigned loopback-only host port, and disabled
Elasticsearch, local AI, memory embeddings, Ask embeddings, and editor integration. Summary backend
is `local` with local AI disabled. Only structured Decision/Handoff capture is used; no transcript
ingest, session-stop, summarization, or runner operation is invoked.

Each worker receives only its synthetic workspace and the read-only auth-file mount. It has no
host home, repository, Docker socket, database, sibling workspace, or result-directory mount.
Docker applies CPU, memory, PID, and capability limits. Codex uses its unrestricted **in-container**
execution mode because Docker supplies the filesystem boundary; this does not grant host access.
Network remains available for Codex authentication and inference. Web search is disabled, and the
task asks the agent not to use network tools or install dependencies. This is not a network egress
allowlist or an adversarial credential-security benchmark.

After the worker exits, only `solution.py` is copied to a separate, credential-free,
network-disabled execution container. Public tests cannot overwrite the hidden grader. Expected
answers remain in the host controller and are never sent to either container. The execution adapter
receives test inputs and returns actual values; the controller compares them with expected values.
Syntax errors, exceptions, debug prints, and candidate grading timeouts are handled as candidate
failures. Docker/startup/recall/auth errors are reported separately and stop further model spending.
This is a cooperative coding benchmark; inspect submitted code before drawing conclusions about
adversarial robustness or resistance to deliberate instrumentation tampering.

Containers are uniquely named, labeled, and removed on normal exit, exceptions, or Ctrl-C. Only
containers created by that invocation are removed. A hard process kill or Docker outage can prevent
cleanup; use the run ID in `manifest.json` to inspect matching `blackbox.benchmark` labels. Images
remain cached for reuse. No broad Docker pruning or host service changes are performed.

## Reading the results

Each result directory includes:

- `manifest.json`: schedule, seed, model/effort, image IDs, CLI version, source revision/dirty state,
  controller hashes, and (when built in this run) JAR hash.
- `history/`: executed historical variants, grades, and captured/recalled evidence IDs.
- `trials/`: exact supplied prompt/files, rendered context audit, raw Codex events, answer,
  candidate code, hidden grade, and result JSON for each attempted trial.
- `report.md` and `report.json`: pass rates, per-task outcomes, token usage, latency, and errors.

Model usage comes from Codex's reported events. Missing usage is `null`/unknown, never zero.
Input tokens include context and cached-input tokens are retained per trial. Failed shell commands
are counted, but are not automatically labeled repeated reasoning mistakes. Retrieval latency and
history byte size are recorded separately; total trial time includes retrieval and container work.
No dollar estimate is inferred. Startup/build costs remain separate from model usage.

All scheduled trials remain in the report, including not-run trials after an infrastructure failure.
Do not interpret an authentication failure as a failed coding solution. Smoke reports explicitly
say no model was used. A one-repeat run is a pipeline pilot, not evidence of statistical significance.
If every arm passes, these tasks may simply be too easy to reveal a memory benefit. Compare paired
outcomes and costs across additional repetitions before making performance claims.

This first benchmark preloads scoped **lexical** recall. It does not test semantic ranking, whether
an agent independently chooses to call MCP, learned outcomes from real earlier agents, instruction
ablation, or policy evolution. Useful next experiments are harder held-out tasks, real prior-agent
attempts with outcome links, stale/irrelevant-memory controls, and a separate tool-use evaluation.

## Initial pilot: 2026-09-18

GPT-6 Astra, medium effort, CLI 0.155.0, seed 42, one repetition per task/arm:

| Arm | Verified tasks | Reported input tokens, including cached | Output tokens | Shell commands |
| --- | --- | --- | --- | --- |
| Bare | 3 / 3 | 107441 | 1626 | 12 |
| Plain history | 3 / 3 | 121324 | 2038 | 12 |
| Black Box recall | 3 / 3 | 117780 | 1886 | 12 |

All workers passed the context audit, all task inputs matched across arms, all candidates passed
the private checks, and no shell-command failures were observed. Expected answers were absent
from execution-container inputs. The benchmark's 18 unit tests, nine Docker infrastructure smoke
trials, and three explicit candidate-error grading probes also passed.

This small run found no correctness advantage for recall. Recall used more input/output tokens
than bare execution and fewer than complete history. These totals are observations, not dollar
costs or evidence of a repeatable efficiency effect. The immediate next step is a harder task set
and measured real-agent histories, rather than claiming the first pilot proves memory helps.

## Continuation study

`continuation.py` adds measured predecessor-agent histories and a development difficulty gate:

```bash
# Read-only schedule: five development runs, then sixty conditional evaluation runs.
python3 scripts/benchmarks/blackbox_memory/continuation.py plan --model gpt-6-astra

# Reference-code infrastructure check; skips the model difficulty gate deliberately.
python3 scripts/benchmarks/blackbox_memory/continuation.py smoke --repeats 1

# Uses the existing benchmark images and the signed-in Codex CLI.
python3 scripts/benchmarks/blackbox_memory/continuation.py run \
  --model gpt-6-astra --effort medium --timeout 180 --repeats 3
```

Build the two benchmark images with the original smoke command first if they are absent. The
continuation runner records the reused image IDs and explicitly does not claim they reflect
current server source. It copies the Python controller sources into each result directory and
uses the frozen grader copy throughout that run. Working-source drift aborts subsequent trials.

The five miniature repository scenarios are resumable exports, webhook ledger replay, invoice
adjustments, configuration inheritance, and migration routing. Each contains a Python module,
public tests, and protocol/change documents. All requirements are available in every arm; memory
never supplies an otherwise unknowable requirement. Webhook v2 explicitly changes the historical
global event identity to a tenant-scoped identity, testing resistance to obsolete advice.

First, Codex repairs each predecessor task with public historical regression examples. Its code,
commands, explanation and independent external grade are retained. A failed predecessor gets at
most one fresh repair attempt. All continuation conditions inherit the identical resulting code,
including unresolved errors. Before proceeding, the runner proves this checkpoint does not
already satisfy the continuation. Authored reference solutions are used only in smoke mode.

| Condition | Context |
| --- | --- |
| `bare` | Empty history |
| `handoff` | The predecessor's actual final explanation and measured historical pass status |
| `blackbox` | Exactly that evidence, captured and retrieved through isolated scoped REST recall |
| `misleading` | A deliberately fabricated conflicting historical claim |

Plain handoff and Black Box evidence must match byte-for-byte. This is a strong handoff baseline,
not an all-project dump. Their comparison tests capture/recall fidelity; it cannot demonstrate
retrieval superiority. The misleading control is labeled and recorded in the private manifest,
not represented in the report as real prior experience. It is shorter than the real handoff, so
it is a stress test rather than a length-matched relevance experiment. Nothing from this corpus
is written to the live Black Box service.

The five bare development continuations use a separate seed. At **1–4 passes out of five**, the
runner proceeds automatically to the scheduled evaluation. At zero or five passes it stops with
`floor_detected` or `ceiling_detected`, retaining all evidence and leaving the 60 evaluation rows
explicitly unrun. This gate is fixed before execution, does not select tasks based on memory wins,
and avoids spending the full model budget on an uninformative task set.

Evaluation randomizes scenario/repetition blocks and arm order within each block. All four arms
share the same task inputs, inherited code, model, effort and timeout. Primary success requires
every private check within the fixed model budget. A timeout fails that outcome even if its
partially saved checkpoint later passes grading. Infrastructure failures stop further inference.
Partial command traces and checkpoints are retained on timeouts; missing token usage stays unknown.

Reports include scheduled denominators, individual checks, paired wins/losses against bare,
tokens, model latency, retrieval overhead, and separate history-production cost. They verify
paired input hashes and useful-history byte equality. Expected answers remain on the host;
neither the model worker nor candidate-execution container receives them. No continuation grade
or model response becomes history for another trial.

This remains an exploratory study of **five authored scenario families**, each sharing one frozen
predecessor history across repetitions. Different seeds vary data identifiers, not whole problem
architectures. Sixty runs would not mean sixty independent problems. A ceiling result calls for
more realistic repository continuations, not a claim that memory can never help. No policy is
revised and no model weights are changed by this experiment.

### First continuation run: 2026-09-18

GPT-6 Astra, medium effort, CLI 0.155.0, 180-second model budget per attempt:

| Phase | Result | Reported input tokens, including cached | Output tokens |
| --- | --- | --- | --- |
| Actual predecessor agents | 5 / 5 historical tasks passed | 290599 | 5512 |
| Bare development continuations | 5 / 5 passed | 309711 | 8843 |
| Four-condition evaluation | Not run: difficulty gate detected a ceiling | Unknown / not consumed | Unknown / not consumed |

Each inherited predecessor checkpoint initially failed 5–7 continuation checks. The follow-up
agents therefore did real extension work; they did not inherit already-complete solutions.
All five bare continuations nevertheless passed within budget. The gate returned
`ceiling_detected` and retained all 60 evaluation rows as `not_run`. They are not 60 failures,
and there are no measured handoff/Black Box/misleading continuation results from this run.

The result does not establish a correctness or efficiency advantage for memory. This small,
fully documented task set still leaves no room for a correctness improvement at the selected
model and budget. Efficiency comparisons were not run. The next stronger experiment should use
actual repository maintenance cases with pre-fix snapshots and evidence distributed across
implementation, tests and prior work, rather than tuning these cases until memory wins.

Artifacts are retained locally under ignored `target/benchmarks/continuation-study-v1/`, including
the frozen controller sources, predecessor histories, all ten real worker traces, candidate
implementations, external grades, schedule and gate result. Infrastructure verification also
passed: 27 Python tests and a Docker smoke covering five histories, five screens and 20 evaluation
conditions using authored reference code. The ordinary local service was not deployed or restarted.
