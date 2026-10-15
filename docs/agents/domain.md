# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root: the glossary of domain terms.
- **`docs/adr/`**: read ADRs that touch the area you're about to work in.
- **`docs/architecture.md`** and **`docs/architecture/`**: the existing architecture contract and
  package conventions (enforced by ArchUnit). ADRs record new decisions; they do not replace it.

If `GLOSSARY.md` or `docs/adr/` don't exist, **proceed silently**. Don't flag their absence; don't
suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and
`/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

Earlier specs and plans live under `docs/superpowers/specs/` and `docs/superpowers/plans/`. They
are history, useful for why something was built the way it was.

## File structure

Single-context repo:

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-<decision>.md
│   └── 0002-<decision>.md
└── src/
```

## ADRs versus Black Box Decisions

This product is itself a decision store, so keep the two apart. An ADR is a durable,
hard-to-reverse architectural decision about this codebase, versioned with the code. A Black Box
`Decision` event is agent continuity: a choice future agents should not re-litigate, recorded
through the capture tools. An ADR-grade decision gets both, paired; routine routing or scope
choices get only the Black Box event. The `domain-modeling` skill's `DECISION-CAPTURE.md` holds
the capture convention (one decision per capture, `Kind:` trailer, `supersedes`).

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
