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

## Offline Java repository-fixture qualification

Before adding real Java repository continuations, qualify their staging and behavioral grader
without running an agent. The separate development qualifier supports exactly **four fixed public
repairs**. The default
`structured-redaction` fixture uses baseline `5d76086eeb0d423207e0f5560b3ae1aa1f9bebc8`, reference
`d833fa96942a558cc7bc453b504656a2df41148f`. The `summary-export` fixture uses baseline
`9933ade549c37af5d784edff650f74705d55fa83` and reference
`16ce9f343706d1818f0b73e67043e6e84a1104e0`. The `event-chronology` fixture uses baseline
`a2f969585dc5b780b3dc4b0611a4084ec7efd0aa` and reference
`aac7a30230687e795f844a971c72ebd5fd393e5c`. The `capture-ack` fixture uses baseline
`5d76086eeb0d423207e0f5560b3ae1aa1f9bebc8` and reference
`596ccf62a99416f14acf0ca24f91a928bc08ed40`. The selected commit objects must already exist locally.
The qualifier does not fetch history, accept arbitrary revisions/candidates, change existing benchmark
runs, or provision Docker. These familiar published bugs are development fixtures, not held-out
difficulty cases.

```bash
# Read-only pin/hash/allowlist verification. No output files, builds, or model calls.
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py plan

# Unit/fake contracts; no Docker, model, or Maven builds.
python3 -m unittest discover -s scripts/benchmarks/blackbox_memory -p 'test_repository_fixture.py' -v

# Run the fixed reviewed baseline/reference through six trusted pure-Java assertions.
# Requires Java 21, Maven, and already-cached dependencies for these historical sources.
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py verify --execute

# Select the second reviewed fixture; all seven filesystem/controller checks are mandatory.
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py plan --fixture summary-export
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py verify --execute --fixture summary-export

# Select the third reviewed fixture; all seven canonical SQLite feed checks are mandatory.
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py plan --fixture event-chronology
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py verify --execute --fixture event-chronology

# Select the fourth reviewed fixture; all eight capture-acknowledgement checks are mandatory.
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py plan --fixture capture-ack
python3 scripts/benchmarks/blackbox_memory/repository_fixture.py verify --execute --fixture capture-ack
```

`--maven-repo` selects an existing local artifact cache; the default is `~/.m2/repository`.
`--timeout` bounds each build (180 seconds by default). Missing objects, tooling, or cached
dependencies fail explicitly; the qualifier never downloads dependencies or installs tools.
An optional `--output` writes a new JSON report with owner-only permissions. Its parent must exist,
and existing files or symbolic-link path components are refused. The default prints JSON only.

The worker-facing export contains tracked baseline `pom.xml`, README, license, Java sources/resources
and public Java tests, plus the fixture task text. It contains no Git metadata, post-fix history/docs,
reference patch, or private regression grader. Baseline and reference grading use separate private
copies. Only the reviewed production-source overlay crosses into the reference grading copy;
identical controller-owned tests are added to both. Fixture/build/source hashes, exact reference-path
allowlists, and grading-input hashes fail closed on drift. Only bytes already validated against the
pinned grader/task digests are staged; the full expected source/build/grader inventory is checked
before Maven starts and again after it exits. Existing candidate-execution isolation in
the Docker benchmark is unchanged; this native path is **only for reviewed authored revisions**, not
an adversarial sandbox or a way to execute model submissions on the host.

The fixed Maven recipe is offline, uses isolated empty settings/home, bypasses Maven startup rc files,
and strips inherited provider, proxy, Spring, Maven and JVM options. Every Maven invocation, including
the Java-version preflight, runs from an owned directory with an empty `.mvn` and explicit
`MAVEN_BASEDIR`, so caller/ancestor Maven configuration is not discovered. The redaction grader
directly instantiates the redactor. The
summary-export grader uses the existing controller through standalone MockMvc and the real export
service with an in-memory catalog and controlled templates. Neither starts Spring Boot, a provider,
a listening server or a database, and neither reads authentication files or existing notes. The export
grader places its entire filesystem fixture under its private grading `target/` directory.
The chronology grader uses the existing recording API, canonical schema, fixed clock and explicit
transactions with its own SQLite file under that directory. It starts no Spring Boot context,
listening server or provider and never opens an installed database. Temporary
source/build trees are removed on normal completion and handled failure; owned
process groups are killed on timeout/interruption. A hard process/host crash can leave temporary data.
Reports retain only fixed labels, reviewed revision/content hashes, test outcomes and timings; raw
build logs and source trees are not retained.

Structured-redaction qualification requires all six named tests to execute: the baseline must fail
exactly three known
behavioral assertions while passing three preservation checks; the reference must pass all six.
Compiler/dependency/tooling failures, errors, skipped/zero/duplicate/unexpected tests, inconsistent
exit codes and altered inputs cannot masquerade as reproduced behavior. Actual offline checks
reproduced those three baseline failures and all six reference passes, in roughly five seconds per
build, including a run from deliberately contaminated caller/ancestor Maven configuration and JVM
options. An empty-cache run failed explicitly with `offline_dependency_unavailable`. Temporary trees
were cleaned in both cases. This verifies fixture infrastructure, not task difficulty or a memory benefit.

Every result remains `infrastructure_only` with `usefulness_gate.status = not_cleared`, zero model
runs and zero observed accepted actions. Future studies still require twenty adjudicated historical
candidates, five resumed tasks, a same-model/budget/source-window ordinary latest-handoff/search
comparator, at least 70% useful supported suggestions, below 10% stale/duplicate suggestions, and
three observed useful accepted actions that comparator missed. The existing five-task development
screen (continue only at 1–4 bare passes) is unchanged. This qualifier supplies none of those missing
outcomes and makes no efficacy claim.

The summary-export fixture requires exactly seven named tests. Its baseline must fail three
behavioral assertions: descendant-directory symlink containment, final-note symlink containment and
preservation of an outside hard-link alias. Both snapshots must preserve ordinary/repeated exports
with an explicitly assigned mode, intentionally configured root aliases, existing notes on template
failure, and parent-directory traversal rejection. The reference must pass all seven. All files,
including the deliberately outside-target sentinels, belong to one private temporary fixture.
POSIX modes, symbolic/hard links, stable file identities and atomic sibling replacement are required;
unavailable capabilities report an infrastructure failure, never a skipped or successful grade.
The grader uses no new write/publication injection seams. It does not establish interrupted-write or
crash durability, adversarial concurrent rename resistance, task difficulty or continuation efficacy.

The second fixture was also qualified offline on Java 21: exactly three expected baseline assertion
failures, four baseline preservation passes, and seven reference passes. Both fixture selections
passed from a deliberately contaminated caller/ancestor Maven configuration with inherited JVM and
Maven options; an empty cache failed explicitly, and owned temporary trees were removed. This is
fixture infrastructure evidence only; no model continuation or accepted-action study was run.

The event-chronology fixture requires seven named feed checks. Its baseline must fail fractional
first-page ordering, mixed-precision bounded pagination and inclusive nanosecond windows. Both
snapshots must preserve timestamp text, equal-instant ID ties, conjunctive project/source/query
bounds and invalid-cursor rejection without mutation. The reference passes all seven. Expected
order comes from an independent Java Instant oracle, with cursor progress, unique IDs and bounded
termination checked across pages. The reviewed overlay includes two changed production sources
and one new helper; this does not extend the grader’s claims to all search/recall or PostgreSQL.

Actual offline replay reproduced three expected baseline failures and four preservation passes,
then seven reference passes. All three fixture pairs passed again with contaminated caller Maven
settings; an empty cache failed explicitly. The original two worker-input hashes remain unchanged.
The [chronology report](evaluation-results/2026-10-03-event-chronology-qualification.json) records
these infrastructure outcomes, with zero model runs and accepted actions.

The capture-ack fixture is a **potentially easy control**: the baseline already had a guarded
optional-publication helper, and the reference reuses it for ordinary capture. It requires eight
named checks against the real ingestion service and a private SQLite `RecordingSqlStore` reached
through the store's own Spring transaction interceptor; only optional publication is a fixture. The
baseline must fail four acknowledgement checks: a committed capture is acknowledged after its
`EventRecorded` publication throws, and terminal captures attempt `SessionStopped` independently
when either or both publications throw. An independent SQLite connection must see the committed
event, with no active transaction, before each publication. Both snapshots must preserve the
acknowledgement shape, append-only ordinary retries, terminal publication order, and rejection with
session/event rollback and no publication for an unserializable payload or a failed database write.
Only `EventIngestService` is overlaid. The grader qualifies the service commit boundary only; the
REST/MCP acknowledgement claim, PostgreSQL and lost-response idempotency remain unverified by it.

Actual offline replay reproduced the four expected baseline failures and four preservation passes,
then eight reference passes. The three earlier fixtures were replayed with unchanged worker-input
hashes. The [capture-ack report](evaluation-results/2026-10-03-capture-ack-qualification.json)
records these infrastructure outcomes, with zero model runs and accepted actions.

## Next comparison preparation

The [proposed continuation comparison protocol](continuation-comparison-protocol.md) records a
17-candidate familiar development inventory with exact pre-fix/reference commits and evidence
paths. Summary-export and event-chronology have the seven-check offline qualifications described
above, and capture-ack the eight-check easy-control qualification, making three qualified inventory
members in three distinct clusters. Structured-redaction is outside that inventory.
No model trials or human accepted actions were established. The existing usefulness and difficulty
gates stay unchanged.

### Offline handoff and literal-search adapter

`history_search.py` is a deterministic, standard-library adapter for the comparison's ordinary
arm: it validates one hash-pinned frozen corpus, delivers the latest eligible handoff once, then
answers at most six literal searches inside a 6,000-byte-per-delivery, 24,000-byte total budget,
using one shared delivery envelope. Its full matching, ranking, excerpt, budget and end-of-budget
contract is in the [protocol](continuation-comparison-protocol.md#offline-literal-adapter).

```bash
# Unit tests plus subprocess CLI journeys over synthetic temporary corpora only.
python3 -m unittest discover -s scripts/benchmarks/blackbox_memory -p 'test_history_search.py' -v

# Validate a frozen corpus, then serve one session over stdin/stdout JSON lines.
python3 scripts/benchmarks/blackbox_memory/history_search.py validate --manifest M --manifest-sha256 SHA
python3 scripts/benchmarks/blackbox_memory/history_search.py serve --manifest M --manifest-sha256 SHA
```

It reads only the selected manifest and its sibling items file: no live history, transcripts,
databases, credentials, servers, providers or network. Valid hashes and in-range dates do not prove
historical availability; chronology remains a registration requirement. The CLI is an offline
single-process demonstration, not a deployed tool or anti-tamper sandbox; a future runner must
hold the one session and prevent restart or alternate history access. It is not wired into
`benchmark.py`, `continuation.py` or any model loop, changes no arm, budget or gate, and is
infrastructure evidence only. The Black Box backend for the same envelope, an authentic corpus
builder, registration/adjudication and the difficulty and accepted-action studies remain outstanding.

The session now delivers through a small backend seam. A golden trace pins literal delivery bytes
to the pre-seam code. A compact-search backend over `/api/search/compact` was assessed and not
built. Complete match traversal, exact totals beyond the candidate ceiling, deterministic replay
ties and arbitrary literal `"`, `%` and `_` terms are unavailable from that endpoint. See the [blocker](continuation-comparison-protocol.md#backend-seam-and-the-compact-search-blocker).
