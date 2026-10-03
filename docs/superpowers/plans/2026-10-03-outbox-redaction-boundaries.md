# Consume credential suffixes after repeated redaction markers

## Reproduction and contract

The actual enqueue CLI persisted a synthetic `token=[REDACTED]SYNTHETIC_REMAINDER`
value in a disposable SQLite outbox. The assignment scanner skipped any value beginning
with its redaction marker. Provider-token replacement could also introduce that prefix
while leaving attached credential text behind.

Treat the marker as one token, including its closing bracket, then consume any attached
suffix through the existing value boundary. Preserve adjacent evidence and idempotent
redaction. Only newly accepted captures change; retained queue bytes and capture IDs must
remain immutable for idempotent delivery. Do not change sanitizer-version compatibility,
register hooks, inspect a real queue, rewrite old rows, or contact providers.

## Verification

Add scanner boundary/idempotence cases and a real hook → disposable queue → dropped-response
retry journey. Inspect database and rollback-journal bytes, transport payloads and diagnostic
output for the synthetic suffix; verify retry sends identical bytes and capture ID. Run
targeted tests before/after, the full outbox suite and hook smoke, then diff checks.

## Observed results

The two added regression methods initially produced nine failures, including persisted
synthetic suffix bytes through the real hook. After the scanner change both pass, all
43 outbox tests pass, and the hook smoke passes (including its embedded outbox suite).
The dropped-response replay keeps identical serialized bytes and capture ID and produces
one committed fake-server event. Database and rollback-journal inspection contains no
synthetic marker suffix. No existing local queue, provider, registration or live service
was touched. The new behavior remains idempotent at whitespace and comma/brace/bracket
boundaries; ordinary non-assignment marker text is preserved.

## Independent review and matching server boundary

Review found that treating only the initial marker atomically still exposed a suffix after
a second marker. The strengthened two-test queue regression produced thirteen failures
before correction, including real hook storage after two provider tokens became markers.
The loop now consumes every complete marker before considering real closing delimiters.

The server shared the repeated-literal-marker boundary defect (its provider rule order
already masks the raw composite provider case). Before changing Java, three scalar cases,
two real HTTP/storage/read paths and two fake export/telemetry paths failed: seven failures
across 39 tests, zero errors. The same atomic-marker loop fixes that boundary while keeping
custom rules, disabled ingestion, scan bounds and independent export redaction intact.

The first full outbox run after review saw the existing six-process enqueue concurrency
case retain six rows instead of seven. This has no demonstrated connection to the scanner;
three focused repeats passed, followed by a full 43-test pass. No concurrency code or
timeouts were changed in this slice; the intermittent failure remains recorded for follow-up.

Final affected Java capture/redaction suite: 133 tests, zero failures/errors/skips. Scoped
Palantir formatting and diff checks passed. The final queue suite passes all 43 tests.
Neither server nor outbox rescans stored captures, and the installed app remains unchanged.
