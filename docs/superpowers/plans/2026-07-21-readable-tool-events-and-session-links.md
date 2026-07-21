# Readable Tool Events And Session Links

**Goal:** Make Activity Stream tool events readable, provide an explicit and reliable path into the owning Browse transcript, and retire the duplicate faceted Find entry points now covered by Stream.

## Scope

- Keep Stream rows as inline expand/collapse controls.
- Parse stored JSON payloads into command/script, working directory, status, duration, output, and remaining argument sections without losing unknown fields.
- Add a visible `View session` action that opens Activity Browse with both the owning session and exact event selected.
- Hydrate a requested session directly so an exact link still works when that session falls outside the recent-session rail limit; preserve project scoping when doing so.
- Remove Find from Activity, utility navigation, and command-palette destinations. Preserve `/search` as a compatibility redirect into Stream with its query intact.
- Keep Ask, backend search APIs, recording formats, and the general Activity visual system unchanged.

## Verification

1. Component tests cover multiline command/output decoding, unknown structured arguments, Stream expansion semantics, and the exact Browse link.
2. Activity/Sessions tests cover the three remaining modes, legacy Find fallback, direct session hydration, event targeting, and project-scope rejection.
3. Backend web tests cover the direct session lookup endpoint and not-found response.
4. Run the frontend suite, targeted Maven tests, `git diff --check`, the isolated packaged Playwright suite, then deploy with `scripts/deploy-local.sh` and verify port 8766.
