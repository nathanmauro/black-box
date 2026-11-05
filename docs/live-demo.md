# Live demo

`scripts/live-demo.sh` walks through the write-plus-query loop on a throwaway database. It starts
its own recorder, seeds synthetic history over MCP, and checks every claim it prints. A failed check
stops the run.

## Run it

You need Java 21, Maven, `jq`, `lsof` and `git`. Run from a clean checkout. The script builds the
jar if `target/` has no current one, which takes about a minute the first time.

```bash
./scripts/live-demo.sh
```

The run takes about 30 seconds once the jar exists. It ends by printing `━━ Done`, the final event
count, and the database files the recorder holds open. It then stops the recorder and deletes its
scratch directory.

| Option | Effect | Needs |
| --- | --- | --- |
| `--step` | Waits for Enter between acts, and before teardown so you can open the UI | A terminal |
| `--semantic` | Turns on hybrid recall and adds a recall phrased in different words | Local Ollama with `nomic-embed-text` |
| `--real-agent` | Adds Act 2b, a real headless Claude Code session | A signed-in `claude` CLI; costs a few cents |
| `--keep` | Leaves the recorder running and prints its PID and scratch path | |
| `--skip-boundaries` | Skips Act 4 | |

To present the full version, or to record a run:

```bash
./scripts/live-demo.sh --step --semantic --real-agent
./scripts/live-demo.sh --semantic --real-agent | tee live-demo-run.txt
```

`SBA_DEMO_PORT` changes the port from its default of 18766. Port 8766 is always refused.
`SBA_DEMO_CLAUDE_MODEL` changes the Act 2b model. [`live-demo-run.txt`](live-demo-run.txt) is a
recorded run.

## What each act shows

| Act | What happens | What the script checks |
| --- | --- | --- |
| 0 | Starts a recorder and seeds six captures through `tools/call` | The recorder starts empty, and only scratch database files are open |
| 1 | Agent A writes a decision with three rejected alternatives, then closes its MCP session | The server commits a `Decision` |
| 2 | Agent B opens a new MCP session and recalls with `limit` and `maxChars` | The packet holds A's alternatives and open loop, and leaves out another project and a superseded decision. A tighter budget cuts visibly, and history stays available with `includeSuperseded` |
| 2b | A real Claude Code session is asked about the option agent A rejected | Its first tool call is recall, the packet it received holds A's decision, and its own decision is on record |
| 3 | Lists the tools, sends an invalid capture, and captures a fake credential | The invalid capture writes nothing, and recall returns the credential redacted |
| 4 | Runs Spring Modulith and ArchUnit on a copy of `HEAD`, then adds a class that reaches into another module's `internal` package | The clean copy passes, and the build fails on the violation |

## What it does not show

- Agents A and B are scripted MCP clients with fixed payloads. Only Act 2b launches a model.
- Without `--semantic`, recall is a substring match, so a natural question can return nothing. A
  real agent hit this during testing, which is why the Act 2b prompt names a keyword in that mode.
- `maxChars` covers text fields plus 200 characters per item, not JSON framing. A packet can have
  more JSON bytes than its budget. When the budget runs out, later items are dropped, and a budget
  too small for the first item returns no items with `truncated: true`.
- Seeded events carry the time of the run, because captures cannot be backdated.
- Elasticsearch, summaries, the judge and authentication are off.

## Isolation

The recorder runs under `env -i` with its `HOME` in the scratch directory, so ambient `SBA_*` and
Spring settings cannot reach it. The script reads the recorder's open files with `lsof` and fails if
a database sits outside scratch. Act 2b runs Claude Code with `--restricted`, which ignores user and
project settings, including capture hooks, and with `--strict-mcp-config`, which offers only the
scratch server. Act 4 edits a copy of `HEAD`, never your checkout.
