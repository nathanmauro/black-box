# Java package conventions

Black Box is a feature-first modular monolith. The first package segment identifies the product
capability that owns the code; packages below it identify the role that code plays inside the
capability.

The supported compatibility surfaces are REST, MCP, SSE, CLI, Spring properties, JSON, and SQLite.
Implementation Java package names are internal and may move as the architecture improves.

## Module shape

Large modules use this vocabulary when the distinctions are useful:

```text
dev.nathan.sbaagentic.<module>
├── package-info.java
├── <Module>Operations.java
├── <Module>Event.java
├── spi/
└── internal/
    ├── domain/
    ├── application/
    │   └── port/
    └── adapter/
        ├── in/{web,mcp,cli}/
        └── out/{sqlite,elasticsearch,filesystem,model}/
```

This is a vocabulary, not a folder quota. A small feature stays small until several cohesive types
or a dependency boundary justify another package.

## Ownership and dependency rules

- Domain types live with the feature and rules that give them meaning.
- Cross-module commands, queries, results, events, and extension ports are exposed at the module
  root only when another module consumes them.
- HTTP request and response records live beside their controller. JDBC row projections live beside
  their SQLite adapter. Neither is a domain model by default.
- Controllers call application use cases or a module facade, never repositories.
- Application code depends on domain types and owned ports, never web, MCP, CLI, JDBC,
  Elasticsearch, servlet, filesystem, or process implementations.
- Infrastructure implements a port owned by the consuming feature.
- A module may import another module's public API or named SPI, never its `internal` packages.
- Cross-module reads use a narrow API. Cross-module reactions prefer explicit application events.
- Canonical SQLite writes commit before optional indexing, SSE, discovery, or summary fan-out.
- Spring transaction and lifecycle annotations stay on externally invoked Spring beans; package
  moves must not introduce proxy-bypassing self-invocation.
- Tests mirror production packages so package-private seams do not become public for test
  convenience.
- New global `controller`, `model`, `service`, `repository`, `common`, `shared`, or `util` package
  buckets are forbidden.

## Intended modules

| Module | Responsibility |
| --- | --- |
| `recording` | Canonical session/event capture, redaction, raw feed, and SQLite write boundary |
| `project` | Logical project identity, aliases, catalog, timelines, and melds |
| `memory` | Recall, context assembly, search/facets, and optional Elasticsearch projection |
| `summary` | Session finalization, summary backends, transcript export |
| `ask` | Retrieval orchestration and answer synthesis |
| `workflow` | Specs, tasks, annotations, lifecycle, lineage, and DAG projection |
| `runner` | External REST-driven FULL_AUTO/SDLC runner |
| `platform` | Bootstrap, generic errors, health, SPA, SSE hub, MCP registration, configuration, CLI shell |

`platform` is the composition edge and may depend on public module APIs. Feature modules never
import platform internals. `runner` remains wire-independent from server implementation types.

## Public means intentional

A type at a module root is an intentional Java boundary. A public type below `internal` may still
be needed for Spring or Java mechanics, but module verification prevents other modules from
importing it. Do not create forwarding facades merely to preserve old implementation FQCNs; keep a
temporary facade only when it materially lowers migration risk.

The build enforces these rules in two layers:

- ArchUnit ratchets internal layering and naming as each vertical slice migrates.
- Spring Modulith verifies the final module graph, cycles, allowed dependencies, and named
  interfaces.
