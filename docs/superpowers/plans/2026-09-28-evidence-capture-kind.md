# Evidence capture kind and lane fields

**Status:** original draft from `claude/blackbox-additions`, based on `claude/idea-kind` (`4c6a9e2`).
Current integration follows [the October 3 acceptance plan](2026-10-03-evidence-integration.md);
not yet merged or deployed.

**Origin:** during the 2026-09-28 loose-ends hunt, the facts that proved ideas were being dropped
had nowhere to live: `searchSessions` matched the hunt's own output, atuin showed zero `sf` runs,
and a recommendation sat at rollout line 1332. Nathan: "maybe evidence should be captured too."
An `Evidence` is a verifiable fact with provenance that can support or refute an `Idea`, a
`Decision`, or a `Handoff`. This slice also gives `Idea` and `Evidence` lane-ready project fields
so a future per-project board can route each item to a home lane and cross-list it elsewhere.

## Scope

- The `Evidence` kind: capture over MCP and REST, a listing, recall, and embeddings. It mirrors
  the `Idea` kind (`docs/superpowers/plans/2026-09-28-idea-capture-kind.md`).
- Recall of an `Idea` together with the `Evidence` that supports or refutes it.
- The lane fields `project` and `alsoIn`, added to both `Idea` and `Evidence`.

## Out of scope

- The board that consumes the lane fields. This slice defines the schema only; there are no
  lane filters or lane views.
- A dedicated Evidence UI. Evidence events appear in the stream and under the existing kind
  facet, and their text rendering keeps them legible there.
- The `[Idea]` observation migration. It already ran on the live service, and nothing is left
  to migrate.
- The tangent router, which would auto-detect asides in the human-turn stream. It is the next
  candidate slice.
- Checking that a link target exists. A link may point at an item captured later, or on
  another instance.

## Contract

### Lane fields (on `Idea` and `Evidence`)

| Field | Type | Rules |
| --- | --- | --- |
| `project` | string | The item's home lane: a repo path or a project name. It is stored only when given. |
| `alsoIn` | list of `{project, score}` | Other lanes where the item is relevant. `project` is required and must not be blank. `score` is required and must be between 0.0 and 1.0; `NaN` is rejected. The list can hold at most 20 entries. A project may not appear twice (compared case-insensitively after stripping), and no entry may repeat the home lane. |

- Violations are validation errors with actionable messages, returned through REST and MCP.
- **Metadata:** `project` is a string, and `alsoIn` is a list of `{project, score}` maps.
- **Event text:** the rendering adds `Project: <project>` and `Also in: a (0.8), b (0.3)` lines.
- **Views:**
  - `IdeaView` and `EvidenceView` carry `project` and `alsoIn`.
  - The view's `project` is the explicit value, or `repo` when none was given.
  - In the Idea listing, both fields come from the newest revision that carried them, like the
    other optional fields. A status-only re-capture keeps the lanes.
- **Shared record:** the record type is the root API record `LaneListing(String project, Double score)`
  in `recording`.

### Link references

`supports` and `refutes` take typed references:

- `idea:<ideaKey>` points at an Idea by its stable key, so the link survives status re-captures.
- `event:<eventId>` points at any event (a Decision, a Handoff, or one Idea revision).
- A bare value that looks like an event id is read as `event:<value>`: 8 to 36 characters of
  hex digits and dashes, which covers a full UUID or a short prefix such as `fa35f02b`.
- Any other value is a validation error. It names the two accepted forms.

Normalization and validation rules:

- The prefix is case-insensitive. The id or key is stripped and must not be blank.
- Stored refs are canonical `idea:<key>` or `event:<id>` strings.
- Duplicates within a list collapse.
- A ref that appears in both `supports` and `refutes` is a validation error.
- Each list holds at most 50 refs.
- The rules live in one root API helper in `recording`, reused by capture and by the readers.

### Capture

`CaptureEvidenceRequest` is a root API record in `recording`. The constants live in
`EvidenceKind`: `EVENT_TYPE = "Evidence"`, `KIND = "evidence"`, and `TEXT_PREFIX = "[Evidence]"`.

| Field | Type | Rules |
| --- | --- | --- |
| `source` | string, required | The capturing client, as for other kinds. |
| `clientSessionId` | string, required | As for other kinds. |
| `repo` | string | The project path, as for other kinds. |
| `claim` | string, required | One sentence stating the fact. |
| `excerpt` | string | Verbatim text that shows it: a transcript line, command output, or a file excerpt. |
| `sourceRef` | string, required | Provenance: a session id, a `path:line`, a URL, or the command that was run. |
| `outputDigest` | string | An optional digest of the command output, for example `sha256:<hex>`. At most 200 characters. |
| `observedAt` | string | When the fact was observed, as an ISO-8601 instant or offset date-time. Invalid input is a validation error. It is stored normalized to an instant string. It does not change the event time. |
| `capturedBy` | string | Who captured it (the agent or the person). |
| `supports` | list of refs | See Link references. |
| `refutes` | list of refs | See Link references. |
| `notes` | string | An optional markdown body. |
| `project`, `alsoIn` | | See Lane fields. |

- Validation happens at `StructuredCaptureService`, like the other kinds.
- **Stored event:** `eventType = "Evidence"`, with metadata `kind = "evidence"` and every field
  that was given.
- **Event text:** starts with `[Evidence] <claim>`, followed by readable lines for the excerpt,
  source, digest, observed time, captured-by, supports, refutes, the lanes, and notes.
- **REST:** `POST /api/evidence` returns the same ingest response as the other capture routes.
- **MCP:** a `captureEvidence` tool. Its description says when to use it: a verifiable fact
  with provenance, especially one that supports or refutes an idea, decision, or handoff.
  `alsoIn` is an array of objects, like the paths in `captureProjection`.
- **`captureIdea`:** gains the optional `project` and `alsoIn` parameters, on both MCP and REST
  (`CaptureIdeaRequest`).
- **ChatGPT gateway:** `scripts/chatgpt_mcp/gateway.py` accepts `kind=evidence` wherever it
  accepts `kind=idea`.

### Read

- **Recall:** `recallContext` and `/api/recall` accept `kinds: ["evidence"]`. The headline is the
  claim and the rationale is the excerpt. The default recall kinds do not change. The
  `recallContext` kinds description lists `evidence`.
- **Embeddings:** Evidence joins memory embeddings wherever the `idea` kind does.
- **Listing:** `GET /api/evidence` returns `{ items: EvidenceView[], count }`, newest first. It
  accepts these filters:
  - `target` matches `supports` or `refutes`, and is normalized with the same ref rules. An
    invalid value returns `400 invalid_argument`.
  - `project` and `repo` scope the list, using the same resolution as `GET /api/ideas`.
  - `q` filters by text.
  - `limit` defaults to 100 and is capped at 500.
- **`EvidenceView`** carries these fields:
  - `eventId`, `sessionId`, `source`, `clientSessionId`, `repo`;
  - `claim`, `excerpt`, `sourceRef`, `outputDigest`;
  - `observedAt` (the explicit value, or the capture time);
  - `capturedBy` (the explicit value, or `source`);
  - `supports`, `refutes`, `notes`;
  - `project` (the explicit value, or `repo`), `alsoIn`, `capturedAt`.
- **An idea with its evidence:** `GET /api/ideas/detail?ideaKey=<key>` returns
  `IdeaDetail { idea: IdeaView, supports: EvidenceView[], refutes: EvidenceView[] }`, newest
  first. An unknown key returns `404` with the typed error envelope.
  - Evidence is linked when it references `idea:<key>`.
  - It is also linked when it references `event:<id>` of any revision of that idea, either the
    exact id or a prefix of at least 8 characters.
- **MCP `recallIdea(ideaKey)`:** returns the same `IdeaDetail`. An unknown key is an error that
  says to list ideas or check the key. The current retired-board baseline grows from 10 to 12 MCP tools.
- **Scans:** the listings read every Evidence and Idea event through keyset pages, like the Idea
  scans. They do not read a newest-N window.

## Verification

- **Unit and service tests:**
  - ref normalization, lane validation, and Evidence validation;
  - `observedAt` parsing and rendering;
  - Idea lane fields round-tripping, and surviving a status-only re-capture.
- **API tests:**
  - `POST` and `GET /api/evidence`, including the filters;
  - the `/api/ideas/detail` join, through an `idea:<key>` ref after a status re-capture and
    through an `event:` prefix ref;
  - a 404 for an unknown key.
- **Recall:** `kinds:["evidence"]`, and embedding inclusion.
- **Contracts:** the MCP contract snapshot (12 tools), the REST contract matrix, mappings, and
  wire fixtures, and the gateway pytest.
- **Verify through use:** run the packaged jar on a spare port against a scratch database. Over
  MCP, capture one Idea and one Evidence that supports it, then call `recallIdea` and see them
  together. Deploying to `:8766` is Nathan's call.

## Deviations

- The existing `IdeaEventReader` already reads arbitrary event types through keyset pages, so
  Evidence reuses it instead of adding a duplicate reader port.
- The original draft used REST/MCP callback tests only. The current integration additionally
  verifies a packaged jar against private disposable fixtures; it never touches the live service.
- Empty secondary-lane and Evidence-link arrays are treated as absent, preserving earlier Idea
  lanes on status-only re-capture. The effective home lane uses `repo` when `project` is omitted.
