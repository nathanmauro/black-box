# Qualify one pinned Java repository continuation fixture offline

## Scope and safety

Add a separate development-only qualifier without changing existing benchmark/evaluation run paths
or verdicts. The known public structured-redaction repair is infrastructure evidence, not a held-out
or difficult task and not a memory efficacy result. Only the reviewed baseline and reference commit
IDs compiled into the qualifier may run; never accept arbitrary revisions or model candidate code
for host execution. Coordinator owns all Git/publication and confirms public repository ownership.

The baseline is 5d76086eeb0d423207e0f5560b3ae1aa1f9bebc8; the reference is
d833fa96942a558cc7bc453b504656a2df41148f. Verify actual commit objects, parent relationship, source
and build hashes, and the exact allowed changed paths before staging or executing. Both immutable
objects exist locally and have Nathan Mauro as author. No fetch/download/authentication is required.

Export only tracked, allowlisted baseline build/source resources into a private new temporary
workspace without Git metadata, post-fix docs/history, the reference patch or private grading tests.
Create separate private grading directories from that baseline, copy the fixed trusted regression
suite into them, and apply only the reference production-source change to the reference grading copy.
Do not make hidden tests or reference code available in the worker-facing export.

Use a fixed Java 21 / Maven offline test recipe with trusted isolated settings and sanitized process
environment. No Docker, credential/auth file reads, provider calls, live service/database access,
repository edits, or dependency downloads. This native execution is for reviewed authored revisions
only; temporary directories and environment controls are not a sandbox for adversarial submissions.

## Qualification contract

The baseline must compile and run every expected test, preserving ordinary behavior while failing
only named behavioral regression assertions. The reference must pass the identical complete suite.
Compile/dependency/tooling failures, timeouts, absent/zero/skipped/duplicate/unexpected test results,
changed source/build/grader hashes, and reference changes outside the exact allowlist fail closed as
infrastructure/qualification errors. Verify input hashes before and after each run. Kill owned process
groups on timeout/interruption; retain only an explicitly selected report, otherwise clean temp data.

Reports always say infrastructure_only and usefulness_gate=not_cleared. Keep the original gate:
20 adjudicated historical candidates; five resumed tasks; same-model/source-window ordinary latest-
handoff/search comparator; at least 70% useful supported suggestions; below 10% stale/duplicate;
and three observed accepted actions the comparator missed. Also preserve the existing five-task
1–4-pass development difficulty gate. No model outcome, accepted action or stronger result is invented.

## Verification

Unit/fake subprocess tests first: pins/hashes, extraction allowlist and path/symlink safety, grading
classification, missing dependencies, timeout cleanup, exact test inventory, output contract, dry-run
non-mutation and unchanged old verdicts. Then execute the actual immutable baseline/reference with
the trusted fixed Java suite using already-cached Maven dependencies. Missing prerequisites are an
explicit failure, never permission to download. Record commands, measured outcomes and limitations.

## Verification evidence

Coordinator verified the repository is PUBLIC under nathanmauro/black-box and PR66 merged the reviewed
source into public main. The qualifier itself stays offline and checks the already-present immutable
objects; it does not read authentication or call GitHub.

Initial unit/fake run: 14 tests passed, including actual timeout termination of an authored child.
Actual native execution used Java 21 and Maven 3.9 with offline cached dependencies: baseline ran all
six tests, failed exactly the three named structured-redaction assertions, and passed the three
preservation checks (4.608 s). The reference ran and passed all six (4.730 s). The report remained
infrastructure_only/not_cleared with zero model runs and accepted actions. Private fixture trees were
cleaned; the explicitly requested report contains no raw build logs or workspace source.

## Review hardening

Fresh review found two gaps before final acceptance: Maven's version preflight could discover caller
or ancestor `.mvn` settings, and a trusted grader file was reread after its digest check. All Maven
invocations now use owned working/base directories with empty `.mvn`, disabled startup rc files,
offline mode and isolated settings. Staging uses captured digest-validated task/grader bytes and
checks the complete expected inventory, including the POM and production source, before launch.

Regression coverage executes an authored fake Maven launcher from a contaminated caller/ancestor,
refuses a grader mutation after the initial load before any Maven command, and refuses unexpected
staged input before a build. Trusted files are opened without following symbolic links and must be regular files before any read;
a fake external link and FIFO regression covers this guard. The complete benchmark unit/fake suite
passes all 45 tests.

## Final verification and handoff

Final controlled native run: the actual Java 21 / Maven 3.9 qualifier ran from owned caller and
ancestor directories containing invalid `.mvn/jvm.config` and test-skipping `.mvn/maven.config`,
with invalid inherited JVM/Maven options. Baseline ran six tests: three exact expected behavioral
failures and three preservation passes (4.799 s). Reference ran six and passed all six (4.716 s).
An actual empty-cache invocation exited 2 with offline_dependency_unavailable and did not classify
it as behavioral evidence. Owned source/build/temp trees were absent afterward; the explicitly
requested JSON report was owner-only. No provider, server, database, Docker or model was invoked.

Commands checked: the documented plan/verify CLI, all benchmark Python tests (45 passed), existing
evaluation Python tests (20 passed), pinned Palantir check scoped to RepositoryFixtureContractTest,
and git diff --check. The evaluation suite needed scoped permission for its disposable loopback
fake servers; no application service was used. The native fixed-fixture proof is opt-in and needs
already-present commit objects plus cached dependencies; ordinary CI discovers the new unit tests.

Codex worker /root/continuity_backend leaves seven owned source/fixture/document paths uncommitted
on codex/repository-evaluation-fixture for the coordinator's review and Git publication. Existing
benchmark run paths and verdicts are unchanged. This published development repair is familiar and
not held out; no task-difficulty or continuation-usefulness result follows. NAT-7 remains not_cleared.
No live process or database was modified, and no temporary test process was left running.
