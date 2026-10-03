# Safe CLI help before application startup

## Reproduced behavior

An isolated launch of current application `main` with `--help` started Spring, initialized a
SQLite schema, and served HTTP instead of printing help. `-h` and `help` failed as unknown
commands. `ingest --help` created a session and a `ManualCapture`; it also consumed piped text.
The audit used temporary homes, cwd, databases, disabled providers, and owned ephemeral ports.

## Contract and scope

Add a dependency-free command/help definition in the platform module's public API. Application
bootstrap handles help before constructing Spring; the CLI runner reuses the same definition.
Supported discovery is `--help`, `-h`, `help`, `help <command>`, and a known command with an exact
`--help` or `-h` flag. Configuration options do not cause help to start the application.

Unknown or retired leading commands remain errors even when followed by a help flag. An unknown
`help <command>` is also an error; neither route starts Spring. Do not interpret literal option
values (`--text=--help`, `--q=--help`) or the positional query `search help` as help requests.
Normal commands and no-command server startup keep their existing behavior.

Keep help text limited to implemented commands/options. Do not change capture, read APIs, auth,
provider behavior, Spring configuration, or the general command parser.

## Verification

- First run real process regression before changing production code.
- Help processes must exit successfully with stdin held open, a reserved HTTP port, and invalid
  database configuration. Also test valid disposable SQLite configuration and require no DB file.
  No Spring banner/startup, provider call, ingestion acknowledgement, or HTTP listener may appear.
- Cover every registered command, help aliases, unknown/retired errors, and literal data. Verify
  command names and CLI dispatch stay consistent using mocks, without calling real services.
- Run existing bootstrap and module tests, full backend checks, and scoped Palantir formatting.
- Use only disposable fixtures; close/reap processes and leave no live database/service changes.

The coordinator owns all Git integration, publication, and issue reconciliation.

## Results

- Before the change, all 15 actual help-process cases failed; three unknown/retired-command
  rejection controls passed. There were no test errors or skips.
- After the change, 43 targeted tests passed with no failures/errors/skips: 18 subprocess cases,
  command/help routing, all seven normal command dispatches, literal-data controls, the existing
  retired-runner guard, and module boundaries.
- Full backend suite: 824 tests, zero failures/errors, 37 environment-dependent skips
  (33 opt-in PostgreSQL cases and four existing optional checks). No PostgreSQL fixture or real
  provider was started for this dependency-free bootstrap change.
- Scoped Palantir formatting and `git diff --check` passed. Independent coordinator review found
  no functional issues in routing, compatibility, or process isolation.
- The actual subprocess tests leave stdin open with fixture data, reserve the configured HTTP
  port, cover poisoned driver and valid SQLite configurations, and require clean exit/no database
  file/no Spring or server logs. Every owned subprocess is reaped; JUnit removes private fixtures.

Normal commands retain their existing persistence/provider behavior. Their dispatch is verified
with mocks; this slice does not invoke production capture, summary/backfill, cloud deployment, or
private configuration. The coordinator owns the reviewed eight-path diff and Git/PR finishing.
