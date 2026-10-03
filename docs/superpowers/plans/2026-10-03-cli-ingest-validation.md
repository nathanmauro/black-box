# Reject ambiguous CLI captures before reading input

## Reproduced failure

At the existing CLI, `ingest 'Unique fixture note'` with closed stdin exits successfully and
stores an event with no text. `ingest --session intended-fixture --text='Unique fixture note'`
also succeeds, but the positional session value is discarded and a generated session is used.
The corresponding text/session searches return no match. Explicit empty `--source=`,
`--session=` and `--type=` each persist an empty canonical field, although the HTTP capture
endpoint rejects the equivalent request with a field-specific 400 and no event/session writes.
The control using `--session=intended-fixture --text='Unique fixture note'` captures and queries
successfully. These were actual subprocess/HTTP checks using private SQLite fixtures, disabled
providers, and current CLI sources; no live service or database was used.

## Contract and scope

Validate ingest arguments before reading stdin or calling the recorder. Reject unexpected
positionals and explain the existing `--name=value` syntax. A present known ingest option that
requires a value must not silently fall back to its default. Require nonblank source, client
session ID and event type when explicitly supplied, matching the existing capture contract.
Omitted options keep their defaults; explicit empty/whitespace text remains a valid metadata-only
capture. Optional field emptiness retains its existing normalization. Spring configuration
options remain accepted. No general parser rewrite, changes to other commands, HTTP contracts,
stored data, or provider configuration are included.

## Verification

- Regression tests exercise malformed arguments before any stdin access or recorder invocation.
- Actual isolated CLI processes keep stdin open for negative cases and must exit nonzero without
  acknowledgement or event/session/stream-position/receipt rows. Valid explicit/default captures
  must exit successfully and remain searchable.
- Existing help, delayed/split UTF-8 stdin, empty text, input limits, and clean-exit tests remain
  required controls. Run focused tests first, then the relevant backend suite and module checks.
- Apply the pinned Java formatter only to changed Java files and run a scoped diff check.

Root owns review, Git integration, publication and any live deployment. All fixture processes
and temporary databases must be cleaned up. Results will be recorded before source freeze.

## Results

The new unit regression reproduced 17 failures before the repair. After the guard was added,
all 98 focused CLI tests passed, including 20 new unit cases and 11 new actual-process cases.
Nine invalid subprocess invocations exited nonzero while stdin remained open, without a capture
acknowledgement or event, session, receipt, or stream-position rows. Two valid capture-to-query
controls verified canonical fields, defaults, explicit options, stored text and returned event IDs.
Existing tests covered empty/blank text, delayed and split UTF-8 input, size/decoding failures,
help routing and normal context shutdown.

Full `mvn test` passed: 903 tests, zero failures/errors, 38 environment-dependent skips, in
1 minute 52 seconds. PostgreSQL fixtures were not enabled for this CLI-only change (34 skipped
cases); four other optional/environment checks also skipped. The suite included module boundary
checks. Scoped Palantir formatting and its follow-up check passed, as did the diff whitespace check.
An independent read-only review found no remaining issue in the guard, fixture isolation,
compatibility or documentation.

Only CLI argument acceptance changed. Startup can still initialize the selected database before
the runner validates its arguments; the guarantee is rejection before stdin and canonical capture,
not a configuration-free or database-free invocation. No live database, provider, service,
configuration, Git history or publication was changed by the worker. All owned fixture processes
were reaped and temporary storage was removed. Source is ready for coordinator review, commit and
integration; no deployment is included.
