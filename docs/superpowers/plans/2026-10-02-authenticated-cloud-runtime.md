# Authenticated PostgreSQL cloud runtime

Status: implemented and verified after integration with board retirement and retained lineage (base `33bddb2`).

## Outcome and boundary

The cloud image starts only with an explicit PostgreSQL profile, PostgreSQL URL and enabled
browser/bearer authentication. The ordinary JAR and local SQLite defaults remain unchanged.
A disposable authenticated PostgreSQL server proves the current consumer APIs together:
idempotent capture, exact project recall, explicit replacement/history, full event retrieval,
MCP tool execution, bearer SSE heartbeat/event delivery and restart persistence.

This is the existing single-server deployment, not the proposed S3/OAuth/queue redesign. No cloud
resources, real credentials, host services, production databases or consumer configuration are changed.
The coordinator owns Git integration, PRs and merging.

## Reproduction

The baseline cloud Dockerfile sets `SBA_BIND_ADDRESS=0.0.0.0` and a SQLite URL, then invokes Java
directly. Replaying that exact JSON ENTRYPOINT with a temporary fake Java executable, those image
defaults, and no authentication variables printed `JAVA_STARTED` and returned 0. No service was
started. The image's `postgres-auth-v1` label did not enforce its configuration contract.

## Independently verifiable steps

1. Add an environment-only cloud entrypoint. Reject missing/incompatible profile, URL or auth
   inputs, insecure cookies and competing Java/Spring configuration channels. Never print secrets.
   Keep fixed JVM sizing and exec Java for container signal handling. Add the single required
   script to the deny-by-default Docker context and change only the cloud image's startup.
2. Exercise the executable through a fake Java process: default denial, each invalid setting,
   valid launch, argument/override denial, secret-safe errors and unchanged Java arguments.
3. Add an opt-in real PostgreSQL consumer test using only a random test schema, generated app
   credentials, loopback HTTP and disabled model services. Assert rejected requests make no writes;
   assert each consumer path and persistence after restart.
4. Supply a disposable PostgreSQL 16 service in CI and fail if either PostgreSQL contract class
   skips. Run executable guard tests, targeted integration tests, then the relevant full suite.
5. Update backend/readiness docs with exactly what the proof covers and what remains external.

## Acceptance limits

A direct loopback SSE heartbeat does not prove a managed proxy's buffering or idle timeout.
These tests do not establish phone use, OAuth connectors, migration, backup/restore, remote
latency or availability with the Mac asleep. The startup guard prevents configuration mistakes;
an operator who replaces the container entrypoint/image still controls the process.

## Results

- Baseline direct Java startup accepted the cloud image defaults without PostgreSQL or auth;
  the new executable rejects those defaults before Java starts.
- The nine executable guard tests pass, including missing/invalid inputs, secret-safe failures,
  command/configuration override denial, fixed non-secret JVM arguments, exec PID and exit behavior.
- Review identified Spring Boot's later Hikari binding as a separate connection override path.
  The guard rejects the entire competing Spring datasource namespace; regressions cover pool URLs,
  credentials, driver/datasource classes, JNDI/type selection and relaxed spelling variants. A
  real Spring Binder/Hikari test demonstrates the override without connecting a database.
  Equivalent JSON/config/profile environment spellings are also rejected. Startup pins packaged
  classpath configuration, excluding automatically discovered mounted files; real JSON postprocessor
  and config-data fixtures verify these channels and the retained PostgreSQL profile.
- An actual scratch-only Docker build with nested script and target decoys admitted exactly
  `scripts/cloud/cloud-entrypoint.sh` and `target/app.jar`. The explicit directory re-exclusions
  prevent Docker's ancestor allow-rules from admitting other files. No cloud image was built.
- All 52 cloud Python tests pass, using only offline processes and local fixture HTTP servers.
- Both PostgreSQL suites pass without skips: the existing backend contract and the authenticated
  consumer journey plus three actual configuration-binding fixtures. The journey includes MCP
  bounded discovery and source retrieval, capture, current/history recall, SSE heartbeat/event
  delivery, full payloads and idempotence after restart.
- Final `mvn -B test` with the disposable PostgreSQL fixture: 767 tests, zero failures/errors, four
  unrelated skips (observed on this base before board-retirement integration). Both PostgreSQL
  classes ran: 19 backend contracts and four authenticated-runtime/configuration tests, no skips.
- Scoped Palantir `spotless:apply` and `spotless:check`, shell syntax checks and `git diff --check`
  passed. A fresh read-only review found no remaining actionable guard/Docker/CI/verify issues.
- Integrated `mvn -q clean test` on the board-retired source: 585 tests, zero failures/errors,
  four unrelated skips. Both PostgreSQL classes ran without skips: 16 backend contracts and four
  authenticated-runtime/configuration tests. The clean build removed obsolete board test classes.
  All 52 cloud Python tests also passed on this integrated source.

The fixture creates and drops only its own randomly named schema and closes both application
contexts. The coordinator-owned disposable PostgreSQL server remains running. No production database,
cloud resources, real credentials or deployed service was changed. The next release step is CI
verification of the integrated source, followed by publishing the application change; actual hosting
and cutover remain separate work.
