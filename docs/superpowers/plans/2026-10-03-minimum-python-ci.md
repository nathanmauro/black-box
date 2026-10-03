# Exercise the supported minimum Python in CI

## Evidence and scope

The outbox and checkpoint evaluation document Python 3.9 support, but CI previously exercised
only the Ubuntu runner's default Python. Actual timestamp failures on Python 3.9 and then Python
3.12 demonstrated that one interpreter cannot establish compatibility at both boundaries.

Add one small `python-minimum` job to the existing workflow. Select Python 3.9 explicitly and
assert the running interpreter before executing the existing hook smoke (which already includes
the full outbox suite), benchmark contracts and checkpoint-evaluation contracts. Keep the modern
backend and frontend jobs unchanged. Use only disposable fixtures and fake loopback recorders;
no provider, live queue, database service, Maven build or browser is involved in this job.

The job inherits read-only repository permissions and existing workflow triggers/concurrency.
It has a five-minute timeout, ensures jq/OpenSSL are available, and adds no dependency cache,
artifact upload, credentials or separate trigger. Existing action conventions use versioned
major tags. The official [setup-python documentation](https://github.com/actions/setup-python/blob/main/README.md)
confirms `actions/setup-python@v7` and explicit version selection/PATH setup. The official
[Python build manifest](https://github.com/actions/python-versions/blob/main/versions-manifest.json)
includes Python 3.9 builds for Ubuntu 24.04 x64.

## Verification and completion contract

Run the exact interpreter assertion, hook smoke and both unittest-discovery commands locally with
Python 3.9 on PATH. Parse the workflow and verify the new job's command/action structure, shell
syntax, read-only permissions and unchanged existing jobs; use actionlint if available. Check the
scoped diff. Record local evidence without claiming Ubuntu Actions execution.

Only `.github/workflows/ci.yml`, the README verification paragraph and this plan are owned.
The coordinator owns final review, branch/commit/push/PR, actual Ubuntu run and merge. No live
service, configuration, provider, Linear or Git mutation is authorized for this worker.

## Local results and frozen handoff

The workflow's exact four run blocks were extracted from parsed YAML and executed together with
Python 3.9.6 selected on PATH. The interpreter assertion and tool checks passed, followed by the
actual hook normalization/lineage/fail-soft smoke, all 57 outbox tests (31.310 seconds), all 45
benchmark contracts (0.384 seconds), and all 20 checkpoint-evaluation tests (1.280 seconds).
All fixture processes completed; no real provider, queue or service was touched.

Ruby/Psych parsed the workflow, verified the new action/version/job structure and read-only
permissions, and compared the original backend/frontend jobs plus global workflow settings as
unchanged. Every run block passed `bash -n`; `git diff --check` passed. Actionlint was unavailable,
so no actionlint result is claimed. The new setup-python action and Ubuntu execution are not
locally simulated: an actual successful Ubuntu Actions run remains required before merge.

The three owned paths are frozen for coordinator review and Git/publication. No worker Git,
Linear or live configuration mutation occurred.
