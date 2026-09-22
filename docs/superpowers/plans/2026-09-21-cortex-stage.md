# Cortex Stage Draft Plan

Status: integrating the cortex draft against current main for review.

Integration keeps the upstream idempotent-capture schema and compact-search contracts while
adding judgment storage and stream replay. The original draft is preserved separately.

Scope:

- Add a new `judgment` bounded context for off-by-default Jev judgments at ingest.
- Persist one judgment row per event in a folded beat.
- Add additive stream v2 payload fields and replay cursors.
- Document the cortex stage and constellate orbit consumption path.

Verification target:

- `mvn -q test`
- `mvn -q -DskipTests package`
- `git diff --check`

Safety contract:

- Do not touch the live LaunchAgent, live jar, or live database.
- Do not call `api.typesafe.ai` from tests.
- Keep `sba.judge.enabled=false` as the default so no scheduler, executor, rows, or HTTP client are
  active unless explicitly enabled.
