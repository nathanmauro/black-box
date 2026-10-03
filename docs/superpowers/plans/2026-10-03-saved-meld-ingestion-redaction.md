# Saved meld ingestion redaction (NAT-307)

## Contract

Apply the configured ingestion redactor to newly saved meld/braid free text (title, body,
provider, model and prompt version) and metadata before persistence and the save response.
Validate artifact kind, ownership, execution mode and ordered session selection independently;
never redact canonical identifiers, server-derived input provenance or existing rows. Keep
ordinary metadata types, nulls and order except the existing documented secret-key policy.
Reuse the public recording boundary; do not import another module's internals or change the
redactor's rules, disabled/custom behavior or 50,000 UTF-16 scalar scan ceiling.

## Verification sequence

1. Reproduce the current bypass through real loopback HTTP on a random port and a disposable
   SQLite database, using synthetic credential strings only. Compare save response, SQL and GET.
2. Expose `recording.IngestionRedactor` and inject it into the meld save application service.
3. Verify default, disabled and custom rules, assigned/unassigned artifacts, ordinary nested
   metadata/nulls, large Unicode scalars, classification independence and transaction rollback.
4. Verify existing captures, provenance and old saved rows are unchanged; run focused and full
   backend tests plus scoped pinned Java formatting and whitespace checks.
5. Update public behavior documentation. Root owns Git, PR, CI, merge, Linear and cleanup.

No provider calls, installed service operations, historical scrub or MCP/reader changes are in scope.
PostgreSQL fixture use requires coordination; optional local skips must be reported honestly.

## Results

- Before production changes, the two real HTTP baseline cases (assigned and unassigned) confirmed
  that save responses, SQL and GET all retained synthetic credentials. Their redaction assertions
  failed as expected; capture preservation and response/storage consistency checks passed.
- Added the public configurable ingestion interface and save-boundary calls only. No schema,
  reader, MCP, historical-row or shared-redaction-rule changes were made.
- Final focused verification passed 22 tests with no skips: 10 SQLite redaction cases, the same
  10 PostgreSQL cases, and both database restore cases. These cover ordinary melds and both braid
  ownership modes, nested metadata and nulls, default numeric `tokenCount` replacement with a
  string, custom-only and disabled behavior, Unicode-safe clipping under default/custom rules,
  classification/mode/provenance preservation, historical reads and atomic rollback.
- A first large-scalar fixture used an oversized JSON member name rejected by the existing Jackson
  parser limit. The final real HTTP case uses accepted string values; parser limits were unchanged.
- The first PostgreSQL-enabled full run reached an existing restore-test prerequisite failure:
  native PostgreSQL clients were absent from the test process PATH. Prepending the existing client
  directory for that process fixed the restore check; no installation or global setting changed.
- The final full backend suite reported 1,061 tests, zero failures/errors and four optional skips
  (two model-backed recall evaluations, Elasticsearch integration, and platform-specific editor
  navigation). Both new HTTP suites ran all 10 cases with no skips. PostgreSQL cleanup verifies
  that every random schema owned by the new fixture is removed.
- Scoped pinned Palantir formatting checks and `git diff --check` passed. All application HTTP
  fixtures used random loopback ports and disposable storage. No installed service, provider,
  global hook or canonical user database was touched.

CI requires `ProjectMeldRedactionPostgresHttpTest` to run without skips. The coordinator verified
the exact CI guard against the final local reports and integrated the intervening frontend-only
main changes. Publication, merge and execution-record updates remain coordinator-owned.
