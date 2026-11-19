# Continuity diagnostics and braid-graph export

Two read-only endpoints show whether the decision, handoff and braid trail is holding together.

- `GET /api/diagnostics/continuity` lints structured captures and saved braids.
- `GET /api/export/braid-graph` exports the project continuity graph as JSON or as a Mermaid
  flowchart: sessions, their decisions and handoffs, and the braids that weave them.

```bash
curl -fsS http://localhost:8766/api/diagnostics/continuity | jq '.ruleCounts, .findings[:5]'
curl -fsS 'http://localhost:8766/api/diagnostics/continuity?project=/path/to/repo&staleAfterHours=72' | jq
curl -fsS 'http://localhost:8766/api/export/braid-graph?project=/path/to/repo' | jq .counts
curl -fsS 'http://localhost:8766/api/export/braid-graph?format=mermaid&project=/path/to/repo' > graph.mmd
```

Both endpoints sit behind the same [authentication](authentication.md) boundary as the rest of
`/api` when authentication is enabled. Only `GET` is routed.

## Safety contract

- **Read-only.** `SELECT` statements only. Nothing is written, repaired, re-linked or deleted. A
  test fingerprints the event, session and braid tables before and after each call.
- **Redacted.** Every stored string in a response (excerpts, labels, ids, project keys) goes
  through the mandatory export redaction. That covers configured secret values, including their
  Base64 and URL-safe Base64 forms inside longer streams, and the built-in credential rules. The
  whole stored value is redacted before it is clipped to an excerpt or label, so clipping can never
  cut a secret into a prefix the masker no longer recognizes. Tests insert raw, unredacted rows,
  the way legacy or imported history can hold them, and assert that no form of the secret survives
  in any format.
- **Mermaid injection-safe.** Node ids (`s1`, `d1`, `h1`, `b1`, `x1`) and edge relations are
  generated. Labels are the redacted text with every character outside `A-Z a-z 0-9 space _ . , : /
-` written as a Mermaid entity (`#34;`). Quotes, brackets, pipes, semicolons, `%%` directives,
  backticks, HTML and line breaks therefore cannot leave their label. Control and invisible format
  characters such as bidi overrides are dropped. Tests check every output line against a strict
  line grammar.
- **Bounded.** The lint reads the newest 50,000 structured captures and 5,000 braids. The export
  reads the newest 20,000 decisions and handoffs and 1,000 braids. It keeps at most `maxSessions`
  sessions, 25 captures per session and 200 braids. `scan.truncated` and `truncated` say when a
  bound was hit.

## Lint rules

A capture's project is its `repo` when it names one, otherwise its session's working directory,
resolved through [project aliases](project-continuity.md).

| Rule                            | Severity | Fires when                                                                                                                                                         |
| ------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `orphan_braid`                  | error    | A braid has no input sessions, or none of its input sessions still exist.                                                                                          |
| `stale_handoff`                 | warning  | A handoff is older than `staleAfterHours` and no decision or handoff in the same project was captured after it. An observation alone does not count as a pickup.   |
| `decision_missing_kind`         | warning  | Neither the rationale nor the decision has a line starting with a non-empty `Kind:` field (the decision-capture trailer convention, `Kind: <kind> \| Tests: ...`). |
| `decision_missing_alternatives` | warning  | The decision recorded no non-blank rejected alternatives.                                                                                                          |
| `braid_missing_sessions`        | warning  | Some, but not all, of a braid's input sessions no longer exist.                                                                                                    |
| `undersized_braid`              | warning  | A braid has a single input session; braids weave 2 to 8.                                                                                                           |
| `unscoped_capture`              | info     | A decision, handoff or observation names no project, either through its repo or its session's directory, so project-scoped recall cannot find it.                  |

With `project`, the lint covers that project's captures and the braids that are either saved to it
or weave at least one of its sessions. A braid with no surviving sessions and no saved project
cannot be attributed, so only the unfiltered report shows it.

Parameters: `project` (optional), `staleAfterHours` (default 168, 1 to 8760) and `limit` (default
100, 1 to 1000). Out-of-range values return 400. `ruleCounts` and `findingCount` count every
finding. `findings` holds the first `limit`, errors first, then newest first.

## Graph shape

| Node kind         | Id   | Label                     |
| ----------------- | ---- | ------------------------- |
| `session`         | `sN` | `<source> - <title>`      |
| `decision`        | `dN` | the decision              |
| `handoff`         | `hN` | the context summary       |
| `braid`           | `bN` | the braid title           |
| `missing_session` | `xN` | a braid input with no row |

| Relation       | From → to          | Meaning                                                                     |
| -------------- | ------------------ | --------------------------------------------------------------------------- |
| `decided`      | session → decision | The session captured the decision.                                          |
| `handed_off`   | session → handoff  | The session captured the handoff.                                           |
| `carried_by`   | decision → handoff | The first handoff the same session captured at or after the decision.       |
| `picked_up_by` | handoff → session  | The earliest session in the same project that started after the handoff.    |
| `woven_into`   | session → braid    | The session is one of the braid's inputs (`missing_session` nodes as well). |

`ref` on each node is the stored id. Braid nodes carry `flags` (`orphan`, `missing_sessions`,
`undersized`), which match the lint rules. Parameters: `format` (`json` by default, or `mermaid`,
case-insensitive), `project` (optional) and `maxSessions` (default 50, 1 to 200). Sessions are
ranked by their latest capture or braid, and the most recently active ones are kept.
