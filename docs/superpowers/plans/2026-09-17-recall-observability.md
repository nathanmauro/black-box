# Recall observability implementation and verification

## Goal and safety contract

Measure future recall invocations and their actual lexical/semantic behavior through the existing
log pipeline. Preserve SQLite/local-first behavior, privacy, existing request defaults, and the
running service. Do not reconstruct unsupported historical usage totals or provision a new cloud
stack. Use isolated worktrees and fake dependencies for failure scenarios.

## Established gap

Code inspection before changes: `ContextService` had no usage counters or completion logging;
semantic `RuntimeException`s silently returned lexical results. Successful recall alone could not
prove semantic execution. Corpus session/event totals did not count recall invocations. Supplemental
`fetchVectors` failures escaped despite the page already being ranked. Existing Actuator metrics
and SLF4J logging provided an instrumentation path without another exporter.

## Independently verifiable slices

1. Add one bounded completion record and process-local metrics around the core use case, including
   stage outcomes, gate counts, generated correlation, and safe project/client/purpose attribution.
2. Wire HTTP, initialized MCP metadata/optional declarations, embedding HTTP correlation, and the
   existing session-start hook. Keep older callers source/wire compatible. Preserve valid ranked
   results on optional score-enrichment failure.
3. Verify behavior and redaction with fake-backed tests, actual controller and JSON tool callbacks,
   hook script fixtures, contract snapshots, and the full backend suite. Compare live request
   latency only in the coordinator's activation phase.
4. Coordinator separately projects this schema through the existing collector, verifies saved
   HyperDX views, integrates only owned changes into main, publishes the requested branch state,
   and activates through the supported local deployment script. Do not overwrite the live JAR.
5. Verify status, successful/no-match real recall, audited telemetry arrival, and isolated failure
   evidence; leave a compact private handoff containing exact deployment/remote/view evidence.

## Acceptance evidence

Instrumentation tests cover semantic eligibility, completion versus contribution/returned overlap,
embedding unavailable/errors, vector errors, no matches, relevance rejection, preserved lexical
fallback, optional enrichment failure, genuine core error propagation, bounded attribution,
server-generated IDs, nested-context cleanup, embedding HTTP correlation, and independent metric/
logger failure guards. MCP input snapshots add only the three optional telemetry declarations;
response schema is unchanged. The hook fixture suite labels its requests `test`.

The detailed schema and interpretation limits are in [recall-observability.md](../../recall-observability.md).
Live deployment, collector activation, and publication are a separate coordinator-owned checkpoint;
a passing local suite alone does not establish them.

## Verified candidate

- Full Maven suite: 592 tests reported, zero failures/errors, 10 skipped.
- Targeted recall/MCP/embedding/contracts: 57 tests passed; recall-hook fixtures: 12 passed.
- Packaged service exercised over real HTTP and MCP against a temporary SQLite database and
  controllable embedding HTTP server: lexical success, no match, hybrid success, embedding-outage
  lexical fallback, initialized MCP client classification and matching outbound correlation IDs.
  All five completion records were labeled `test`; no fixture content appeared in telemetry.
- The isolated service was stopped and its temporary database removed. Production dependencies
  were not interrupted to test failure behavior. Packaging and whitespace checks passed.

## Local verification checkpoint

- Targeted telemetry, MCP callback, hybrid recall, embedding HTTP, and MCP snapshot tests passed.
- Full `mvn -q test`: 592 tests reported, zero failures/errors, 10 skipped. The additive MCP method
  signature required updating the existing reflection-based contract check to inspect the annotated
  method; the original Java overload is retained for callers.
- `scripts/test-recall-hook.sh`: all 12 fixture cases passed, including bounded declaration headers.
- `git diff --check`: clean. Packaging and end-to-end activation evidence are coordinator checkpoints.
