# Isolate the Playwright application runtime

Status: implemented and verified in the isolated checkout; coordinator review/integration pending.

## Reproduction and scope

The actual pre-change `webServer.command` was executed with a disposable checkout, fake Maven/Java,
and a decoy `application.yml`. Java observed the repository working directory, a relative jar with
no configuration/profile pins, inherited `SPRING_APPLICATION_JSON` and `JAVA_TOOL_OPTIONS` targeting
throwaway decoy databases, alongside the intended `SBA_DATASOURCE_URL`. The fake Java exited 23 and
the existing ownership trap cleaned its fixture directory. No real datasource or provider was used.
This proves the runtime boundary admits higher-priority configuration; it does not claim the fake
Java applied Spring precedence.

## Contract

Keep the strict port 8799/loopback URL guard, exclusive private temp directory, exact SQLite path,
ownership token, refusal of existing database/sidecars and token-based cleanup. Keep Maven in the
checkout with its normal build environment. Change only the application launch: use a fresh explicit
environment, owned temp cwd/home, absolute jar, packaged application configuration and default
profile. Disable all model/embedding/judge and Elasticsearch calls. Preserve the fake-editor command,
allowlist, timeout, output/sentinel paths and ownership metadata. The subshell must exec Java so the
outer trap terminates and waits for that process before cleanup. Do not touch rendering or live state.

## Verification

Exercise the actual command with fake Java to observe argv/cwd/environment and cleanup on success,
Java failure, build failure, termination, pre-existing storage and invalid listener overrides. Then
run the existing frontend checks and a meaningful packaged E2E journey under disposable hostile
configuration, verifying intended SQLite writes, unchanged decoy state and cleanup. No installation,
production service, provider call or Git publication belongs to this implementation lane.


## Results

- `npm run check` passed: no errors; 70 existing Solid warnings remain. Full frontend unit suite:
  624 tests across 57 files passed. Focused runtime/storage suite: 13 passed.
- The actual server command with fake Java verified its exact argv/cwd/environment and successful,
  failed, and interrupted cleanup. The pre-change reproduction admitted both hostile Spring JSON
  and JVM overrides; the new launch excludes those settings and inherited credentials/export flags.
- Packaged E2E ran with inherited `SPRING_APPLICATION_JSON`, config-location/additional-location,
  PostgreSQL profile selection, datasource override and all three JVM option variables targeting
  a disposable decoy SQLite database/config and alternate fixture port. The real app started on
  8799. `runtime-isolation.spec.ts` captured an event, read that exact ID/text from the intended
  SQLite database using a read-only connection, and followed Stream into the exact Browse event.
  The existing fake-editor journey also passed, including literal injection-shaped filenames and
  blocked out-of-project paths. Both tests passed in one run; Maven packaging/frontend build passed.
- Decoy database SHA-256 stayed identical. The owned run directory and project fixture were removed,
  and port 8799 had no listener afterward. The existing protected-runtime check observed an unchanged
  8766 listener; it did not discover the production DB, so its DB identity/row count were unavailable.
- `git diff --check` passed. No rendering/source bundle changes, live service changes, provider calls,
  or Git publication were made. Generated frontend assets were unchanged after packaging.

## Boundary and review notes

The shared [server command](../../../frontend/src/e2e/e2eServer.mjs) is used directly by Playwright
and the fake-runtime tests, so tests observe the executable launch rather than a duplicate config.
[The packaged regression](../../../frontend/tests/e2e/runtime-isolation.spec.ts) checks actual
persistence as well as UI use. The existing URL/storage/token protections and global teardown order
are preserved. Maven intentionally retains the caller's ordinary build environment; this change
isolates the application runtime, not the trusted toolchain or the parent Playwright process.
The protected-runtime snapshot is still taken after server startup; it is not the safety boundary.
