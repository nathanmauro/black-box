# NAT-311: qualify a second fixed Java repository fixture

## Safety and scope

Add only the reviewed summary-export development fixture to the offline repository qualifier.
The original structured-redaction fixture remains the default. Accept exactly two named fixtures,
never arbitrary revisions, source paths or candidate code. Baseline is
`9933ade549c37af5d784edff650f74705d55fa83`; reference is its child
`16ce9f343706d1818f0b73e67043e6e84a1104e0`. Replace only the reviewed
SummaryExportService source in a private baseline copy. Preserve all existing archive, source,
build, grader, environment, offline dependency, timeout and cleanup protections.

An identical hidden grader uses the pre-existing controller and real export service through
standalone MockMvc, an in-memory RecordingCatalog and controlled ResourceLoader. No Boot service,
network listener, database, provider or live notes. Every sentinel and symlink target is beneath one
private temporary fixture directory. Require POSIX modes, symlinks, hard links, stable file keys and
atomic sibling replacement; unavailable capabilities are infrastructure failures, never skips.

## Behavioral acceptance

Seven exact named tests: baseline fails directory-symlink containment, destination-symlink containment
and hard-link alias preservation; reference passes all seven. Both versions must preserve normal and
repeated exports with explicitly assigned mode, configured root aliases, existing note bytes/mode
on template failure, and actual parent-directory traversal rejection. Root-alias returned absolute
paths may differ; compare resolved destinations and relative paths. No new writeStaged/publish seams,
creation-mode assumptions, adversarial rename claims or interrupted-write/crash-durability claims.

## Verification

Write and hash the identical grader first. Run unit/fake tests, then reviewed immutable snapshots with
Java 21 and the existing Maven cache in offline mode. Re-run the original fixture. Reject missing
cache/tooling, compile errors, skipped/zero/unexpected tests and altered source/build/grader files.
Verify Python 3.9, contamination isolation and owned-process/temp-tree cleanup. No dependency downloads,
installation, model calls or arbitrary candidate execution. Every result remains infrastructure_only,
not_cleared, zero model runs and zero accepted actions; existing NAT-7 gates are unchanged.

The worker owns only source/test/docs in this isolated managed checkout. Root owns all Git,
publication, integration and Linear. No other checkout or existing study protocol/inventory is edited.

## Observed qualification and handoff

The first real offline summary-export qualification reproduced exactly the three expected baseline
assertion failures and four preservation passes; the unchanged grader passed all seven on the
reference overlay. After moving every filesystem fixture explicitly under the private grading
`target/` directory, the same result passed again from deliberately contaminated caller/ancestor
Maven directories with inherited Maven/JVM options. Each snapshot build took approximately five
seconds in this cached local run. This is fixture qualification, not a difficulty measurement.

The original structured-redaction fixture also retained its three expected baseline failures and
six reference passes under the same contaminated invocation. Its manifest, task and grader bytes
remain unchanged. An empty-cache invocation returned `offline_dependency_unavailable`; no dependency
was downloaded. Verification checked that the qualifier's owned temporary trees disappeared after
both successful runs and the empty-cache failure, and removed its own contaminated caller/config/cache
directories. All spawned processes completed or were reaped by their owning tests.

Python 3.9.6 passed all 71 local benchmark unit/fake checks, including 44 qualifier checks covering
both selections. The shared checks reject changed pins/build bytes, additional reference paths,
links/special archive entries, invalid report inventories, skipped tests, nonbehavioral failures,
and altered selected manifest/grader inputs before or during Maven. Fake timeout execution verifies
owned process termination; selected-build failure tests verify private-tree cleanup. One initial
fake no-follow test assumed the grader was the first trusted file; the second fixture's sorted task
name came first. The test now targets the first trusted file explicitly, preserving its no-read
assertion without changing production behavior.

The new Java grader passed scoped offline Palantir formatting and final format checking. Diff and
exact owned-path checks complete the handoff. No application production source, existing benchmark
run path, study protocol or candidate inventory changed. No server, live notes/database, provider,
model continuation, dependency installation or Git mutation was performed. Root owns independent
review, integration with the protocol documentation, Git publication and Linear reconciliation.
The result remains `infrastructure_only` and `not_cleared`, with zero model runs and zero observed
accepted actions; all original NAT-7 thresholds and the development gate remain unchanged.

## Coordinator integration

Integrated the merged comparison protocol, preserving its source inventory and unchanged gates.
Recorded the scoped seven-check qualification in the inventory and a machine-neutral JSON report;
no broader filesystem or efficacy claim follows. Root independently reran both actual offline
fixtures and the full Python suite. Opus accepted the implementation and identified an unsupported
JVM-wide temporary-file claim. Removed the added Maven temp-directory property and its flag-only
test; the grader explicitly owns its files beneath grading target/, with outer timeout cleanup.
After that correction, all 69 Python checks (42 qualifier checks) passed on Python 3.9.6.
Both actual offline snapshot pairs passed their exact failure/preservation contracts again; the
committed report comes from this final summary-export run. The default Maven recipe and original
fixture/task/grader bytes remain unchanged.
