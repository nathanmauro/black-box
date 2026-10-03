# Derive transcript duplicate suppression from canonical output

## Scope and safety

Session transcript reads currently suppress repeated tool text only when metadata.rawHook contains
the same string. Durable capture deliberately drops rawHook, making equivalent captures produce
different transcript API responses. Replace that dependency with strict decoding of the canonical
toolOutputJson string and the existing trimmed text equality. Keep metadata projection unchanged.

Only the returned transcript projection changes. Preserve stored event bytes, timestamps, ordering,
pagination, tool payloads and distinct status text. Malformed JSON, non-string JSON, trailing tokens,
null output and truncated prefixes must not suppress text. Do not infer duplicate prefixes or alter
capture truncation. The UI already hides exact short duplicates; this is an API consistency fix.

No live database/service, private transcript directory, provider, Git write or deployment is allowed.
Use isolated temporary SQLite and explicitly unavailable JSONL paths, provider-disabled Spring
contexts, and fake hook input. The coordinator owns review, Git integration and publication.

## Verification

Reproduce through durable sanitizer to idempotent HTTP capture to transcript read before changing
the service. Confirm legacy/durable parity, exact decoded strings including escapes and whitespace,
distinct status preservation, malformed/non-string output safety and truncated-prefix preservation.
Compare complete canonical SQL rows before/after transcript reads and idempotent replay. Keep
existing ordering and local-transcript tests passing. Run focused tests, relevant backend/module
checks, scoped Palantir formatting and diff check, then record evidence and freeze the owned diff.

## Evidence and handoff

Before the service change, the isolated HTTP regression failed on the durable capture's repeated
text; distinct/truncated control cases passed. Five new unit cases also failed, including a distinct
status hidden by contradictory rawHook metadata. The HTTP fixture invokes the actual Python durable
sanitizer with a controlled environment, captures through /api/events/idempotent, reads the session
transcript with JSONL unavailable, then replays the capture. It compares the complete canonical SQL
row and canonical event API response before and after reads/replay, checks output/metadata projection,
and preserves timestamp precision. Python 3 is required, as for the existing hook/storage checks.

After the fix, all 35 focused tests passed without skips: SessionTranscriptServiceTest,
CanonicalToolTranscriptHttpTest, JsonlTranscriptMessageSourceTest, RecordingApplicationModuleTest,
ApplicationModuleStructureTest and PackageArchitectureTest. Native mvn test then reported 735 tests,
zero failures/errors and 37 expected skips: 33 opt-in PostgreSQL cases and four existing optional or
environment-dependent cases. No PostgreSQL fixture was started for this read-projection-only change.
Scoped Palantir formatting/check and git diff --check passed. The IDE was open on another project,
so native Maven supplied compilation and test verification.

Codex worker /root/continuity_backend leaves five owned paths uncommitted on
codex/transcript-canonical-output for the coordinator's review and authorized Git finish. No live
service, database, private transcript directory, provider, deployment or Git metadata was changed.
The new HTTP fixture closes its application and removes its temporary SQLite/files; its sanitizer
process exits or is terminated on timeout. Canonical event storage, ordering and cursor behavior are
unchanged. Truncated-prefix ambiguity remains deliberately unresolved; no new UI behavior is claimed.
