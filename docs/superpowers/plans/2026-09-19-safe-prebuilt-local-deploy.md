# Safe local deployment

Status: independently reviewed, integrated and exercised in a successful existing local installation.

## Safety contract

The installed launchd plist owns the service label, Java command, working directory, and JAR
path. Deployment must preserve its bytes. An isolated, verified Spring Boot JAR may be supplied
with `--prebuilt-jar`; that mode must never run Maven. Rebuild mode must build exactly the artifact
that the installed service starts.

Before stopping a healthy service, retain a validated, hashed recovery JAR outside Maven's target
directory. Lock the deployment, reject path aliases and ambiguous installation identity, and
confirm both the launchd PID and every JAR consumer have stopped before replacement. Install
atomically. A failed build, install, bootstrap, readiness check, or handled interruption must
restore and verify the old binary, returning failure even after successful rollback. Retain
recovery evidence when rollback cannot be verified.

Binary rollback does not undo database migrations or application writes. Deployment requires
backward-compatible migrations and an independently maintained database backup/recovery plan.
No live service, launchd configuration, or database will be mutated by this implementation task.

## Verification

Use the actual entry point with disposable Boot archives, a plist, and fake service/process/build
commands. Reproduce the existing clean-build deletion and ignored-stop failures before changing
behavior. Cover prebuilt success/no Maven, invalid archives, mismatched installation identity,
stop failure, lingering JVM, build failure after deletion, install/bootstrap/readiness failures,
interruption, rollback failure, deployment locking, and unchanged plist bytes.

The coordinator owns independent review, final integration, packaging, and any real deployment.

## Observed implementation results

The original entry point reproduced all three frozen failure cases: failed Maven deleted the
old JAR without recovery; bootout failure still reached Maven; a lingering JVM still reached
Maven. Evidence: `/tmp/blackbox-safe-deploy-red.log` (local test output, not a product artifact).

The replacement delegates to a standard-library Python helper so the plist, ZIP archive,
process identity, hashing, advisory lock, and rollback state are parsed explicitly. The initial
13-scenario script suite passed, including prebuilt/no Maven and each requested rollback path.
The expanded suite covers identity, lock, port ownership, invalid archives, loaded runtime
configuration, HTTP redirects/wrong JSON, and interruption after candidate startup. No real launchd service, live configuration, or database has been changed.

Fresh independent review caught a realistic missing-file `lsof` error that the first fake omitted.
After reproducing that exact failure, restoration now skips the file-descriptor query only when
Maven has removed the JAR; recorded-PID and complete `-jar` process checks remain mandatory.
Readiness also requires HTTP 200 and canonical storage counts. Loaded launchd program, argument,
working-directory, and environment checks guard edited-but-not-reloaded plists.

Read-only preflight of the actual installed service confirmed the loaded definition, healthy
response, and sole JAR consumer. This invoked no deployment, stop, bootstrap, or state-directory
creation. Real stop/install/restart and database compatibility verification remain the
coordinator's integration step. Retained binary backups do not imply database rollback.

Final implementation verification: **26 scripted use tests passed**, including interruption
both after Maven removed the old artifact and after a candidate process started. Python compile,
Bash syntax, and `git diff --check` passed. The final test output is retained locally at
`/tmp/blackbox-safe-deploy-final-reviewed.log`; the additional realistic missing-JAR red
reproduction is `/tmp/blackbox-safe-deploy-missing-jar-red.log`. No commit or publication was
performed by the implementation worker. The coordinator owns integration and real deployment.

## Coordinator closure

The coordinator reran all 26 scenarios, verified copied old → candidate → old binary compatibility
against a disposable SQLite database, retained a consistent private online database backup, and
deployed a clean prebuilt candidate through this entry point. The previous process stopped before
replacement; the new launchd PID owned the configured port and reported healthy canonical storage.
Installed plist and summary-wrapper fingerprints, working directory and database inode remained
unchanged. Status, session, recall, exact-event and UI routes passed; the actual browser workflow
and legitimate idempotent handoff replay also passed. Previous binary and recovery metadata remain
retained. No real failure was induced in production; failure/rollback paths were tested with the
disposable scenarios. This closes the previously pending real deployment step.
