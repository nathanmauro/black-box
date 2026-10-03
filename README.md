<div align="center">
  <img src="frontend/public/favicon.svg" width="72" alt="Black Box logo">
  <h1>Black Box</h1>
  <p><strong>Shared memory for coding agents.</strong></p>
  <p>Keep the decision, the reason, and the next step.<br>Recall them when another session picks up the work.</p>
  <p>
    <a href="docs/installation.md">Get started</a> ·
    <a href="docs/agent-integration.md">Connect an agent</a> ·
    <a href="docs/README.md">Documentation</a>
  </p>
  <p>
    <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&amp;logoColor=white" alt="Java 21"></a>
    <a href="docs/agent-integration.md"><img src="https://img.shields.io/badge/MCP-Streamable_HTTP-7C6CF2" alt="MCP Streamable HTTP"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-2EA44F.svg" alt="MIT License"></a>
  </p>
</div>

Work can outlast an agent session. Black Box gives Codex, Claude Code, and other MCP clients a
place to record decisions and handoffs, then retrieve that context before continuing. You can
inspect the same records in a local web app, follow them back to their sources, and see how a
project's direction changed.

It runs locally with SQLite by default. Models and external search services are optional for the
core capture-and-recall loop.

![The Activity workspace, showing captured decisions and handoffs from a synthetic project.](docs/assets/hero.png)

## Try the loop

1. **Capture a decision:** what changed, why, and which alternatives were rejected.
2. **Leave a handoff:** the verified state, unfinished work, and one useful next action.
3. **Recall the context:** a later session asks `recallContext` for the project or topic and receives
   a bounded set of records with their sources.

![A real API demonstration capturing a decision and handoff, then recalling the saved context.](docs/assets/demo.gif)

The demo uses synthetic records and the actual API. [Read or reproduce the demonstration](docs/showcase.md).

### Run a disposable demo

On **macOS or Linux**, install Java 21+, Maven 3.9+, Bash, `curl`, `jq`, and `lsof`, then:

```bash
git clone https://github.com/nathanmauro/black-box.git
cd black-box
./scripts/quickstart.sh
```

The script builds the app, starts a private scratch database, runs the capture-and-recall example,
and serves the UI at [localhost:8766](http://localhost:8766), opening it automatically on macOS.
Model calls, external summaries, and editor
launching are disabled for the demo. An occupied port is refused; choose another with
`SBA_DEMO_PORT=8877 ./scripts/quickstart.sh`. The script prints how to stop its process and remove
its scratch directory when you are done.

For a persistent installation, downloads, Windows guidance, and service setup, follow the
[installation guide](docs/installation.md).

**Source and release:** this README describes the current source. The latest published download,
[v0.2.0](https://github.com/nathanmauro/black-box/releases/tag/v0.2.0), predates some features shown
here and still includes the retired task board. Read its [release boundaries](docs/releases/v0.2.0.md)
and the [upgrade notes](docs/board-retirement.md) before replacing an existing installation.

## Connect your agents

A running Black Box server exposes Streamable HTTP MCP at `http://localhost:8766/mcp`:

```bash
codex mcp add sba-agentic --url http://localhost:8766/mcp
claude mcp add --transport http --scope user sba-agentic http://localhost:8766/mcp
```

Use your chosen port if it differs. These commands register the server; they do not start it or
enable automatic capture. Ask the agent to recall context before work and leave a handoff when it
finishes. Optional hooks can record session activity or supply a small recall packet at startup.

[Agent integration](docs/agent-integration.md) covers the tools, hooks, and session lineage.
[ChatGPT integration](docs/chatgpt-mcp.md) uses a separate restricted gateway. Connecting either
client does not automatically synchronize its entire conversation history.

## Find the context behind the work

**Recall** separates the project from the question. Read decisions and handoffs, copy a briefing,
follow supporting evidence, and explicitly record when a decision has been replaced.

![Recall with a selected project, a question, and source-backed results from the synthetic example.](docs/assets/recall.png)

**Activity and Browse** let you search recorded events, filter by source or kind, inspect sessions,
and follow parent/child agent relationships. Saved views and projectless braids help collect
related work without moving the underlying records.

**Trajectories** put recorded history beside proposed next steps. Selecting a node reveals its
source. Projections are possibilities written by an agent; their declared confidence is not a
calibrated prediction.

![A project trajectory with a selected node and its supporting record.](docs/assets/trajectory.png)

The optional [macOS companion](docs/companion.md) provides a small activity view and opens
project-scoped recall. The browser interface remains available without the native companion.

## What to expect

| Area | Current behavior |
| --- | --- |
| Storage | SQLite by default. Optional PostgreSQL owns a separate database for one API server; there is no automatic local/cloud synchronization. |
| Recall | Lexical retrieval works without a model. Optional semantic recall covers Decisions, Handoffs, Observations, Ideas, and Evidence. Projections are lexical-only; full event history is searchable but not entirely vector-indexed. |
| Capture | Explicit memory tools plus opt-in session hooks. The optional durable outbox retries accepted captures; it is not a replica of your history. |
| Execution | The server records and retrieves context. It does not run coding agents. |
| Availability | Self-hosted software. The earlier hosted prototype was retired; no hosted service is offered by this repository. |

**Data and access:** the default server listens on loopback without authentication. Network access
requires [authentication and HTTPS](docs/authentication.md). The source configuration defaults to
an external Codex summary wrapper, which can send transcript text to that provider. Choose a
model-disabled or locally configured setup using the [installation guide](docs/installation.md).
Optional integrations have their own data flows. Ingestion redaction is best-effort; see
[configuration and redaction limits](docs/operations.md#configuration).

## Built with Black Box

Black Box is used while developing Black Box. In an October 2026 development run, Codex recalled
an earlier handoff about a recall launcher, used that context to preserve its behavior, verified the
result, and recorded new handoffs for the next session.

That is a traceable example of the write-and-query loop. It is not proof that memory makes every
task faster or more accurate. Read the [case study and its limits](docs/building-black-box.md),
[project history](docs/evolution.md), or the [continuation evaluation](docs/real-resumption-evaluation.md).

## Develop and contribute

The backend uses Java 21, Spring Boot, and Spring Modulith. The frontend uses SolidJS and TypeScript;
its built assets are included in the JAR. Node is needed for frontend development, not for running a
downloaded release.

Run commands from the repository root:

```bash
mvn test
(cd frontend && npm ci && npm test)
(cd frontend && npm run check)
(cd frontend && npm run build)
./scripts/verify.sh --e2e
```

Use a separate checkout for verification builds if a service is running from your working copy;
packaging replaces files under `target/`. The full verification path includes packaged browser
journeys against disposable storage. See
[frontend standards](docs/frontend-standards.md), [Java formatting](docs/java-formatting.md),
[architecture](docs/architecture.md), and [package conventions](docs/architecture/package-conventions.md).
The [documentation index](docs/README.md) separates user guides, contributor references, experiments,
and historical design records.

## License

[MIT](LICENSE) © 2026 Nathan Mauro
