# Preserve hook transcript provenance without losing captures

## Scope

Project a canonical top-level `metadata.transcript_path` from explicit hook fields. Main sessions
use only transcript_path/transcriptPath; derived child sessions use only
agent_transcript_path/agentTranscriptPath. Keep lineage and legacy rawHook behavior, while durable
capture continues excluding rawHook. No hook path inference/file reads, live wiring or Java
production changes. Selected issue: [NAT-312](https://linear.app/nathanmauro/issue/NAT-312/preserve-safe-transcript-provenance-through-durable-hook-capture).

Select the first nonblank string, snake case before camel case. Preserve its bytes/characters
exactly; omit a selected locator longer than 4,096 characters or containing NUL rather than trying
a lower-priority field. Durable sanitization additionally omits a top-level locator that redaction
would change, without rejecting useful text or lineage. Nested payload fields keep their existing
rules. Legacy keeps its existing server-side redaction behavior and does not gain a Python dependency.

## Verification plan

- Reproduce actual hook omission with a private outbox held against delivery and fake legacy curl.
- Verify both normalizers, precedence, child/parent isolation, privacy omission and unchanged missing
  fields. Inspect queued bytes and attempts; no live recorder/network is needed for these fixtures.
- Run focused/full outbox tests and the shell normalization smoke, including Python 3.9.
- Existing SQLite/PostgreSQL HTTP contracts already exercise top-level path retrieval and source
  reader tests enforce root/identity checks. Extend only the existing HTTP controller test with
  synthetic parent/child JSONL candidates to verify legacy parent rejection and child recovery;
  no Java production change.
- Record results, fixture cleanup and an exact frozen path/hash handoff for coordinator Git review.

## Results

- Before the fix, actual hook fixtures omitted explicit main/child locators. Three focused test
  methods produced 35 expected failing subcases for projection and optional-path handling.
- After the fix, all five new focused test methods passed. The complete outbox suite passed all
  67 tests on Python 3.9.6. The combined shell smoke passed its legacy normalization, lineage,
  transcript projection and never-fail checks, then passed the same 67 durable tests.
- `AgenticControllerTest` passed all 29 cases, including real controller ingestion and transcript
  retrieval against synthetic parent/child JSONL: a parent-only candidate is unavailable without
  hiding recorded captures; an explicit child locator recovers only the child's transcript.
  Provider-dependent features, including the judge, were disabled.
- Scoped Palantir formatting, Python syntax compilation, Bash syntax checks and `git diff --check`
  passed. Initial sandbox-only failures were loopback fixture binding and Mockito JVM attachment;
  scoped reruns passed without changing production code or test assertions.
- Fresh read-only review found no actionable defects. Hook fixture queues, fake-curl files and
  synthetic JSONL were temporary and cleaned; no installed hook, live database, configuration,
  service or provider was accessed. Normal Maven test reports and existing fixture DB/export
  artifacts follow the test class's existing lifecycle.

This is source-only verification. No Git publication, deployment, PostgreSQL rerun or duplicate
full backend suite was performed in this lane. Existing server root/identity checks remain
unchanged. NAT-244 live wiring and transcript completeness remain separate and unverified.

Coordinator handoff: the seven owned files cover the two normalization/sanitization sources,
Python and shell regressions, the existing HTTP controller test, durable-capture documentation and
this plan. The next action is coordinator review, exact-path commit and CI/publication for NAT-312.

## Coordinator acceptance

Integrated main without changing the frozen production paths. Root independently ran the actual
shell smoke and all 67 outbox tests, plus all 29 HTTP controller tests with no skips. Opus accepted
the implementation. Root strengthened the main/child loop regressions to require one newly queued
row for every invocation, preventing a previous matching row from hiding accidental capture loss;
those loops explicitly exercise the Claude source. Final hook verification after that test-only
change passed. The selected repository slice leaves NAT-244's live wiring and completeness checks
open; installed hooks and services remain unchanged.
