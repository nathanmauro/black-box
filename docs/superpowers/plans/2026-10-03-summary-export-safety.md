# Contained, atomic summary exports

## Evidence and scope

The existing controller/service returned HTTP 200 and overwrote an outside fixture file when
an export month directory or final note was a symlink. Path.normalize/startsWith checks lexical
containment only; Files.writeString follows links and truncates the destination in place.
The reproduction used actual controller/service classes and isolated MockMvc/sentinel fixtures.

## Safety contract

- Resolve the explicitly configured root deliberately; configured root aliases are allowed.
  The caller owns that root and keeps its ancestors stable during an export.
- Canonicalize the configured root, then reject symlink descendants and symbolic-link/non-regular
  destination notes using no-follow attributes. Capture and revalidate directory/file identities.
  The local GraalVM/macOS provider does not expose SecureDirectoryStream, so this portable slice
  requires caller-controlled directories to remain stable during export; it does not claim
  resistance to adversarial concurrent directory renames. No native dependency is introduced.
- Render before changing destination state; write a complete private temporary sibling and
  atomically replace the destination. A failed render/write/publication keeps the previous note
  bytes. Existing hard-link aliases retain their bytes when the note is replaced. Repeated exports to an ordinary note remain supported.
- Check each missing/existing subdirectory and revalidate identities before publication. Test
  a deterministic detected descendant swap; leave staged data in a displaced directory rather
  than following the changed path for cleanup. Fail closed when atomic moves are unavailable.
  Do not claim root/ancestor replacement resistance or whole-filesystem crash durability.
- No live export, database, provider, Git or infrastructure changes. Only disposable fixtures.

## Acceptance

Controller-level normal export/re-export, nested directory creation, configured root alias,
lexical traversal rejection, directory/file/dangling symlink refusal and outside-byte preservation.
Fault injection verifies existing-note preservation, temporary-file cleanup, and deterministic
concurrent descendant replacement behavior. Run focused tests, scoped Palantir formatting,
then the relevant full backend suite. Coordinator reviews and performs Git/publication.

## Verification and handoff

- Both original controller regressions failed before the fix: directory/file symlinks returned
  HTTP 200 and overwrote outside fixture sentinels. Both now return HTTP 500 without changing
  outside bytes. Normal and repeated exports remain HTTP 200.
- The focused safety suite has 15 passing cases, including an actual loopback HTTP controller
  fixture with no database/providers; configured root aliases; nested directory creation; symlinks,
  dangling links and lexical traversal; hard-link alias preservation; POSIX modes; partial write
  failure; unsupported atomic move; and detected directory/staged-file replacement.
- A final staged-file replacement fixture reproduced permission copying before the identity check.
  Validation now occurs before permission copying and again before publication; outside content
  and mode remain unchanged. This is a deterministic detected-swap check, not a race-proof claim.
- Full local `mvn -B test` passed 602 tests with zero failures/errors and 25 skips (21 PostgreSQL
  opt-in checks plus four unrelated optional/environment checks). After the final ordering check,
  the focused export/controller and three PostgreSQL classes passed together: 65 tests, zero
  failures/errors/skips (15 safety, 28 controller, 16 PostgreSQL backend, 4 authenticated consumer,
  2 native restore). The initial PG-enabled attempt failed only because the coordinator-owned
  disposable fixture had been stopped; the coordinator restarted it for the successful rerun.
- Scoped Palantir check passed for the service and new test. `git diff --check` passed. All export
  files and HTTP contexts were test-owned and cleaned up; no live export, provider, application
  database or running production service was touched. The coordinator owns PostgreSQL process
  cleanup. The worker did not start or stop it.

Codex source-only handoff: changes are limited to SummaryExportService, SummaryExportSafetyTest,
operations documentation and this plan. No Git/publication/deployment was performed. The coordinator
owns final review, integration, commit and publication. Filesystem crash durability, cross-process
editing and adversarial concurrent directory renames remain outside the documented contract.
