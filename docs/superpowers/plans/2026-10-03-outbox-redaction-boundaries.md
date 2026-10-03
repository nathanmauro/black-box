# Consume credential suffixes after redaction markers

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
