# Validate optional structured confidence at the shared capture boundary

## Scope and evidence

NAT-159's original required-field/no-write/Handoff acceptance is already met and remains separate.
A disposable real HTTP/MCP probe confirmed Decision and Projection confidence -0.1 and 1.1 were
accepted and persisted despite their documented 0..1 meaning. Reject supplied non-finite/out-of-range
confidence before persistence, with a field-specific message; keep omission/null and 0/1 valid.

Decision validation must cover normal and replacement captures. Projection validation applies only
to retained titled paths, preserving intentional null/blank filtering and first-five capping. Error
paths use the original input index so the caller can correct the right entry. No new DTO constraints
may reject confidence on a path the service intentionally discards. No schema, stored-history or
existing successful response change is needed.

No live database/service, provider, private configuration, Git or Linear changes. Use disposable
SQLite and disabled external systems. The coordinator owns independent review, Git and publication.

## Verification

Reproduce before fixing with actual REST and Streamable HTTP MCP; include negative, above-one and
representable non-finite coercions/overflow. Assert actionable field errors without Java internals
and unchanged event/session counts. Test optional/boundary success, filtered/capped paths, original
path index, and replacement rejection preserving its original event/relation state. Run relevant
unit, transport, module and backend suites, scoped Palantir formatting, and diff check; record limits.

## Results and handoff

The before-fix real transport test ran 24 cases and reproduced 24 accepted-invalid failures: REST
and MCP both admitted Decision/Projection -0.1, 1.1, quoted NaN/Infinity/-Infinity and JSON numeric
overflow 1e309. The new shared guard now returns the same field-specific message through HTTP's
invalid_argument envelope and MCP's isError result, without exposing implementation details.

The focused gate passed 80 tests without failures, errors or skips across StructuredCaptureServiceTest,
CaptureConfidenceHttpTest, StructuredCaptureHttpTest, RecordingApplicationModuleTest,
ApplicationModuleStructureTest, PackageArchitectureTest and McpContractSnapshotTest. Actual transport
coverage includes all 24 invalid values, 16 omitted/null/0/1 success cases, filtering/capping controls
and invalid replacements preserving the original SQL event, session/event counts and relation count.
In-process tests also reject all three non-finite Double values before either recorder method.

Full native mvn test passed: 770 reported tests, zero failures/errors and 37 expected skips (33
opt-in PostgreSQL cases and four existing optional/environment cases). No PostgreSQL fixture was
started for this pre-persistence validation change. Scoped Palantir check and git diff --check pass.
The current schemas and generated MCP contract are unchanged; no request-field constraints were
added that would inadvertently validate an intentionally discarded Projection path.

Codex worker /root/continuity_backend leaves five owned paths uncommitted on
codex/capture-confidence-validation for the coordinator's independent review and authorized Git
finish. The new HTTP context closes and its temporary SQLite database is removed. No provider,
private configuration, live service/database, deployment, Git or Linear state was changed. NAT-159's
original required-field acceptance remains separate from this confidence correction. Existing stored
captures are not rewritten, and generic event-ingest metadata remains outside this structured API rule.
