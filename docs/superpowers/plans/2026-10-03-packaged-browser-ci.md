# Gate packaged browser journeys in CI

## Scope and contract

Extend the existing Ubuntu frontend job without removing backend, PostgreSQL, frontend unit,
format, lint or type gates. Run the full discovered Chromium suite against the current packaged
application, using the existing provider-disabled runtime and ownership-checked temporary SQLite
fixtures. Preserve the 8799 allowlist, one worker, zero retries, no existing-server reuse and the
180-second startup deadline. Prebuild outside that deadline. No credentials or deployment services
are added, and workflow token permissions are explicitly read-only.

Keep diagnostics limited to fake-fixture Playwright output with seven-day retention. The existing
workflow uses major-version action tags; the new `actions/upload-artifact@v7` tag follows the current
[official action documentation](https://github.com/actions/upload-artifact#usage), checked when
implementing this change. Hidden files remain excluded.

## Acceptance

- Validate workflow YAML; run actionlint if installed.
- Confirm discovery includes the full suite, then run the exact CI browser command locally once.
- Verify fixture cleanup and preserve every runtime/seed guard.
- Root reviews the isolated change and owns commit, publication and integration with newer main.
- Require the actual GitHub Ubuntu frontend job and its full packaged suite to pass before merge;
  local verification is preparation, not Linux acceptance.

## Verification results

Local validation completed:

- YAML parsed successfully. Every workflow `run` block passed `bash -n`; the backend job compared
  identical to the base, all five existing frontend commands remain, and token/artifact bounds were
  checked. `actionlint` was not installed. Whitespace validation passed.
- Discovery found 46 Chromium journeys in 14 files. The separate Maven frontend package succeeded
  in 10.7 seconds with cached dependencies. The exact workflow browser command then passed all 46
  journeys in 57.5 seconds (58.0 seconds command wall time), with one worker and no retries.
- The HTML report was generated. The owned database directory and project symlink were removed,
  the fixture listener stopped, and port 8799 was verified free. The protected port 8766 listener
  stayed unchanged. Its database was not discovered, so no live database identity/count claim is
  made. The application ran with the existing isolated environment and providers disabled.
- Only the CI workflow, frontend verification documentation and this plan changed. No fixture,
  runtime guard, application source or generated asset change was needed. No Git publication,
  deployment, credentials, live-state mutation or provider call was performed.

Codex hands the frozen local change to root for review and integration with newer main. The actual
Ubuntu Actions run, artifact upload and Linux full-suite result remain pending; root must obtain
that evidence before merge. Local macOS success is not a substitute for Linux acceptance.
