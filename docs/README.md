# Black Box documentation

Start with [installation](installation.md), then [connect an agent](agent-integration.md) and try
the [capture-and-recall demonstration](showcase.md). The [project README](../README.md) gives the overview.

Unless labeled otherwise, these guides describe the current source checkout. A downloaded release
may differ: [v0.2.0](releases/v0.2.0.md) is a historical artifact with its own compatibility boundaries.
Upgrading source or downloading a JAR does not update an already-running installation.

## Use Black Box

| Guide | What it helps you do |
| --- | --- |
| [Installation](installation.md) | Choose a download or source build, understand platform support, and run a persistent server. |
| [Agent integration](agent-integration.md) | Connect MCP clients, choose hooks, and preserve session lineage. |
| [ChatGPT gateway](chatgpt-mcp.md) | Set up a restricted integration with explicit capture and bounded retrieval. |
| [Project continuity](project-continuity.md) | Recall a project, follow evidence, and record decision replacements. |
| [Projectless braids](projectless-braids.md) | Collect related sessions across project boundaries. |
| [Companion](companion.md) | Use the browser companion and optional macOS shell. |
| [Cortex judgment stage](cortex.md) | Understand the optional, default-off model integration and its outbound data. |
| [Cross-environment capture](cross-environment-capture.md) | Preserve origin, scope, and provenance across clients. |

## Run and maintain a server

| Guide | What it covers |
| --- | --- |
| [Operations](operations.md) | Configuration, services, containers, and file navigation. |
| [Authentication](authentication.md) | Browser and bearer access for a network-exposed server. |
| [Database recovery](database-recovery.md) | Snapshot, restore, and recovery limits. |
| [Durable capture](durable-capture.md) | Opt-in local outbox, retry behavior, and acknowledgement limits. |
| [Idempotent capture](idempotent-capture.md) | Retry a capture without duplicating it. |
| [Capture text limits](capture-text-limits.md) | How oversized input is bounded and reported. |
| [Durable stream recovery](durable-stream-recovery.md) | Recover browser event delivery after a disconnect. |
| [Local writes and Elasticsearch](local-writes-and-elasticsearch.md) | Keep optional indexing separate from canonical writes. |
| [PostgreSQL](postgres-backend.md) | Use a separate canonical PostgreSQL database with one API instance. |
| [Board retirement](board-retirement.md) | Upgrade boundaries for removed execution tools and retained lineage. |

The outbox, stream cursor, and backup tools solve different recovery problems. None of them
automatically synchronize a local SQLite database with a cloud database.

## Understand and contribute

| Reference | What to read it for |
| --- | --- |
| [Architecture](architecture.md) and [package conventions](architecture/package-conventions.md) | Module ownership and enforced dependency boundaries. |
| [Frontend standards](frontend-standards.md) | TypeScript, lint, formatting, and UI test conventions. |
| [Java formatting](java-formatting.md) | The opt-in formatter and scoped verification. |
| [Recall observability](recall-observability.md) | What recall telemetry measures, and what it cannot establish. |
| [Showcase](showcase.md) | Reproduce the synthetic demo and README images. |
| [Building Black Box with Black Box](building-black-box.md) | A dated example of using captured context during development. |

## Experiments and design history

These documents explain investigations and possible directions. Read their status and limitations
before using them as setup instructions or evidence of a shipped capability.

- [Memory benchmark](memory-benchmark.md), [real resumption evaluation](real-resumption-evaluation.md),
  and [comparison protocol](continuation-comparison-protocol.md): evaluation methods and bounded results.
- [Linear integration](linear-integration.md): an explicitly triggered evidence-to-work prototype.
- [Cloud transport readiness](cloud-transport-readiness.md): verified transport contracts and remaining deployment work.
- [Local lifecycle](local-lifecycle.md): offline lifecycle rehearsal, with no infrastructure provisioning.
- [Retired Lightsail prototype](lightsail-prototype.md): historical deployment record and rebuild recipe.
- [Evolution](evolution.md) and [Futures](futures.md): project history and proposals, respectively.
- [Designs and implementation plans](superpowers/README.md): dated engineering records; proposals and checklists do not establish current completion.
- [Historical documents](history/README.md): superseded guides and retired features.
- [Frontend overhaul handoff](frontend-overhaul-handoff.md): an earlier development checkpoint.

For actual behavior, prefer the current guide, source, and relevant tests over an older plan.
