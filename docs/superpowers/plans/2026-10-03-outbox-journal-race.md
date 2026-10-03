# Revalidate rollback journals removed during concurrent commit

## Reproduction and safety contract

An actual six-process CLI enqueue fixture reproduced the reported seven-versus-six queue
failure: one child returned the never-fail zero exit with `unsafe_file` after 0.113 seconds,
while the sender still held its transport lock. The other captures persisted. This is lost
acceptance, not an assertion race or an exhausted invocation budget.

A deterministic real SQLite fixture opens the rollback journal while another connection owns
an UPDATE transaction, then commits that transaction before the guard calls `fstat`. SQLite
unlinks the journal: the same safe regular file descriptor now has zero links. The original
guard rejects it and the next capture is not accepted.

Only a read-only inspection of an otherwise safe rollback journal may recover from that
zero-link state. Close the old descriptor, then reopen and validate the current entry within
the same invocation deadline. A missing entry is normal. Any replacement must pass all existing
file guards. Database and sender-lock files, unsafe owner/mode/type, hardlinks, symlinks,
WAL/SHM rejection, capture identity/bytes, transport exclusion and supervisor limits retain
their existing contract. No live queue, installed hook, configuration, provider or Git changes.

## Verification plan

1. Add and run the real COMMIT-between-open-and-fstat regression before changing production.
2. Cover missing and safe replacement journals, unsafe replacements, DB/lock controls,
   bounded repeated disappearance and descriptor cleanup.
3. Strengthen the six-process fixture to assert silent success, exact capture identities and
   immutable bytes through one canonical fake-server delivery per capture.
4. Run focused before/after cases, full Python 3.9 hook/outbox and current-Python outbox suites,
   bounded repeated concurrency and diff checks. Freeze the four owned files for root review
   and Git/publication; root is the authorized finisher.

## Implementation and observed results

The guard retries only read-only rollback-journal inspection when the opened descriptor is
regular, user-owned, mode 0600 and now has zero links. It closes that descriptor before each
new open, including before checking the replacement's type/owner/mode/link count. Symlinks
still cannot be opened. A disappeared replacement is treated like any absent journal; repeated
disappearance uses the original queue deadline (a fixed 200 ms fallback for direct callers).
Database and sender-lock zero-link states still fail closed. Descriptor cleanup also runs if
`fstat` itself raises. No sanitizer, wire, database schema, queue quota or transport change.

The real SQLite regression failed before the fix with `unsafe_file` for both absent and safe
replacement entries. After the fix, its two captures retain distinct UUIDs, exact event bytes
and the committed bookkeeping update. The five new test methods also cover replacement
symlink/hardlink/mode/FIFO/directory/owner failures, unlinked DB/lock and unsafe-journal controls,
repeated disappearance exhausting the shared deadline, and descriptor cleanup. The existing
six-process test now requires silent enqueue success, exact seven client identities and UUIDs,
unchanged wire bytes, exactly seven fake-server receives/commits, and an empty queue after ack.

The first full run exposed a test setup error: prior in-process CLI tests install umask 077,
so `touch(mode=0644)` did not make the intended unsafe file. The fixture now explicitly sets
and verifies its owned file's mode. This correction required no production change.

Final local verification:

- Nine focused regression/security/concurrency cases passed on Python 3.9.6.
- Python 3.9.6 hook normalization, lineage and never-fail smoke passed, followed by all
  62 outbox tests in 31.174 seconds.
- Python 3.14.7 passed all 62 outbox tests in 32.034 seconds. It reports existing unclosed
  SQLite test-connection ResourceWarnings; those warnings do not concern the production
  descriptor path and remain outside this fix.
- Thirty full six-process fixture repetitions passed on each interpreter (6.827 and 6.988
  seconds), checking 420 accepted capture identities and byte-identical deliveries overall.
- Python 3.9 grammar checks and `git diff --check` passed. Independent read-only review found
  no remaining actionable issue. No remote/Linux CI result is claimed yet.

## Source handoff

Codex changed only the outbox helper, its tests, this plan and `docs/durable-capture.md` on
`codex/outbox-journal-race`. All queues, HTTP endpoints and child processes were disposable
fixtures; no live data, installed hooks, providers or configuration changed. Source is frozen
for the root coordinator's final review, commit, PR and Linux CI acceptance. No worker Git
mutation or publication was performed.
