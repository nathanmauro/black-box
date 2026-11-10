# Hook normalization process reduction

The capture bridge now parses its seven small identity fields together. It retains the separate
large-text path, raw-text fallback, final event projection, and durable sanitizer. This removes six
`jq` launches and six repeated full-payload parses per invocation without changing capture fields.

The identities travel through an anonymous pipe with NUL delimiters, never through shell evaluation.
Trailing newlines and embedded NUL bytes retain the former Bash command-substitution behavior.
Large assistant/tool text remains outside the delimiter-reading loop. Durable raw input still stays
in memory and pipes until sanitization.

## Reproduce

From the repository root, with Bash, Python 3.9+, and jq installed:

```bash
python3 -B scripts/benchmarks/hook_normalization.py --baseline-ref 9c042984 --runs 12
scripts/test-agent-hook.sh
mvn -Dtest=ClaudeStopHookHttpTest test
```

Use Java 21 for Maven. The benchmark reads the baseline hook and outbox from Git into a temporary
directory. Direct capture substitutes a fake curl; durable capture uses a temporary outbox and a
loopback HTTP acknowledgement server. Neither mode writes to a running Black Box instance.

Each case has two warmup runs per version, then 12 measured runs, alternating baseline/candidate
order. Timings include the actual shell hook and, for durable mode, supervisor, sanitizer, queue,
and acknowledgement. A separate instrumented invocation counts jq launches; the counting shim is
not present during timing. Every delivered event is compared after removing only `observedAt`.
The 512 KiB durable fixture retains its existing sanitization/truncation behavior.

## Observed results

Measured on macOS arm64 with Bash 5.3, jq 1.8.2, and Python 3.14. Other development work was running
on the machine. These are paired synthetic measurements, not production latency guarantees.

| Mode    | Assistant text | Baseline median | Candidate median | jq launches |
| ------- | -------------- | --------------: | ---------------: | ----------- |
| Direct  | 100 bytes      |      140.590 ms |        89.650 ms | 11 → 5      |
| Direct  | 512 KiB        |      320.528 ms |       242.650 ms | 11 → 5      |
| Durable | 100 bytes      |      366.019 ms |       349.878 ms | 10 → 4      |
| Durable | 512 KiB        |      640.729 ms |       505.638 ms | 10 → 4      |

Small durable captures are dominated by work outside identity extraction; their timing difference
is modest. The deterministic result is six fewer jq processes in either mode.

## Compatibility checks

The hook suite covers non-JSON input, BOMs, Unicode, tool payloads above `ARG_MAX`, strings above
the recorder parser limit, field precedence, child identity, provenance, redaction, and durable
deadline/retry behavior. Added regressions cover interior/trailing newlines, NUL bytes, numeric
identities, and empty aliases. The raw-input audit still requires every observed jq stdin to be
a pipe rather than a regular temporary file; it no longer assumes redundant parser invocations.

`ClaudeStopHookHttpTest` exercises the actual hook against a disposable HTTP recorder and SQLite,
then checks both search APIs and the transcript without local JSONL, in direct and durable modes.
No timing threshold is used as a correctness assertion.
