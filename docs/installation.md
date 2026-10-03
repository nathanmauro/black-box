# Install Black Box

Black Box is a single Java process that serves the web UI, REST API, and MCP endpoint on
`http://127.0.0.1:8766`. SQLite holds its canonical history by default; live database sidecars and
optional outbox or gateway state need separate care when [backing up](database-recovery.md).
This guide covers three ways to get it running, platform support, and what data can leave your machine.

## Choose a path

| Path | Use it when | Needs |
| --- | --- | --- |
| [Release JAR](#run-the-release-jar) | You want the last published build without cloning the repository | Java 21+ |
| [Source build](#build-from-source) | You want the current `main` branch, the hook scripts, or the service templates | Java 21+, Maven 3.9+, Git |
| [Isolated demo](#try-the-isolated-demo) | You want to see capture and recall work before connecting a real agent | Source checkout, Java 21+, Maven 3.9+, Bash, `curl`, `jq`, `lsof` |

The current published release is **v0.2.0** (JAR and `SHA256SUMS`, published 2026-09-20). The
`main` branch is newer than that release: it has removed the task board and runner (see
[board retirement](board-retirement.md)) and includes later UI and recall changes. The README
screenshots show `main`. If you want what the screenshots show, build from source.

Node.js is not needed to run Black Box. The built UI is committed and packaged into the JAR; Node is
only for working on the frontend itself (see [frontend standards](frontend-standards.md)).

## Platform support

| Capability | macOS | Linux | Windows |
| --- | --- | --- | --- |
| Run the server JAR (Java 21) | Supported | Supported | Not qualified; see [manual command](#windows-unverified) |
| Build from source with Maven | Supported (primary development platform) | Supported (CI runs on Ubuntu) | Not qualified |
| `scripts/quickstart.sh` and `scripts/demo.sh` | Supported | Supported (bash, `jq`, `lsof`) | Not supported natively |
| Background service | `launchd` template and `scripts/deploy-local.sh` | `systemd --user` unit template | None supplied |
| Capture and recall hooks (`scripts/hooks/*.sh`) | Supported | Supported | Not supported natively (bash, `jq`, Python `fcntl`) |
| Durable hook outbox to a loopback server | Supported | Supported | Not supported |
| Durable outbox to an HTTPS server | Supported (bearer read from macOS Keychain) | Not available | Not available |
| macOS companion panel (`companion/macos`) | Supported | Not applicable | Not applicable |

"Not qualified" means it is not covered by this repository's documented qualification or CI.
The manual Windows JAR command below is a starting point that still needs verification on Windows.
The shell scripts and hooks assume a POSIX shell, and the durable outbox uses Python `fcntl` file
locking, which Windows Python does not provide.

## Run the release JAR

Download `sba-agentic-0.2.0.jar` and `SHA256SUMS` from the
[v0.2.0 release](https://github.com/nathanmauro/black-box/releases/tag/v0.2.0) into one directory,
then verify and start it:

```bash
shasum -a 256 -c SHA256SUMS        # macOS; on Linux use: sha256sum -c SHA256SUMS
SBA_SUMMARY_BACKEND=local SBA_LOCAL_AI_ENABLED=false \
  SBA_MEMORY_EMBEDDING_ENABLED=false SBA_ASK_EMBEDDING_ENABLED=false \
  java -jar sba-agentic-0.2.0.jar
```

Open <http://127.0.0.1:8766>. These four settings make no model calls: summaries use a compacted
transcript, and recall stays lexical. Without them, a terminal session event schedules a summary
through the default external Codex wrapper (when present), and recall tries embeddings at
`localhost:11434` (see [data leaving your machine](#data-leaving-your-machine)). The v0.2.0 release
notes show a shorter two-variable command that leaves embedding calls to that loopback default enabled. The database is
created as `sba-agentic.db` in the current directory, so start the JAR from a stable directory or set
`SBA_DATASOURCE_URL=jdbc:sqlite:/path/to/blackbox/sba-agentic.db`.

The release JAR does not include the repository's `scripts/` directory: no hooks, summary wrappers,
or service templates. Clone the repository at the `v0.2.0` tag if you want those to match the
release. The [v0.2.0 release notes](releases/v0.2.0.md) describe its features, upgrade steps from
0.1.0, and release boundaries; that release still includes the task board that `main` has since
removed.

### Windows (unverified)

This is a manual, untested equivalent for PowerShell. Compare the printed hash with the line for the
JAR in `SHA256SUMS` yourself.

```powershell
Get-FileHash .\sba-agentic-0.2.0.jar -Algorithm SHA256
$env:SBA_SUMMARY_BACKEND = "local"
$env:SBA_LOCAL_AI_ENABLED = "false"
$env:SBA_MEMORY_EMBEDDING_ENABLED = "false"
$env:SBA_ASK_EMBEDDING_ENABLED = "false"
java -jar .\sba-agentic-0.2.0.jar
```

## Build from source

```bash
git clone https://github.com/nathanmauro/black-box.git
cd black-box
mvn -q -DskipTests package
SBA_SUMMARY_BACKEND=local SBA_LOCAL_AI_ENABLED=false \
  SBA_MEMORY_EMBEDDING_ENABLED=false SBA_ASK_EMBEDDING_ENABLED=false \
  java -jar target/sba-agentic-0.2.0.jar
```

The Maven version on `main` is still `0.2.0`, so the JAR has the same file name as the release even
though its contents are newer. Run `mvn test` for the backend suite. Never run `mvn package` in a
checkout whose JAR a running service is using: packaging replaces the file under the live process.
[Operations](operations.md#run-as-a-service) explains how to run and update a service safely.

## Try the isolated demo

From a source checkout:

```bash
./scripts/quickstart.sh
```

The quickstart checks prerequisites, builds the JAR, starts a server on a throwaway demo database,
seeds a short cross-agent story, and prints a recall result. Model calls and Elasticsearch are
disabled for the demo, and it never touches an existing database. On macOS it opens the UI in your
browser; elsewhere it prints the URL. `./scripts/demo.sh` is the same demo without the prerequisite
report. Both leave the demo server running and print its PID so you can stop it; set
`SBA_DEMO_PORT` if another Black Box already uses port 8766.

## Data leaving your machine

Black Box stores its data locally, but some optional features send text elsewhere. Local storage
is not a promise that nothing leaves the machine.

- **Session summaries default to an external CLI.** With no configuration, a terminal event
  (`SessionEnd`, `Stop`, or `SubagentStop`) automatically schedules a summary through [`scripts/summarize-with-codex.sh`](../scripts/summarize-with-codex.sh),
  which sends transcript text to the Codex CLI and its provider. Set `SBA_SUMMARY_BACKEND=local` to
  avoid this.
- **"Local" model endpoints are whatever you point them at.** `SBA_SUMMARY_BACKEND=local` calls
  `SBA_LOCAL_AI_BASE_URL` (default `http://localhost:1234`, for LM Studio or another
  OpenAI-compatible server). Recall embeddings call `SBA_MEMORY_EMBEDDING_URL` (default
  `http://localhost:11434`, an Ollama-style endpoint). If you point either at a remote host, text
  goes there. Set **both** `SBA_SUMMARY_BACKEND=local` and `SBA_LOCAL_AI_ENABLED=false` to disable
  summary model calls and use a compacted transcript; disabling local AI alone leaves the default
  external backend selected. `SBA_MEMORY_EMBEDDING_ENABLED=false` disables memory embeddings, and
  recall stays lexical. Disable Ask embeddings separately with `SBA_ASK_EMBEDDING_ENABLED=false`.
- **Optional integrations send what you configure them to send**, such as an Elasticsearch index,
  Ask embeddings (`SBA_ASK_EMBEDDING_URL`), the off-by-default [cortex judge](cortex.md), an
  authenticated HTTPS deployment, or the [ChatGPT gateway](chatgpt-mcp.md).

Capture, browsing, lineage, and lexical recall work with no model and no Elasticsearch. The full
variable list is in [Operations: configuration](operations.md#configuration).

## Security defaults

The server binds to `127.0.0.1` and has no authentication by default. Anything on the machine that
can reach the port can read and write your history. Before binding to another interface, enable the
[authentication boundary](authentication.md) and put HTTPS in front of it.

## Next steps

- [Connect an agent](agent-integration.md): register the MCP endpoint in Claude Code or Codex, and
  optionally install capture and recall hooks.
- [Operations](operations.md): run as a `launchd` or `systemd` service, Docker, and every
  configuration variable.
- [PostgreSQL backend](postgres-backend.md): an optional shared database. It is a separate canonical
  store with no SQLite synchronization and supports one API instance.
- [Database recovery](database-recovery.md): back up and restore before upgrades.
- [Companion](companion.md): the ambient `/companion` view and macOS menubar panel.
