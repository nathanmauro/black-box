# Opt-in durable hook outbox

Status: implementation and disposable hook verification complete; coordinator review and integration remain.
Acceptance was frozen before implementation. Server idempotent ingestion is already present.

## Scope and safety contract

Implement only an opt-in local outbox for the existing hook: `SBA_CAPTURE_DURABLE=1`. Preserve
legacy Bash/jq normalization, semantic roles, subagent lineage, and the default direct capture path.
Hook failures remain soft (exit zero). Do not activate global hooks, alter canonical databases or
services, change Java/server behavior, or publish. The coordinator owns review, Git, integration,
and final actual hook-to-isolated-Java verification.

## Frozen acceptance

- Validate and sanitize in memory before disk. Drop metadata.rawHook, preserve normalized useful
  text/tools/lineage, redact built-in provider credentials, bearer tokens, complete or unterminated
  private keys, secret assignments, and explicit secret JSON keys recursively. Reject suspicious
  identity/path fields instead of rewriting identity. Bound input, nesting, scanning, and output;
  use Python 3.9 standard library only. Document lossy best-effort and custom-server-rule limits.
- Queue immutable UUID, sanitized event bytes, and one observedAt; keep sanitizer version outside
  the event. Use only numeric loopback HTTP origins with explicit ports, no auth/path/query/fragment,
  no DNS/proxy/redirect, and no rewriting of an explicit URL. Durable default is 127.0.0.1:8766.
  Partition by normalized origin and never retarget queued rows.
- Keep an owner-only directory and files (0700/0600), verify owners and types, reject symlinks,
  hard-linked files, and unsafe existing permissions, and use no-follow file opens. Check database,
  journal, and sender lock. Use SQLite rollback journal, full synchronization, short transactions,
  and a nonblocking sender flock. Enqueue remains possible while another process sends HTTP.
- Bound sanitized HTTP payload to 1 MiB, logical queued bytes to 64 MiB, and rows to 10,000 in one
  quota transaction. Never evict an unacknowledged row. Failure diagnostics must be fixed and
  contain no event content. Hook enqueue plus drain has an approximately three-second budget.
- Provide enqueue/status/drain commands and explicit drain event/time limits. There is no scheduler
  or detached child. Delete only after HTTP 200 with matching canonical capture UUID, valid canonical
  event/session UUIDs, and a boolean replayed acknowledgement. Retain on failed/ambiguous delivery.
  Authentication/unsupported-route responses pause the origin; permanent payload errors retain a
  rejected row. Document attempt ordering, retained gaps, and explicit unpause behavior.
- Exercise the actual hook against local fake HTTP: outage/recovery, immutable retry identity/bytes/
  timestamp, committed-response-loss deduplication, interrupted processes around acknowledgement,
  concurrent enqueue/sender/quota, origin isolation, bad acknowledgements, unsupported server,
  privacy in DB/journal/logs, unsafe filesystem, quota/corruption, and bounded slow-server behavior.
  Add golden sanitizer fixtures grounded in existing Java rules plus nested secrets/truncation.

## Verification plan

1. Existing hook smoke test and actual stdlib/filesystem capability preflight.
2. Focused sanitizer/storage tests, then subprocess/real HTTP/process interruption scenarios.
3. Existing legacy hook normalization/lineage tests and durable integration tests.
4. `git diff --check`, documented evidence and known limits, fresh independent review.

## Explicit limits

Fail-soft queuing can lose a new capture when sanitization, local permissions, disk, or quota prevent
acceptance; it must never discard an already queued row to make room. Sanitization reduces known
secret exposure but cannot recognize every secret or mirror custom server rules. Retrying retained
captures requires another opted-in hook or an explicit drain. Remote/authenticated endpoints and
retargeting are not supported. A future queue administration/forget flow needs its own authority.

## Results

- Preflight confirmed Python 3.9.6, SQLite 3.51.0, owner-only filesystem creation, `O_NOFOLLOW`,
  directory-relative opens, POSIX flock, and real-time signal timers in this worker context. The
  coordinator owns Git integration; this worker made no Git, live service, hook registration, or
  canonical database changes.
- Before implementation, the existing actual hook returned zero during an outage and created no
  queue. The new outage assertion failed on that absence, establishing the missing behavior. The
  original legacy hook smoke passed before changes.
- `scripts/test-agent-hook.sh` now passes the existing 18 normalization/lineage checks, two fail-soft
  checks, and 32 durable tests. `python3 scripts/hooks/test_capture_outbox.py` is the focused command.
  The durable suite uses real subprocesses and local HTTP fixtures, not production test hooks.
- Evidence includes committed-response-loss retry with unchanged UUID/event bytes/observedAt and one
  fake-server event; killed senders before, during, and after acknowledgement; a real SQLite writer
  blocking local acknowledgement deletion; concurrent enqueue during a held sender; atomic row/byte
  quotas; origin isolation; invalid acknowledgements; paused old servers; retained rejection gaps;
  corrupt/full SQLite; symlink, hard-link, FIFO, mode and ownership refusal; sanitized DB/journal/log
  bytes; and Java-derived plus nested/truncated sanitizer fixtures.
- Independent review reproduced three material issues before correction: a large raw here-string
  reached a regular temporary stdin file, a recognized credential embedded in a secret JSON member
  name survived, and held-open stdin exceeded the intended deadline. New tests were red for all
  three and green after correction. Durable normalization now uses pipes throughout, every retained
  member name is sanitized, and a foreground supervisor bounds the whole opted-in hook and cleans
  up only its own subprocess group.
- Actual-hook timing tests cover held-open input and 900 KiB normalization followed by a slow server;
  each returns zero within the approximately three-second budget (test ceiling 4.5 seconds). Real
  SIGTERM/SIGINT interruptions return zero, preserve the already accepted row, and later retry to one
  acknowledged fake-server event. The legacy path keeps its prior behavior.
- Documentation describes loss before acceptance, unknown-secret/custom-rule gaps, logical versus
  physical storage limits, origin partitions, rejected ordering gaps, explicit pause recovery, and
  the lack of background retry.
- Coordinator verification exercised the real hook against a copied candidate Java server and
  disposable SQLite database. With the endpoint unavailable, the hook queued a sanitized event.
  A loopback fault fixture forwarded the queued request to the real server, then discarded the
  committed response. The queue retained identical capture ID, event bytes, and observed time.
  After restarting the real Java server on the same temporary database, the retry returned the
  same event/session IDs with `replayed=true`; the queue emptied and canonical counts remained
  exactly one event, one session event, and one receipt. The recorded event was also read back
  through HTTP. External providers were disabled and all owned fixture processes were stopped.
  Fresh independent review accepted the final privacy/deadline corrections.

## Handoff

Codex worker changed the owned hook/outbox, tests/fixtures, this plan, and the durable-capture guide
with a narrow agent-integration link. The coordinator completed real-server integration proof and
owns exact-path Git integration and any deployment. No global hook activation is included.
