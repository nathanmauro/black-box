# Close completed one-shot CLI contexts

## Evidence and scope

A disposable `sessions` subprocess printed its JSON in 1.389 seconds and remained alive more
than ten seconds later. Its thread dump showed a non-daemon Spring scheduling thread waiting
for the SSE heartbeat, with the JVM waiting for non-daemon threads. The bootstrap discarded the
application context returned after its synchronous CLI runner completed.

Close the returned Spring context after a recognized one-shot command completes. Keep normal
no-command HTTP startup running, early help routing unchanged, and command/startup failures
non-successful. Do not change command parsing, delayed-stdin behavior, API/capture semantics,
or global async shutdown policies.

Every explicit CLI operation is synchronous: reads, canonical ingest, explicit summaries, and
embedding backfill finish their direct work before the runner returns. Optional terminal-event
summaries and enabled judgments run on daemon workers. Normal context shutdown may cancel
that work; canonical capture success does not guarantee those optional jobs completed. Use an
explicit `summarize` or `summarize-missing` command to wait for the summary operation. Preserve
that distinction in public docs and verification rather than adding a new async lifecycle.

## Verification

- Reproduce a successful command's non-termination before the change with a bounded subprocess.
- Run actual `sessions`, `search`, `doctor`, and embeddings dry-run against private SQLite with
  providers disabled; require JSON and successful natural exit. Verify command errors exit nonzero.
- Capture an event and prove its committed row remains after process/context close. Run explicit
  summary commands using local fallback and prove results persist before successful exit.
- Use an isolated blocking fake summary for a terminal capture, proving canonical retention even
  when optional work is pending at shutdown. No real provider or private transcript access.
- Verify no-command server mode remains alive, serves its fixture API, and shuts down gracefully.
- Run existing command/help/module regressions, backend checks, scoped formatting and diff checks.

Only private temporary homes/databases and owned ephemeral processes/ports are used. The
coordinator owns all Git integration, publication, and issue reconciliation.

## Results

The actual child-process capture regression failed before the fix because the command had not
exited after ten seconds. After the fix, all ten lifecycle process cases passed: four read commands,
canonical capture, a command failure, both explicit summary commands, a terminal capture with a
blocked fake optional summary, and the running HTTP service with graceful shutdown. Every child
uses a private home/cwd/SQLite database, cleared environment, disabled providers, and an ephemeral
loopback port where needed; all owned child processes are reaped.

The focused CLI/help/module suite passed 53 tests with no failures, errors, or skips. Full `mvn test`
passed 849 tests with no failures or errors and 37 existing optional-environment skips. Scoped
Palantir formatting/check and `git diff --check` passed. No live service, provider, database, or
configuration was changed; the worker performed no Git writes or publication. PostgreSQL-specific optional suites were
not enabled; this bootstrap change is verified through isolated SQLite subprocesses.

Optional asynchronous terminal summaries/judgments may still be cancelled on one-shot shutdown;
that completion is deliberately not part of canonical capture acknowledgement. Delayed piped stdin
is a separate selected fix and is not changed here. The coordinator owns independent review,
commit, integration, publication, and deployment.
