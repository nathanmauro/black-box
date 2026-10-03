# Acknowledge captures after canonical commit

Black Box persists each captured event in its canonical relational store, then publishes
optional in-process notifications (search indexing, live refresh, session finalization).
When an optional notification listener throws after the canonical write has committed, an
ordinary capture currently reports failure even though the event is stored, so a client that
retries creates a duplicate. Reproduce and repair this at the event ingestion service: a
committed capture must be acknowledged with its persisted identity, and for a terminal capture
a failed recorded-event notification must not prevent the separate session-stop notification
from being attempted.

Preserve the existing acknowledgement fields and the append-only behavior of ordinary captures
(a repeated ordinary capture still records another event). Request validation errors and
failed or rolled-back database writes must still reject, leave no session or event rows, and
publish nothing. Keep SQLite as the default and the store's transaction boundary unchanged.
Do not swallow persistence errors, add an idempotency or retry protocol, change response
shapes, or change build configuration.

Use the repository's existing Java 21 and Maven patterns. Work only in the supplied source
snapshot; do not use external history, remote services, models, live databases, credentials,
or dependency downloads. This is a familiar development task, not a held-out evaluation or
evidence of continuation usefulness.
