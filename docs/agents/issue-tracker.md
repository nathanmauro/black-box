# Issue tracker: Linear

Issues, specs, and tickets for this repo live in Linear, in the **Black Box** project
(`P-NAT-8`) of the `Nathanmauro` team (issue key `NAT`). Use the Linear connector or MCP tools
available in your harness. GitHub Issues on `nathanmauro/black-box` and local markdown trackers
are not used: if no Linear access is available, stop and report that instead of falling back.

Linear owns selected execution only. Reconcile against existing issues before creating one, and
do not turn every idea or historical task into a new issue. Unselected ideas belong in the
Obsidian vault; decisions, handoffs, and observations belong in Black Box.

## Conventions

- **Create an issue**: save a new issue with team `Nathanmauro` and project `Black Box`. Write the
  body as Markdown with real newlines.
- **Read an issue**: get the issue by identifier (`NAT-123`), then list its comments.
- **List issues**: list issues filtered by project `Black Box`, plus status or label filters.
- **Comment on an issue**: save a comment on the issue.
- **Set triage state**: this project uses Linear statuses, not labels. See `triage-labels.md`.
- **Close**: set status `Done` (or `Canceled` / `Duplicate`) with a closing comment.
- **Reference from Git**: put the identifier (`NAT-123`) in the commit body or branch name.
  Commit subjects stay human-readable title case, with no AI attribution trailers.

Statuses in this team: `Backlog`, `Todo`, `In Progress`, `In Review`, `Done`, `Canceled`,
`Duplicate`.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## When a skill says "publish to the issue tracker"

Create a Linear issue in the Black Box project. A spec is one issue titled `Spec: <name>`; its
tickets are sub-issues of that spec issue (set the parent), created in dependency order so each
ticket can name its blockers.

## When a skill says "fetch the relevant ticket"

Get the Linear issue by its `NAT-<n>` identifier, including comments. Identifiers appear in
commit bodies, branch names, and handoffs.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a single issue with **child** issues as tickets.

- **Map**: one issue in the Black Box project titled `Map: <name>`, holding the Notes /
  Decisions-so-far / Fog body.
- **Child ticket**: a sub-issue of the map. Put the ticket type in the title prefix
  (`[research]`, `[prototype]`, `[grilling]`, `[task]`) instead of a label.
- **Blocking**: Linear's native blocked-by relations. A ticket is unblocked when every blocker
  is `Done` or `Canceled`.
- **Frontier query**: list the map's open sub-issues, drop any with an open blocker or an
  assignee; first in map order wins.
- **Claim**: assign the issue to Nathan (`me`) and set status `In Progress`, as the session's
  first write.
- **Resolve**: comment with the answer, set status `Done`, then append a context pointer to the
  map's Decisions-so-far.
