# Durable hook capture (opt-in)

Set `SBA_CAPTURE_DURABLE=1` on the existing capture hook to queue sanitized events locally before
sending them to the [idempotent capture endpoint](idempotent-capture.md). Without that setting, the
hook keeps its existing direct `/api/events` behavior. Installing or updating these files does not
register hooks or turn durable capture on.

The durable path requires Bash, jq, and Python 3.9 or later with its standard `sqlite3` and POSIX
`fcntl` modules. It preserves the hook's field precedence, semantic roles, and subagent lineage.
A foreground supervisor gives the complete invocation approximately three seconds, including stdin
reading, normalization, queue acceptance, and HTTP delivery. It waits for its own child group and
stops that group on timeout or interruption; there is no background sender or scheduler. The hook
returns zero on ordinary failures and handled signals so capture cannot fail the host agent's turn.

## Enable and inspect

Use this environment on a registered capture command, or try a synthetic event manually:

```bash
printf '%s' '{"hook_event_name":"UserPromptSubmit","session_id":"durable-example","prompt":"Remember this synthetic example"}' |
  SBA_CAPTURE_DURABLE=1 scripts/hooks/sba-agent-hook.sh manual

python3 scripts/hooks/capture_outbox.py status
python3 scripts/hooks/capture_outbox.py drain --max-events 100 --max-seconds 10
```

The default destination is `http://127.0.0.1:8766`, and the queue directory is
`~/.blackbox/outbox`. Override them with `SBA_AGENTIC_URL` and `SBA_CAPTURE_OUTBOX_DIR`, or pass
`--url` and `--directory` to the Python commands. An explicit directory must be an absolute local
path with no symlink components. Existing queue directories must be owned by the current user with
mode `0700`; database, journal, and lock files must be regular, singly linked, user-owned `0600`
files. Unsafe files, ownership, and permissions are refused rather than repaired. Do not put the
queue on a shared or synchronized filesystem.

Only numeric loopback HTTP origins with an explicit port are accepted, such as
`http://127.0.0.1:8766` or `http://[::1]:8766`. `localhost`, HTTPS, credentials, a trailing slash,
other paths, query strings, fragments, and remote addresses are rejected. An explicit unsupported
or empty URL is never replaced with the default. Proxy environment variables and redirects are
ignored. A destination is a local routing choice, not authentication of the listening process.

Rows belong to the normalized origin at capture time. Changing the configured URL does not move
old rows; run `status` or `drain` with the original URL to inspect or retry that partition. The row
and byte quotas cover all origin partitions together.

`enqueue` also accepts a normalized event JSON object on stdin. It sanitizes and queues that event,
then attempts a bounded drain. All commands default to 20 attempted events and three seconds;
`--max-events` accepts 1–1000 and `--max-seconds` accepts a positive value up to 30. `status` prints
the selected origin, row count, logical bytes, oldest age in seconds, and category counts. It never
prints payload previews. `drain` prints the number acknowledged in that invocation. Exit zero is a
fail-soft convention, not proof of queue acceptance or delivery; inspect status and the recorder
when that distinction matters.

## Acceptance and retry behavior

Queue acceptance is a committed SQLite transaction using a rollback journal and full
synchronization. Each accepted row keeps one capture UUID, sanitized event byte sequence, and
observation timestamp. Retry does not re-sanitize, re-normalize, mint a new identity, or advance the
timestamp. The sanitizer version is stored separately from the event.

A nonblocking file lock permits one sender per queue directory. Enqueuers use short database
transactions and can commit while another process waits for HTTP. The sender posts only to
`/api/events/idempotent`. It deletes a row only after HTTP 200 with its matching capture UUID,
canonical event and session UUIDs, and a boolean `replayed` field. That acknowledgement confirms
canonical server persistence; optional indexing and model work are separate. If the server commits
and the response is lost, the same request can be retried and acknowledged with the original event.

| Result | Local action |
| --- | --- |
| Valid matching acknowledgement | Delete that row |
| Timeout, connection failure, interruption, 408, 429, 5xx, redirect, or malformed acknowledgement | Retain; stop this drain and retry later |
| 401, 403, 404, or 405 | Retain and pause delivery for this origin |
| 400, 409, 413, or 422 | Retain as rejected; later rows may be attempted |

Attempts follow insertion order within the selected origin. A retryable failure blocks later
attempts in that drain. Retained rejected rows form visible gaps and are skipped; their payload and
identity are never rewritten automatically. After resolving an authentication or unsupported-server
problem, explicitly resume the origin:

```bash
python3 scripts/hooks/capture_outbox.py drain --retry-paused --max-events 100 --max-seconds 10
```

There is no fallback to the legacy endpoint, automatic retargeting, queue eviction, or automatic
forget command. Rejected rows remain available for a separately reviewed recovery decision. A new
hook invocation retries eligible rows, and explicit `drain` supports recovery when no hooks arrive.
Neither mechanism guarantees delivery while the client remains idle.

## Privacy and limits

Raw hook input stays in process memory and pipes through Bash/jq normalization. The durable path
does not use raw-payload here-strings, which some Bash versions spill to temporary files. It removes
`metadata.rawHook` and sanitizes recognized event content before opening queue storage. Sanitization
covers supported provider-token patterns, bearer tokens, complete and unterminated private-key
blocks, secret assignments, and explicit secret JSON keys recursively, including secret-bearing
member names. Recognized suspicious identity or path fields are rejected instead of silently
changing session identity or routing.

This is lossy, best-effort sanitization. Strings are scanned up to 50,000 characters, the remaining
tail is dropped, and truncation is marked. Deep or excessively complex structures are rejected.
Unknown secrets may remain, benign text may be redacted, and redacted JSON key collisions can lose
fields. Custom server redaction rules are not available to this local sanitizer and cannot protect
the local queue before delivery. Queue privacy relies on normal local filesystem ownership; it is
not encryption or protection against another process already running as the same user. Host
clients' own input files, operating-system swap, and external logging are outside this hook's
storage boundary.

Input and sanitized HTTP payloads are bounded to 1 MiB. Acceptance stops at 10,000 rows or 64 MiB
of logical queued data; SQLite page/index/journal overhead is additional. Quota checks and insertion
share one transaction, and no unacknowledged row is evicted to make room. A new capture can be lost
if its input is unfinished, normalization exceeds the time budget, validation rejects it, Python or
jq is unavailable, or local permissions, contention, disk space, corruption, or quota prevent queue
acceptance. Already accepted rows survive interrupted sends and ambiguous acknowledgements.
Failure diagnostics contain fixed categories, never request bodies or raw exception messages.

## Verification

```bash
scripts/test-agent-hook.sh
python3 scripts/hooks/test_capture_outbox.py
```

The combined command includes the existing legacy normalization/lineage smoke and disposable
durable tests. Durable tests use temporary directories, real local HTTP servers, concurrent
processes, process interruption, SQLite storage inspection, and synthetic sanitizer fixtures.
They cover outage recovery, lost acknowledgements, stable retry bytes, origin separation, malformed
acknowledgements, quotas, unsafe files, corruption, partial responses, held-open input, and the shared
hook deadline. They do not activate installed client hooks or write to a running Black Box database.
