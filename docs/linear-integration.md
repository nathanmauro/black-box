# Evidence to Linear prototype

Black Box captures why an action was considered. Linear owns the work item after it is selected.
This prototype turns explicit `nextAction` and `openLoops` statements from structured recall into
reviewable issue candidates. It does not infer a backlog from transcripts, claim old work is still
unfinished, or import historical work as completed issues.

The CLI uses Python 3.9+ standard libraries and runs on macOS/Linux. No Linear credential is needed
for preview or a publish dry run. Candidate files and publication state contain private project
information: keep them outside the repository.

## Preview and review

Create a preview from Black Box:

```bash
python3 scripts/linear/blackbox_linear.py preview \
  --scope /repos/example --within-hours 168 \
  --output /tmp/blackbox-linear-review.json
```

Use `--blackbox-url https://your-private-blackbox.example` for another reachable instance, or
`--input /path/to/recall.json` for a saved `/api/recall` response. For an entirely offline example:

```bash
python3 scripts/linear/blackbox_linear.py preview \
  --input scripts/linear/recall-example.json \
  --output /tmp/blackbox-linear-example.json
```

Output refuses to overwrite an existing file, preserving prior review. Each candidate includes its
exact event IDs, source dates, field names, source text, headline, repo, and session IDs. Identical
actions within a scope are grouped using whitespace normalization and case folding, preserving all
their sources. Candidate identity is independent of the edited issue title and preview timestamp.
Near duplicates require judgment. Keep the same scope across preview runs; changing it changes the
candidate identity. Empty actions and common explicit no-action markers are excluded. Invalid
source records and truncated recall produce warnings rather than invented evidence.

Edit only the candidates worth acting on:

```json
{
  "title": "Verify delivery recovery after an interrupted request",
  "relevanceConfirmed": true,
  "relevanceNote": "This failure mode remains unverified in the current prototype.",
  "acceptanceCriteria": [
    "An accepted request with a lost response is reconciled without another create.",
    "An unconfirmed outcome remains visible for manual investigation."
  ]
}
```

These are illustrative values, not claims about the current project. Keep the generated `id`,
`sourceAction`, and `evidence` intact. Relevance and acceptance criteria belong to the reviewer;
the CLI does not fill them in. A reviewer may be a user or an agent acting within the user's
explicitly granted task scope. Confirming relevance is not a substitute for permission to publish.

## Verify destination and publish selected issues

Configure `LINEAR_API_KEY` in the environment, or use an AWS Secrets Manager JSON secret via
`--secret-id YOUR_SECRET_REFERENCE --secret-key api-key --region YOUR_REGION`. The AWS CLI must
already be installed and authenticated; `--profile` selects a named profile. The adapter captures
the secret subprocess output in memory and never writes or prints the key. Do not put keys in
command arguments or candidate files.

List accessible teams (read-only):

```bash
python3 scripts/linear/blackbox_linear.py teams
```

Copy the intended team's UUID and exact selected candidate ID. Dry-run the publication to inspect
the exact title and Markdown description, including provenance:

```bash
python3 scripts/linear/blackbox_linear.py publish \
  --candidates /tmp/blackbox-linear-review.json \
  --team TEAM_UUID --select CANDIDATE_ID \
  --state /path/to/persistent/linear.sqlite3
```

Repeat that command with `--apply` to publish. Add another `--select CANDIDATE_ID` for each additional
candidate (maximum ten). Every selected candidate must have relevance confirmed, a relevance note,
and at least one acceptance criterion. The whole selection is validated before any API access.
The command prints planned issue bodies before any external write and verifies the selected team
and required schema fields. Add `--project PROJECT_UUID` to place issues in an existing project;
the command verifies that the project belongs to the selected team before creating any issues.
It sets no assignee, priority, estimate, status, or historical creation/completion dates. Linear
chooses its team's normal initial state.

Use `--source-base-url https://your-private-blackbox.example` to add event links when recipients can
reach that deployment and it contains those exact source events. Omit it for local-only evidence or
when the cloud prototype uses a separate database. Source IDs and dates remain in the issue body.
The adapter quotes evidence as literal text; it never executes captured commands or interpolates
source text into GraphQL queries.

## Publication state and failure recovery

Use **one authoritative persistent state file and one publishing host**. The default is
`$XDG_STATE_HOME/blackbox/linear.sqlite3`, or `~/.local/state/blackbox/linear.sqlite3`. An adjacent
lock prevents concurrent publishers on that file. Place both on persistent storage for a cloud
runner; retain the database during redeployments and move it with the publisher. This mapping is
separate from Black Box's evidence database and contains no API credentials.

Before creating a new issue, the adapter performs a bounded remote lookup by team and the exact
candidate marker, including archived issues. A unique exact match recovers the existing issue into
local state. Incomplete results or multiple exact matches stop publication. This helps recover a
missing state file; it is not a distributed lock. Two hosts with independent state files can race.
Changing a scope, changing source action wording, removing provenance markers, losing visibility
into an existing issue, or permanently deleting issues can defeat remote recovery. Do not use this
prototype as a multi-worker publishing service.

For a new issue, the adapter persists a UUID v4 and `pending` row **before** sending `issueCreate`.
Confirmed success records `published`. Repeating a published selection returns its saved issue
without mutation. Editing a previously attempted payload stops rather than replacing or duplicating
it; make ongoing changes in Linear.

If a create times out, returns HTTP/GraphQL errors, or lacks a complete success response, it stays
pending. Rerun the exact same command and review file: the adapter only queries the preassigned ID.
If that issue exists and its team and provenance match, local state becomes published. If it is
absent or cannot be verified, the adapter stops; it **does not retry creation**. Inspect the recorded
ID and Linear manually. There is intentionally no automatic reset/retry flag for uncertain writes.
This also means a definite API rejection requires manual investigation before a fresh attempt.

For a partial batch, earlier successes stay recorded and remaining selections stop. Repeating the
same batch skips prior successes and reconciles the pending one. Keep the review file unchanged
until the batch is settled. No success claims are inferred from partial GraphQL data.

## Verification and limitations

```bash
python3 -m unittest discover -s scripts/linear -p 'test_*.py' -v
```

The end-to-end tests launch the real CLI as subprocesses against local fake HTTP servers. They
exercise preview/recall, explicit review and selection, exact dry-run payloads, repeat prevention,
missing-state recovery, ambiguous and partial success, HTTP/GraphQL failures, local locking, and
malicious source strings. They require permission to bind loopback; they do not call real AWS or
Linear and use only a clearly fake credential. Live credential and workspace validation is a
separate integration check.

This is a single-user prototype, not a marketplace integration: no OAuth installation, webhook
receiver, two-way board sync, semantic deduplication, cross-host locking, scheduling, or agent
execution is included. An authenticated Black Box deployment may need its own authenticated export
to `--input`; the preview fetch currently adds no Black Box authentication headers. Build those
capabilities only after the evidence-to-selected-action loop proves useful.

API basis checked September 8, 2026: Linear's [GraphQL guide](https://linear.app/developers/graphql)
documents authentication, teams, issue creation, and partial GraphQL errors. The
[official SDK schema](https://github.com/linear/linear/blob/master/packages/sdk/src/schema.graphql)
defines `IssueCreateInput.id` as a client-supplied UUID v4 and provides issue-description filters.
The adapter also checks the creation input fields through read-only introspection before publishing.
