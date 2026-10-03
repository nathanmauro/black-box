# Project continuity

Use `project` to select an exact logical project and `query` to ask a separate question. The same
fields work with `GET /api/recall` and MCP `recallContext`. Existing `scope` (HTTP) and `repoOrTopic`
(MCP) inputs remain supported. A nonblank legacy input combined with either new field is invalid.

```bash
curl -fsSG http://localhost:8766/api/recall \
  --data-urlencode 'project=/repos/example' \
  --data-urlencode 'query=why retries' \
  --data-urlencode 'kinds=decision,handoff' \
  --data-urlencode 'limit=10'
```

`project` accepts a canonical path, not the catalog's base64 URL key. Registered aliases share a
logical project. Matching is exact after normalizing whitespace and trailing slashes, and happens
in both lexical and semantic candidate SQL before ranking. The captured event's `repo` metadata is
used when present; otherwise the session's working directory supplies the project. This prevents
an event with an explicit repo from moving projects when its session later changes directories.
Unknown projects return an empty result. An empty query returns recent intent; only a nonblank
query is embedded, never the selected path. Omitting the project allows a global question.

Lexical fallback, kind and time filters, the default ten results and maximum fifty remain. Explicit
query text treats `%` and `_` literally. Semantic retrieval remains optional and only includes
structured intent with eligible vectors; neither session summaries nor the full event corpus become
recall results. No new generation model is involved. `RecallResult.scope` echoes the question for
new calls, or the legacy scope for old calls; callers retain their selected project separately.

## Session and project chronology

Session lists (`GET /api/sessions` and project session lists) sort by the actual last-seen instant,
including nanosecond fractions, with session ID descending for equal times. This also applies to
missing-summary selection and selected project sessions. Parent/child and human-turn filters are
unchanged. The stored timestamp values are returned without rewriting them into comparison keys.

A session's start remains its first captured event time; a later-arriving older event does not move
that origin. Its last-seen time tracks the latest recorded event time. Project first/last activity
selects the earliest/latest session endpoints and saved-meld creation times across its registered
scopes. These are recorded activity timestamps, not a claim that captured work is still current.

Startup adds an idempotent comparison-key index for session recency on SQLite and PostgreSQL,
retaining the previous index and canonical timestamp columns. Existing databases pay a one-time
index build and storage cost; no event or session timestamp migration is required. See the
[session chronology verification](superpowers/plans/2026-10-03-session-project-chronology.md).

Browse also retains complete timestamp precision when merging an exact source event or an older
transcript page. An older answer stays before a later prompt even within the same millisecond;
loading its preceding prompt restores the complete turn. Equal instants retain recorded-source
and input-order precedence, and duplicate detection is unchanged. Invalid or missing legacy
timestamps remain last. See the [Browse ordering verification](superpowers/plans/2026-10-03-transcript-precise-ordering.md).

When Browse merges search results or older pages, tool-payload comparison preserves numeric JSON
values that JavaScript would round, overflow or underflow. Safely roundtripping numbers retain
sorted-key semantic matching (for example, `1`, `1.0` and `1e0`). Other inline JSON uses compact
captured text with whitespace removed only outside strings. This conservative comparison can
retain extra duplicates when inexact numbers have different spelling or key order; it avoids
hiding distinct evidence. Deep inline JSON uses the same bounded fallback. Stored payloads,
occurrence windows and exact event IDs are unchanged. The existing hash comparison for payloads
above 32,768 characters remains in place and is not a collision-free guarantee. See the
[numeric identity verification](superpowers/plans/2026-10-03-transcript-number-identity.md).

Browse scopes pending reader work to the selected session. A previous session's lineage is hidden
while the new relationship request loads. Switching sessions, transcript searches or My turns
mode releases older-page controls for the new reader; late pages and failures cannot overwrite its
rows, errors or loading state, including a return to the same session. Requests may still finish
in the background; this is response ownership, not transport cancellation. A live first-page
refresh also invalidates an older page's success/error if its starting cursor has moved, keeping
intervening events reachable. See the
[reader request-state verification](superpowers/plans/2026-10-03-session-reader-request-state.md).


## Record a changed decision

In the web interface, choose a project in **Recall**, then enter a separate question. Suggestions
come from recorded captures under the selected project, time and kind filters. Keyboard arrows and
Enter select evidence; its source link opens the owning session at the exact event. Suggestions
require no additional generation model. Selecting another suggestion refreshes the retained card's
alternatives and open loops, including showing or hiding each section as the recorded lists change.
Legacy `scope` links and the menu-bar launcher's `run=1` flag remain supported.

**Resume this project** on Projects opens a one-year project recall. The briefing labels the latest
recorded handoff and recorded open questions without asserting that they remain current. **Copy
context** exports visible retrieved captures with timestamps and exact source links, capped at
24,000 characters and with explicit truncation/omission counts. It is bounded evidence, not a
generated assessment or a complete project history. Source/client filters affect what is copied.

**Latest retrieved handoff** compares complete stored UTC timestamps, including nanoseconds,
independently of retrieval relevance order. Exact timestamp ties use event ID descending. If a legacy missing/invalid timestamp falls back
to the same effective time as a canonical capture, the canonical capture takes precedence. It remains
a statement about retrieved evidence, not the current state of the project.

Projects evidence links use `?focus=capture:<eventId>`. A link through a registered project alias
resolves to the canonical project while retaining its evidence selection, query parameters and
fragment. Canonicalization replaces the current history entry, so Back returns to the preceding
page rather than revisiting the alias redirect.

On **Ideas**, **Capturing session** opens the exact recorded Idea in Browse. Its source remains
visible and highlighted with **My turns** on or **Show memory events** off, and survives reload.
The link clears remembered Activity project scope; source filters still apply. Ordinary session
selection and the sticky human-only setting are unchanged. See the
[Idea source verification](superpowers/plans/2026-10-03-idea-exact-source.md).

Observation, Projection, and Evidence recall items retain a short `headline` and include their full captured text in the
optional `body` field. It reflects the stored evidence after the existing ingest redaction and
length limits; recall does not change those capture rules. Other kinds and older responses omit
that field. Recall's expandable reader
and **Copy context** use the body when available; original evidence remains accessible through the
source link. MCP counts body text toward `maxChars`, marking any shortening with an explicit suffix
and `truncated: true`. Copied context reports its own truncation and omission counts.

**Projection** is an opt-in Recall kind. Its heading names the first recorded path; the expandable
body includes the stored alternatives, conditions, and per-path confidence. Both the card and copied
context label these as possibilities, without implying a selected outcome or an overall forecast
confidence. Projections use text matching only and remain outside default Decision/Handoff recall.

In Browse, Projection belongs to the opt-in **Show memory events** layer. Its evidence is labeled
Projection rather than an agent response, and it does not stand in for a missing conversational
answer. Exact-source links still reveal and highlight the selected Projection with the memory
layer off. This changes presentation only; the original capture and role metadata stay intact.
See the [Projection Browse verification](superpowers/plans/2026-10-03-projection-browse-layer.md).

Use **Replace decision** on a current decision and enter the new choice plus the reason. This writes
only when **Record replacement** is pressed. **Include replaced decisions** requests historical
results; the earlier/replacement links resolve their own sessions even across different clients.
If the write succeeds but refresh fails, the UI clears stale evidence and distinguishes that saved
result from a failed write.

## Capture API

Add `supersedes` to a normal decision capture to explicitly replace one earlier Decision:

```json
{
  "source": "manual",
  "clientSessionId": "review-storage-choice",
  "repo": "/repos/example",
  "decision": "Use the revised storage approach",
  "rationale": "The measured workload invalidated the previous capacity assumption.",
  "supersedes": "prior-decision-event-id"
}
```

Send this body to `POST /api/decisions` or the same fields to MCP `captureDecision`. Replacement
requires a nonblank rationale, an identifiable repo, and a real, current Decision in the same
logical project. Missing targets, other kinds, other projects and already replaced targets are
rejected without new events, sessions or relations. An unscoped target cannot be assigned to a
project by the replacement. The no-project catalog sentinel is not an identifiable project.

The original event bytes are unchanged. A dedicated `decision_replacements` relation and the new
event commit atomically in the selected canonical database (SQLite or PostgreSQL). A unique old
event ID chooses one winner for concurrent replacements. A reservation with a temporarily null
new ID exists only inside the transaction, then binds the inserted event before commit; failed
inserts roll everything back. Optional indexing/publication runs after the canonical commit and
cannot fail the stored replacement. Committed relations are never edited by capture APIs.
There is no inferred replacement, cross-project rewrite, or undo endpoint.

Normal recall omits replaced Decisions even if the replacement itself is outside the current
question or time window. `includeSuperseded=true` requests historical results with the same filters.
`supersedesEventId` and `supersededByEventId` appear when applicable; a chain's middle item can carry
both. Null relation fields are omitted to preserve legacy response bytes, and MCP truncation retains
them. Source event reads still expose the original evidence. Newer unrelated captures do not
supersede anything and timestamps alone do not establish truth.

## Verification and fixtures

`ProjectContinuityTest` exercises exact project/alias isolation with more out-of-project candidates
than the result cap, semantic and lexical paths, moving sessions, literal query characters,
replacement history, invalid targets, SQLite races and rollback, and REST compatibility.
`StructuredCaptureHttpTest` exercises real MCP transport through capture, project recall,
replacement, history and actionable errors. `PostgresBackendContractTest` runs the equivalent
project/replacement path plus real concurrent writes and rollback on a disposable PostgreSQL schema
when its documented test environment is configured.

Changed MCP and wire snapshots can be regenerated from actual tool definitions and record serializers:

```bash
mvn -Dcontracts.update=true -Dtest=McpContractSnapshotTest,WireContractFixtureTest test
```

Review the diff after regeneration. The opt-in generator preserves unrelated wire fixture rows;
normal tests only read frozen snapshots. The REST contract matrix remains hand-maintained.

Functional verification establishes isolation and preservation of evidence. It does not establish
improved agent productivity; continuation quality still needs evaluation on representative tasks.


## Saved braids

Projects has a separate **Saved braids** view for durable unassigned synthesis. It is available even
when the project catalog is empty or unavailable; no synthetic project is created. A detail URL is
`/projects?view=braids&meld=<id>` and survives reload and browser Back. Ordinary project melds
remain in their existing project workspace; a project-owned ID opened here links to its owner.

The reader loads 20 artifacts per page, newest saved first. **Loaded** counts describe the items
already fetched, not the full collection. Load-more errors retain earlier pages and can be retried.
There is no search or creation control: the listing endpoint does not support query/session filters.

The synthesis is rendered as full plain text. Its timestamp is the save time. Provider, model,
execution and prompt fields are caller-declared provenance, not an independent verification of who
created the text. Source sessions retain saved order. New braids keep source/cwd/client-ID snapshots;
legacy artifacts can fall back to current joined values. Titles and timestamps reflect current
records and can be unavailable after deletion; the event count is zero for a missing session.
Links use real internal session IDs at `/sessions/<id>?reveal=session`, without project or invented
event scope. This explicit direct link reveals only that requested session and its full transcript,
even with incompatible My turns or source filters, and explains that saved Browse settings remain
unchanged. Selecting another session exits this mode; missing IDs never select an unrelated session. Caller metadata
is optional and lazy, with a bounded, explicitly truncated preview and raw saved-artifact JSON download.

See the [reader verification plan](superpowers/plans/2026-10-03-saved-braids-reader.md).

Metadata is a parsed display preview: large JSON numbers may be rounded by the browser. **Download
saved artifact JSON** fetches the authenticated same-origin detail response as text, preserving
numeric values without parsing and serializing them again. Download failures remain visible and
can be retried; the action makes no server-side change.
