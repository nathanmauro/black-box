# Keep durable capture working under concurrent outbox writers

The opt-in capture outbox in `scripts/hooks/capture_outbox.py` stores sanitized events in a
private SQLite queue that several hook processes and a sender may use at the same time. It
checks its private files before using them. Under concurrent use, a capture can be rejected even
though another writer only completed an ordinary transaction, so the capture is lost.
Reproduce and repair this so concurrent writers do not cause valid captures to be refused.

Preserve the existing file-safety guarantees: links, wrong modes or owners, special files and
replaced or removed queue files must still be refused without following them or changing data
outside the queue directory. Keep the existing shared deadline semantics, close every descriptor
the outbox opens, keep enqueued bytes and queue bookkeeping exact, and leave the public queue
interfaces and stored format unchanged. Do not weaken checks, disable SQLite's rollback journal,
add dependencies, or change delivery behavior.

Use the repository's existing Python 3.9-compatible standard-library patterns. Work only in the
supplied source snapshot; do not use external history, remote services, models, live databases,
credentials, or network access. This is a familiar development task, not a held-out evaluation or
evidence of continuation usefulness.
