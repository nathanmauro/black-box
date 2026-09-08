# Shared prototype authentication

Safety contract: keep existing loopback access unchanged by default; fail closed when cloud auth is
enabled without independent strong credentials. Protect every app/API/MCP/static route except the
minimal login/health surface. Browser session writes require CSRF; Bearer writes require a valid
explicit credential and never borrow browser sessions. Do not change local runtime state or cloud
infrastructure from this isolated implementation lane.

1. Add managed Spring Security with browser and stateless agent filter chains.
2. Carry the documented SPA CSRF cookie through every frontend mutation.
3. Verify real HTTP login/read/capture/SSE/logout, bad credentials and missing CSRF, stateless Bearer
   capture, MCP initialize, and auth-disabled compatibility. Regenerate frontend assets.
4. Deliver exact changes and evidence for independent security review before cloud publication.

Prepared in an isolated checkout. Verification findings and completion evidence are recorded in the
implementation handoff; cloud deployment and multi-user authorization are separate slices.

## Verified implementation

- Standard Spring Security browser sessions and CSRF, independent stateless Bearer authentication,
  explicit secure-cookie defaults, fixed relative redirects, and servlet-only configuration.
- Real HTTP login/capture/logout, CSRF denial/rotation, malformed-header session isolation,
  stateless capture, MCP initialize, and production cookie flags verified.
- Review reproduced an existing open event stream leaking later events after logout. Publication
  now rechecks browser session validity and inactivity; completion permits only ASYNC dispatch of
  the existing stream. A missing session after filter authentication fails closed. Regression
  covers logout with an open stream, the missing-session race, expiry, and local/Bearer streams.
- Full Java suite: 553 tests, 0 failures/errors, 2 skips before the final three guard regressions;
  final focused guard/HTTP tests also pass. Frontend: 607 tests passed. Packaged Chromium used the
  actual New story form to create a spec/task with CSRF, then logged out and received anonymous 401.
- Packaged non-web doctor exited successfully against an empty temporary database with all provider
  calls disabled. No local live service, existing database, or external account was changed.

Next: integrate with cloud storage/container changes, run the combined suite, and verify the managed
HTTPS endpoint before treating cloud deployment as complete. One shared prototype user remains the
explicit boundary; per-tenant authorization is not implemented.
