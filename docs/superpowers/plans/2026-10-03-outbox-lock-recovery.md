# Recover short SQLite queue contention within the invocation budget

## Reproduction and contract

An initialized disposable queue held under a separate `BEGIN IMMEDIATE` for 0.55 seconds
caused actual CLI enqueue to exit zero after 0.302 seconds with `operation_deferred` and
zero rows. The invocation still had most of its three-second budget available. This is a
concrete acceptance failure; the earlier intermittent concurrent-enqueue failure is not
assumed to have the same cause.

Retry only SQLite BUSY/LOCKED statements, using the original absolute invocation deadline.
Keep SQLite internal waits short/nonblocking, revalidate private files before retries, and
preserve one capture ID and one insertion transaction. A busy commit retries the same commit;
any terminal failure rolls back or closes the partial connection. Never retry corruption,
permissions, unsafe files, quota rejection or other database errors. Preserve the supervisor,
transport flock, immutable replay bytes and never-fail hook exit policy. Python 3.9 remains
supported. Do not touch installed hooks, live queue/data/providers/configuration or Git.

## Verification plan

Reproduce actual CLI startup contention first, then test a short held startup lock,
post-startup enqueue and commit contention, exhausted deadlines and transaction cleanup,
non-busy errors, file guards during retry, and an actual supervised hook deadline with no
remaining child group. Run existing concurrent enqueue, quota, replay, kill/ack, permissions,
redaction and fake HTTP suites plus the legacy hook smoke. Record results and freeze the
four owned files for coordinator review/publication.

## Implementation and observed verification

The CLI passes its existing monotonic deadline into `Queue`. SQLite's internal busy timeout
is zero; each BUSY/LOCKED statement gets a bounded retry after at most 25 ms, using result
codes where available and exact SQLite lock messages on Python 3.9/3.10. Other errors are
not retried. Private-file guards run after each wait, before another statement attempt.
The guard regression releases the competing writer at the same time as making a lock file
unsafe, proving the retry cannot accept a row before checking the changed file.

The enqueue transaction retains its single UUID and INSERT across commit contention;
terminal errors roll back directly even when the deadline has expired. Constructor errors
close the connection and directory handle. The existing sender flock, quotas, sanitizer,
receipt handling, stored bytes and supervisor remain unchanged.

Eight new focused tests pass on Python 3.9.6. Measured actual commands against disposable
queues: a 0.55-second startup lock now accepts exactly one row in 0.566 seconds (exit zero,
no diagnostic); a sustained CLI lock with a 0.35-second budget exits in 0.397 seconds with
zero rows; a sustained lock through the real supervised hook exits zero in 3.066 seconds.
The dedicated supervisor test also observes the owned child group and confirms it no longer
exists on return. No real queue, provider, installed hook or configuration was touched.

Final verification: the complete 51-test queue suite passed in 26.565 seconds. The required
`scripts/test-agent-hook.sh` legacy normalization/lineage/never-fail smoke also passed, followed
by its bundled 51-test queue suite in 26.240 seconds. Those suites retain the existing six-worker
concurrent acceptance, quotas, crash/replay, security and transport coverage. `git diff --check`
passed. Source and tests are frozen for the coordinator's independent review and Git finish;
only the four authorized paths changed, with no worker commit or publication.

## Final autocommit review proof

The coordinator requested separate proof for drain bookkeeping, which uses autocommit rather
than enqueue's explicit transaction. An actual reader-held SQLite lock caused the UPDATE to
be attempted twice after release, while storing `attempts=1` exactly once and leaving no open
transaction. Holding the reader through the deadline left the complete original row unchanged
(`pending`, `attempts=0`), with no lingering transaction. No production defect was reproduced.

Two additional real-drain/fake-HTTP regressions cover both UPDATE and acknowledged DELETE,
released locks and exhausted deadlines. After exhaustion, another drain sends byte-identical
capture data, gets the existing fake-server receipt, and removes the row. Both new tests pass;
the full final 53-test suite passes in 26.620 seconds, and diff checks remain clean. Production
source is unchanged from the previously reviewed 51-test/hook-smoke result. The same four files
are refrozen for the coordinator's Git finish.
