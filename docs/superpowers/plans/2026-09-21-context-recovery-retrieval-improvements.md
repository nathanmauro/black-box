# Improve context recovery without weakening bounded recall

Status: implementation authorized after plan review; see the implementation checkpoint below.
Prepared 2026-09-21 against `4d6fcc05d2caba0e0adfcffce19de2510bbee500`.

## Recommendation and scope

Start with an additive compact search contract and discoverable query semantics. Broad search
currently returns whole event records, while MCP recall already provides a useful bounded path.
Improve the former before changing recall ranking. Next, expose source identity and conservative
copy grouping so an agent can distinguish a recorded observation from the conversation it quotes.

This plan covers retrieval presentation, query diagnostics, provenance, source navigation, and
their evaluation. The original planning pass authorized no implementation or publication. Subsequent
user instructions authorized implementation and commit/push/PR/merge; service deployment remains
separate. No capture change, reindexing or database migration is included. This work does not
replace the separately owned successful case study, Postman collection, or presentation. Fast mode
remains off. Private transcripts, actual query subjects, source IDs, and workstation paths remain
in the supplied private evidence packet; public examples below are synthetic.

## Evidence and confidence

Historical evidence is indexed privately as H1–H8, matching the eight entries of
`original-investigation-requests.json`. The selected original recall response and source-message
excerpts were also read. The recall extract explicitly omits unrelated sections and other hits;
it is not a complete wire capture. The historical broad-search results were not replayed or fully
reconstructed in this task. Their outcomes below are attributed to the investigation record.
Repository findings are new source-code observations at the revision above, not proof of the
binary or index state that served the historical requests.

| Finding | Evidence and classification | Consequence |
| --- | --- | --- |
| Broad output overwhelmed the caller | H1 reports a combined batch warning of approximately 352,446 original tokens, with repeated text and nested tool/hook fields. This is a wrapper estimate across several outputs, not a measured search HTTP body. Code confirms raw fields and no search response budget. | Both product payload design and indiscriminate orchestrator printing contributed risk; their individual historical byte/token costs remain unmeasured. |
| Bounded recall succeeded | H2 selected response records five items, hybrid mode, `truncated=false`, with `limit=15`, `maxChars=18000`, and a 720-hour window. One handoff supplied a source identity; H4/H8 recovered relevant original messages. | Preserve this path as a control. It recovered prior intent, not proof of a causal motive for a later setup action. |
| Monitoring copies obscured origin | H1/H5 describe later monitoring and memory copies containing the searched text. Local search sorts matching events by observation time; no origin-aware grouping occurs in broad search. | Copy prominence is observed. Its exact rank contribution, duplication rate, and benefit from suppression remain hypotheses. |
| Date syntax was assumed | H3 used unsupported `before:`. Current parser leaves it as free text. Supported time operators deliberately suppress the Elasticsearch arm; unknown `before:` does not. | Assistant misuse plus weak discoverability; no evidence of a supported date-filter regression. |
| Printer read the wrong shape | Brief reports empty text from reading Elasticsearch hits like local events. Code confirms local `AgentEvent` versus Elasticsearch `{id,score,source,highlight}`. | The immediate failure was an adapter assumption; the heterogeneous public contract makes it easier to repeat. |
| Source-reader failures were external | H6/H7 rejected excessive Codex page sizes; parsing a plain-text error as JSON failed. H8 used the returned older cursor and a valid page size. | Correct orchestration separately. Black Box cannot change another product's limits or guarantee its source availability. |
| Source navigation crossed identity domains | H2 carried internal event/session IDs and a composite client session identifier; original source was read through the external app. Initial local file discovery omitted hidden files. | Internal session ID, client grouping key, external task ID, and local transcript path must remain distinct. |
| Causation remained unproven | Selected original messages establish an authoritative shared-backend requirement and discussion of options, without a recovered explicit causal bridge to the later setup. | Preserve statement/summary/inference distinctions; do not manufacture a missing decision or causal edge. |

No live Black Box API replay, database read, model call, or source-app replay was performed here.
Any later replay must be labeled as a new observation with time, source revision, actual serving
version, configuration, query arguments, and corpus cutoff. Read calls can emit recall telemetry;
they are not necessarily devoid of operational side effects.

## Actual read paths and existing work

Paths below are relative to the repository. Line references describe the inspected revision.

| Layer | Verified implementation and boundary |
| --- | --- |
| Search ingress | `memory/internal/adapter/in/mcp/MemoryMcpTools.java:96–101` under `src/main/java/dev/nathan/sbaagentic/` returns `SearchResponse` unchanged; MCP defaults to 10, clamps to 50. `SearchController.java:24–26` exposes `/api/search`. |
| Search fan-out | `memory/internal/application/SearchService.java:80–120` caps each backend at 100 and returns separate local/elastic collections plus health. Limits apply per arm, not a shared presentation budget. Valid session/time/exact-project/group/negative filters intentionally remain local-only. |
| Canonical local search | `memory/internal/adapter/out/sqlite/MemorySqlQueryAdapter.java:49–150,257–275` matches free terms with AND, searches metadata as well as text, selects full tool JSON/metadata, orders newest first, then limits. A hit may match metadata while its visible text does not explain why. SQLite remains default; preserve the optional PostgreSQL contract. |
| Optional search index | `memory/internal/adapter/out/http/ElasticIndexClient.java:80–143,251–259` uses phrase plus fuzzy OR matching and returns nested `_source` plus highlights. SQL and index retrieval are not equivalent rankings. Positive source/kind/tool/project facets still reach this arm as raw text; they are not equivalent enforced index filters. |
| Capture duplication mechanism | `scripts/hooks/sba-agent-hook.sh:122–162` can put a tool response in both text and tool output, with the original payload additionally in legacy `metadata.rawHook`. `recording/internal/application/EventIngestService.java:110–127,174–182` redacts fields and limits individual scanned strings, but does not impose a whole-response budget. This supports a possible amplification mechanism; the historical ingestion route was not established. |
| Grammar | `query/EventQuery.java:13–27,127–175` supports `session:`, `since:`, `until:`, `last:`, source/kind/tool/project facets and their documented variants. Unknown operators and invalid time values fall back to literal free text. `TimeSpec.java:15–24,61–86` defines time boundaries. The configured clock uses the server's default zone (`SbaAgenticApplication.java:18`). |
| Recall control | `memory/internal/application/ContextService.java:127–187` retrieves a full candidate pool, fuses lexical and semantic ranks, then limits the returned items. `RecallResultClamp.java:16–68` budgets recalled text with estimated item overhead, trimming/dropping with a flag. This is not a strict serialized byte or tokenizer limit. HTTP `ContextController.java` does not bind `maxChars`. |
| Source access | `recording/internal/adapter/in/web/EventController.java:86–107` provides exact event/session reads. `SessionTranscriptController.java:22–28` provides bounded transcript paging; its `before` parameter is a cursor boundary, not search `before:` syntax. `JsonlTranscriptMessageSource.java:56–117` requires a recorded, readable, confined, identity-checked local transcript. It cannot fetch every external app task. |
| Existing navigation | `RecalledItem` already includes `eventId`, `sessionId`, `clientSessionId`, source and timestamp. `frontend/src/pages/RecallPage.tsx:289–298` links to the exact recorded event. This is distinct from opening the original external conversation. |

Do not repeat completed work from [Recall links](2026-07-29-recall-links.md),
[readable handoffs](2026-09-19-readable-handoff-context.md), or
[recall windows and help](2026-09-20-recall-windows-and-help.md). Preserve their source links,
readable disclosures and rolling windows. Existing grammar fixtures and frontend parser must
remain aligned. [Agent integration](../../agent-integration.md) currently gives broad search only
a short tool description; add practical guidance there rather than another competing guide.

Existing project issues were read without modification. The continuation-evaluation issue remains
open; its private tracker mapping is in the evidence packet. This plan supplies a retrieval
regression case, not completion of that broader issue. Supersession and memory curation are
separate work; copy grouping does not imply either. [The current evaluation](../../real-resumption-evaluation.md)
uses known event IDs and measures checkpoint reconstruction, not discovery or ranking. Retain its
ordinary-search comparator and accepted-action gates instead of claiming this case clears them.

## Prioritized implementation slices

### P0 — Freeze a small evaluation case before behavior changes

**Owner:** Black Box evaluation/tests; coordinator maintains the private evidence mapping.
**Rationale:** H1–H8 provide an actual failed/successful journey but not a controlled comparison.

Create a synthetic fixture with an originating user statement, an assistant proposal, a captured
handoff, repeated later observer copies, a current-search self-copy, and an unrelated similar
topic. Include a multi-megabyte nested tool-result sentinel, date-boundary records, an index-only
hit, malformed source identities, and a missing transcript. Use invented IDs and text; preserve
the causal ambiguity. Keep private source excerpts and answer keys outside Git. Do not ingest
fixtures into canonical history or send them to a remote model.

Freeze the rubric and baseline outputs before implementation. Public artifacts should contain
synthetic data and numeric measurements only. Alternative: replay only the live private corpus;
reject as the sole gate because ingestion, relative time and index state drift. Synthetic tests
establish contracts; a separately authorized private replay establishes applicability to the case.

**Acceptance:** fixture has independently labeled origin/copy/unknown relationships, exact expected
filter boundaries and source targets, no private strings, and an answer key that explicitly says
the causal bridge is unproven. Existing recall control and lexical fallback remain green.

### P1a — Add bounded compact search with one hit schema

**Owner:** memory application/read adapters, REST/MCP adapters, contract tests and integration docs.
**Rationale:** highest-confidence product gap; addresses H1 and the shape mistake together.

Prefer a new compact endpoint and MCP tool (proposed names `/api/search/compact` and
`searchContext`) over silently changing `SearchResponse`. Both should share a typed DTO and
bounded projection. Preserve legacy `/api/search`, `searchSessions`, CLI JSON and existing UI
consumers. Make the compact tool the recommended broad-discovery path in its description/docs;
identify the old path as raw diagnostic output so the new tool is actually discoverable.

Proposed contract to freeze in P0:

- Common hit fields: canonical event/session IDs when known, client session ID, source, event
  kind/role, observed time, a plain-text excerpt, backend membership, and source-resolution status.
  Missing fields are explicit null/unknown, not invented values. Capture type is not proof that a
  sentence was said by the user. Scores retain backend-specific meaning or are omitted.
- Default global `limit=10`, maximum 50; default serialized payload budget 24,000 UTF-8 bytes,
  configurable from 2,048 to 64,000 bytes. Name the option `maxBytes` to avoid implying recall's
  `maxChars` has the same semantics. Count the entire serialized application envelope, including
  diagnostics, escaping, IDs and metadata. Measure MCP framing separately; it has additional cost.
  Return a small validation error for impossible budgets. Bound query length and all string fields.
- Excerpts default to at most 600 Unicode characters. Never include `rawHook`, full metadata,
  `toolInputJson`, `toolOutputJson`, or nested prior responses. Exact source fields are not silently
  shortened into a different identity: use unresolved status if an identity cannot fit its bound.
  Indicate excerpt cuts, dropped hits, and presentation truncation explicitly.
- Preserve ordering within each backend and use a documented deterministic alternation across
  arms for the shared limit; do not pretend their scores are comparable. Collapse the same verified
  canonical event ID returned by both arms, retaining backend membership. Distinct copied events
  remain distinct until P2. Keep local canonical fields when an index copy is stale; index-only
  identities remain unresolved until verified against canonical storage.
- Report returned counts and bounded candidate counts, not an invented total-match count.
  Distinguish `candidateLimitReached` (more may exist), payload truncation, skipped backend,
  unavailable backend and empty results. Health/error fields must be allowlisted and bounded.
- Project before expensive raw response materialization: a local lightweight SELECT must not
  fetch/decode full tool JSON/metadata for every returned hit; use bounded text projection. Use
  index `_source` filtering and bounded highlights. If local metadata matched but excerpt cannot
  explain it, report that limitation without serializing arbitrary metadata. A remaining scan of
  metadata in the predicate is not fixed by projection; measure it separately.

**Alternatives:** a compact view flag is smaller at ingress but introduces a union return schema;
replacing raw defaults breaks consumers; client-side trimming alone leaves server materialization
and transport unbounded. A common excerpt DTO does not require global ranking or a new index.

**Compatibility/privacy:** additive contract; canonical event text and capture remain intact.
Existing exact reads still return potentially large/private content and require deliberate bounded
printing. Compact output reduces exposure but is not a substitute for ingestion redaction.

**Acceptance:** actual HTTP and MCP tool invocation with the nested sentinel fits the declared
budget, contains none of the raw fields or complete nested sentinel payload, retains usable identifiers, and renders
both backend shapes without blank-field adapter errors. Exercise index disabled/failure, long
Unicode/escaped strings, giant identifiers, one huge first hit, zero hits and both arms at limit.
Verify raw contract fixtures, CLI and existing UI consumers stay unchanged. Record wire bytes,
projected characters, latency and allocation evidence separately; do not call them token counts.

### P1b — Explain filters and fail visibly on misleading date syntax

**Owner:** compact-query adapter, shared grammar fixtures, tool descriptions and agent integration docs.
**Rationale:** H3 was avoidable misuse; the correct grammar already exists.

Expose `appliedFilters`, resolved time boundaries/timezone, and backend coverage in the compact
response. Preserve the existing local-only behavior for time/session/negative/exact/group filters.
For the new compact path, also suppress Elasticsearch for positive source/kind/tool/project facets
until they have a real index filter compiler: legacy search currently sends these as raw free text.
Label Elasticsearch as skipped for unsupported filter semantics, not empty or comprehensively
searched. Keep legacy behavior unchanged and explain this deliberate compact-contract difference.
Do not enable a fuzzy index arm for filtered requests merely to fill the page.

In the new compact contract, return an actionable diagnostic for unquoted `before:<date>` and
invalid recognized date forms. Do not silently drop or rewrite them. Provide an explicit way to
search such text literally, with tests preserving quotes through diagnostic parsing. Keep legacy
grammar fallback for existing consumers. Do not reject every colon: paths, URLs and literal text
must keep working. Add examples adjacent to the MCP schema and in the existing integration guide.

Explain that `until:2026-08-18` means all of August 18 in the server clock's zone, with an exclusive
upper bound at the next day's start. For strictly before August 18, a whole-day query can use
`until:2026-08-17`; an exact `until:` instant is inclusive. Never advertise these as a general
strict-before-instant alias. Resolve one clock snapshot per request and expose it for audits.

**Alternatives:** add a `before:` alias with explicit exclusive semantics later; unnecessary for
this first slice and easy to get wrong. A new time-filter API would duplicate working grammar.

**Acceptance:** frozen clock fixtures include midnight, fractional seconds, offset timestamps and
a DST boundary; local results obey the existing time contract and Elasticsearch is not called.
Positive-facet fixtures also exclude out-of-scope records and verify explicit index suppression.
Unsupported syntax gets an explanation before retrieval. Literal-token queries still work.
Documentation examples are executed through HTTP/MCP in isolated use, not tested only as strings.
Legacy parser and frontend grammar-fixture tests remain unchanged unless deliberately extended.

### P2a — Surface provenance and group only demonstrable copies

**Owner:** compact read model; optional small search UI follow-up after API evaluation.
**Rationale:** H5 shows string occurrence is not origin. Ranking changes are not yet justified.

Expose current event identity/type/role and observed time immediately. Add a bounded explicit
`excludeSession` option to compact search, resolved against internal or client identity and applied
before candidate limits. This is caller-selected, not authenticated identity or an automatic
exclusion of the only useful current-session handoff. As with other unsupported index filters,
suppress the index arm until equivalent exclusion is actually implemented and tested.

Distinguish recorded statement, captured summary, known observer/tool output and unknown provenance.
Use role/type and explicit stored relationships; do not classify a record as the original merely
because it is oldest, contains a phrase, or has a confident model summary. Heterogeneous content
inside a handoff still needs per-claim source verification. Add copy-grouping only for verifiable
shared source references, retaining member IDs/timestamps and counts. Exact normalized text alone
can identify a similarity group with unknown relationship, never a proven copy or shared decision.
Prefer a verified originating member when one exists; otherwise label the group origin unknown.
Return a bounded member preview and a way to retrieve members without claiming omitted evidence
is absent. Keep independent repeated decisions and conflicting statements separate.

Grouping only a final limited page cannot recover an origin excluded by that page's candidate cap.
Measure this first, then use a separately bounded candidate expansion (proposed maximum 200 per
arm) with grouping before the presentation limit. Do not exhaustively scan the corpus to fill a
page. Report candidate exhaustion and suggest time/session narrowing when the source remains
outside the candidate pool; origin discovery is not guaranteed in arbitrary copy volumes.

**Alternatives:** blanket exclusion of monitoring tools loses useful observations; an LLM origin
classifier or embedding-similarity dedup adds cost and unsupported authority; recency demotion
alone cannot establish origin. Do not change ingestion, backfill history, or recall ranking here.

**Acceptance:** origin remains discoverable in the frozen fixture with at least 20 later copies,
including a source initially beyond the presentation limit; a second fixture exceeding the
candidate expansion cap reports incomplete coverage rather than false absence. Identical local/index
identity is counted once; uncertain near-copies are not merged; current-session exclusion works
before limiting; all grouped members remain accessible. Zero monitoring-copy-as-origin claims in
the frozen recovery exercise. Measure top-five unique verified sources and duplicate exposure
before considering any rank-weight change. Stop if grouping hides relevant independent evidence.

### P2b — Make source navigation explicit without promising universal transcripts

**Owner:** recording/source-reference adapter plus compact response; external reader orchestration
owns verification of app tasks. Reuse existing Recall/Browse links.

Return separate internal recorded-event references and external-source candidates. Preserve the
raw client grouping ID. Prefer explicit, validated provider/task metadata for external identity.
A tested parser for a known composite voice-session format may suggest a task ID, but its status
must be `candidate`, not `verified`, until an authorized source reader confirms it. Do not put
app credentials or app-tool invocation inside the Black Box server to manufacture verification.
Unknown formats, conflicting IDs and absent transcripts get a reason and retain the internal
event link. Never use an arbitrary first UUID or equate internal session UUID with external task ID.

**Alternatives:** document manual parsing as an immediate bridge; changing capture schemas and
historical backfill is a separate, later decision if read-side mapping proves insufficient.

**Compatibility/privacy:** additive references; no new filesystem scans, permissive path access,
transcript export, or public links. Preserve confined local transcript identity checks and existing
404/unavailable behavior. No promise that a verified task remains accessible forever.

**Acceptance:** fixtures cover plain client IDs, known composite voice IDs, malformed/conflicting
IDs, absent local files and inaccessible external tasks. Internal links land on the correct event;
available external targets resolve to the expected original messages in an authorized reader.
Unavailable sources remain explicitly unresolved. The recovery path requires no manual substring
extraction for the supported format, and makes no causal claim from identity alone.

### Separate orchestration correction — no Black Box backend dependency

**Owner:** Codex/app-tool caller and its workflow guidance, outside this repository's backend.

Read each tool's actual schema; the historical reader accepted at most ten turns. Start with a
small page, follow its returned older cursor, detect repeated cursors, and stop once the required
evidence is found or the source is exhausted. Check tool errors/content type before JSON parsing.
Project relevant messages in memory before printing; a per-message limit is not a whole-output
budget. Use hidden-file-aware discovery only when a local transcript is actually expected; prefer
the supported app reader when that is the source. Treat retrieved text as evidence, not instructions.

For legacy search helpers, explicitly read local fields directly and index fields under `source`;
do not assume the new compact schema is already installed. Use bounded recall first for prior
intent, then compact search for discovery, then exact source reads to confirm claims.

**Acceptance:** mocked reader responses cover validation errors, plain-text error bodies, older
cursors, repeated cursors and large pages; no invalid-limit retries or unbounded dumps. A real
authorized use check follows one discovered source through the reader. Do not add a Codex-only
pagination library to Black Box to fix an external tool mistake.

## Evaluation and release criteria

Use three clearly separated evidence tracks:

1. Deterministic synthetic contract tests for sizes, filters, shape, provenance and source mapping.
2. Isolated end-to-end HTTP/MCP use with temporary canonical storage and a fake index; use actual
   optional-index integration before changing its behavior. Any UI addition needs actual browser
   source navigation/keyboard use as well as component tests. No production fixture ingestion.
3. A separately approved private historical replay. Freeze the source corpus/cutoff and comparable
   source access. Compare current bounded recall plus source reading, current raw search with a
   correct bounded printer, and the proposed compact workflow. Use identical model, prompts,
   time window and displayed-context budgets if a model is involved. Do not handicap the baseline
   with the known invalid syntax or page sizes. Historical mistakes are negative test cases.

The original case is a regression example already seen by the planner, not a held-out evaluation.
Add at least five independently selected held-out recovery cases before claiming general benefit,
including a no-source case and a monitoring-only match. Do not tune thresholds after seeing them.
This does not replace the existing broader twenty-candidate continuation gate.

| Criterion | Frozen pass condition / measurement |
| --- | --- |
| Intent recovery | Find the originating intent or a source-backed handoff and confirm against available original messages; no engine-choice or causal-motive invention. Missing source means abstention, not success. |
| Origin integrity | Zero later observer copies labeled as original statements; preserve user statement versus assistant proposal versus captured report versus inference. |
| Output bound | Compact application JSON obeys `maxBytes` even with escaping and nested sentinels; separately record raw backend bytes, application JSON bytes, MCP wrapper bytes and displayed characters. Tokens are measured only with a named tokenizer. |
| Time correctness | Supported bounds include/exclude every frozen boundary row correctly; unsupported forms are diagnosed, and skipped index coverage is explicit. |
| Shape and identity | Same compact field locations for both backends; all resolvable canonical IDs navigate correctly, all nonresolvable IDs are marked; no manual parsing for supported external formats. |
| Recall preservation | Original selected recall observation stays historical; frozen equivalent fixture preserves useful handoff discovery, text, IDs, kind/time limits, lexical fallback, and honest truncation. No claim that a live rerun must return exactly five as the corpus changes. |
| Efficiency | Record source-reader calls, invalid calls, total displayed bytes/characters and elapsed time. Target zero invalid calls and zero wrapper truncation. Claim faster recovery only if paired observations show it without lost source accuracy; no retrospective speed estimate. |
| Negative/compatibility | Honest zero results and unavailable-source outcomes; legacy wire/CLI/UI behavior unchanged; no raw payload leakage or new source access permissions. |

## Exact next implementation starting point

After Nathan approves implementation, start with P0 plus P1a/P1b only. First add a synthetic
oversized-search regression beside `src/test/java/dev/nathan/sbaagentic/search/SearchServiceTest.java`
and MCP contract coverage beside
`src/test/java/dev/nathan/sbaagentic/memory/internal/adapter/in/mcp/MemoryMcpToolsTest.java`.
Freeze the new compact DTO, global limit and byte-budget semantics before implementation; retain
the old `SearchResponse` fixture as a compatibility assertion. Add the unsupported-date diagnostic
case beside `query/EventQueryGrammarTest.java` without redefining the legacy grammar.

Then implement a distinct compact read projection through `MemorySearchOperations`, `SearchService`,
`MemoryEventReader`/`MemorySqlQueryAdapter`, `SearchIndex`/`ElasticIndexClient`, and the REST/MCP
adapters. Update contract snapshots intentionally and document the new tool and filter coverage in
`docs/agent-integration.md`. Run targeted query/search/MCP/wire tests first, then relevant backend
and PostgreSQL contracts and isolated actual tool/HTTP use. Run `git diff --check`. No live service
change follows automatically from successful tests.

Advance to P2 only after bounded search and diagnostics pass. Use its origin/grouping measurements
to decide whether ranking changes are needed at all. Keep HTTP recall budgeting, broad semantic
index expansion, supersession, automatic inference, new embeddings and whole-corpus dedup outside
this first implementation. The observed success already supports retaining the bounded recall
route while removing the broad-search and source-navigation friction around it.

## Planning checkpoint

Only this plan and a private planning evidence index were written. Source review and existing-plan/
issue inspection were completed; no implementation tests or live retrieval replay were run.
The private index holds session/transcript pointers and historical evidence identities. The next
action is Nathan's review of P0 + P1 scope, followed by the exact regression starting point above.


## Implementation checkpoint

Implemented additive `searchContext` and `/api/search/compact`, sharing an explicit JSON serializer
for the byte budget. Legacy raw search and recall contracts remain intact. Compact storage/index
projections avoid fetching arbitrary raw fields, normalize results, disclose filter coverage, and
reject misleading time syntax before retrieval. Actual HTTP reproduction found a variable-fraction
ISO timestamp comparison error; the new compact SQL path normalizes precision for comparisons
and ordering. Legacy behavior was preserved. That expression may require a sort rather than the
existing timestamp index; response-size improvement is not a measured query-speed claim.

The read-side provenance slice includes pre-limit session exclusion, origin-unknown grouping of
identical complete observer text, canonical event links, and candidate-only external task IDs.
There is no reliable historical copy-reference field to validate, so no proven-copy grouping or
backfill was invented. Grouping happens within a disclosed maximum of 200 candidates per arm;
missing origins beyond that cap remain unknown. Independent decisions remain separate. No UI
redesign or external-reader implementation was needed.

Synthetic actual HTTP reproduction: a one-row legacy result containing many bounded nested fields
was 2,401,441 bytes; the same event's compact HTTP and MCP application JSON were each 1,226 bytes
under a requested 2,048-byte limit. This is one controlled payload-size comparison, not a latency,
allocation, productivity or token-cost benchmark. A first test with one giant field hit existing
per-string redaction limits; the final reproduction used multiple sub-limit fields. The fixture
also exercises an original statement and proposal among twenty later observer copies, unsupported
and quoted date syntax, fractional time bounds, candidate exhaustion, exclusion before limiting,
and source-ID navigation. Private historical source content was not replayed or ingested.

The new compact interfaces are separate from `MemorySearchOperations`; this avoids changing the
legacy service constructor and existing callers. Relational filtering shares its builder with raw
search. Index relevance query construction is shared, preserving the legacy query. Snapshot updates
are additive. Small-budget Unicode tests now preserve the first source reference by trimming its
excerpt; source identities themselves are never truncated into different links.

The broader held-out recovery comparison and continuation usefulness gate remain open. These tests
establish functional contracts and synthetic recovery, not general recall superiority. No production
service update follows automatically from passing tests or merging this change.

Verification completed before publication: full Maven suite **652 tests, zero failures/errors,
20 environment-gated skips**. A separate explicitly configured disposable-backend run passed
**36 tests with zero skips**, including the 16-test PostgreSQL contract suite, real Elasticsearch
search, compact unit cases and actual HTTP/MCP flows. The first full pass found one stale
15-tool registration assertion; it now verifies all previous tools plus the new sixteenth tool.
The repeated full run passed. Independent read-only review accepted the corrected diagnostics,
budget trimming and index request. `git diff --check` passed. Temporary database/index containers
were stopped; no canonical service, history, client configuration, deployment or frontend changed.
