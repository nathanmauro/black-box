# Preserve delayed CLI stdin before acknowledging capture

## Reproduction and contract

The actual ingest process acknowledged a capture after 1.832 seconds while its input pipe was
still empty and open. The producer sent text and closed the pipe after 3.019 seconds; SQLite
already held one event with null text. Immediate input preserved its text. Current CLI sources
were compiled into scratch and run with private cwd/home/SQLite and disabled providers. Both
owned processes were stopped after acknowledgement because the separate CLI shutdown defect
is being handled in another checkout.

`available()` means bytes ready now, not EOF. Explicit `--text` values, including empty/blank,
are authoritative and bypass stdin. A valueless `--text` errors. Otherwise an attached Java
console retains the existing metadata-only capture behavior. Redirected/piped/headless input
waits for EOF, including delayed or split writes. Empty EOF permits a no-text capture.

Read no more than 1 MiB plus one overflow byte; reject oversized input rather than saving a
partial capture. Decode strict UTF-8, rejecting malformed input. Read/decode/size failures must
occur before recording or acknowledgement. The byte acceptance cap is separate from the existing
canonical redaction and text-truncation policy, which still applies after a successful read.

Java may report no console when stdout is redirected even if stdin is a terminal. That case
follows the EOF rule; `--text=''` explicitly requests a no-text capture without reading stdin.
No terminal probing dependency, new input mode, provider behavior or shutdown change is included.

## Scope and verification

Own SbaCli's stdin path, shared CLI help text, dedicated unit/process stdin tests, operations
documentation and this plan. Do not touch application bootstrap or the other worker's exit tests.
Run actual slow producer, split Unicode, explicit text/open stdin, empty EOF, accepted limit,
oversized/malformed input and read failure against disposable SQLite. Assert canonical text and
no event rows/ack on failure; distinguish accepted byte length from canonical truncation.
Test console nonreading and byte/decoder boundaries directly, then run focused/full backend,
scoped Java formatting and diff checks. Root owns all Git and final integration/publication.

## Results and frozen handoff

The focused suite passed 57 tests: 11 new stdin unit cases, 10 real child-process/SQLite cases,
and the existing CLI dispatch/help contracts. The slow-producer test waits for actual application
startup, verifies no acknowledgement while stdin is empty, splits a four-byte UTF-8 character
across writes, and verifies no acknowledgement until EOF. SQLite then contains the exact text.
Explicit nonblank/empty/blank text acknowledges while its input pipe is held open. Empty EOF
preserves metadata-only capture. Exactly 1 MiB is accepted and then follows the existing 20,000
UTF-16-unit canonical text cap; oversize input is rejected without requiring EOF. Malformed UTF-8,
read failure and valueless `--text` each produce nonzero exit, no acknowledgement and zero events.

A separate actual pseudo-terminal launch, with no bytes or EOF supplied, acknowledged in
1.415 seconds and stored one metadata-only event. This exercises the Java console branch through
use, in addition to the direct nonreading unit test. Its owned process and temporary storage were
cleaned up. Successful subprocess cleanup in the automated tests remains compatible with the
separate context-shutdown repair without depending on it.

The full default backend suite completed with 860 reported tests: 823 passed, zero failures or
errors, and 37 expected skips (33 PostgreSQL cases without fixture configuration, two live-model
evaluations, one Elasticsearch integration case and one non-macOS Finder-unavailable case).
No optional live provider or new database service was started. The coordinator's exact-head CI
will run the mandatory PostgreSQL contracts. Scoped pinned Java formatting and `git diff --check`
passed. The IDE was open on another repository, so native offline Maven verification was used.

Six owned files are frozen: SbaCli, CliCommands, SbaCliStdinTest, CliIngestStdinProcessTest,
operations documentation and this plan. No bootstrap/lifecycle-test edits, shared build outputs,
Git/publication, production data, configuration, provider or Linear changes were made. Root owns
review, integration with the independent shutdown fix, Git finish and actual CI acceptance.
