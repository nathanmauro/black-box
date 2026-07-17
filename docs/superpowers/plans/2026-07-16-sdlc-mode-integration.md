# SDLC mode integration plan

Date: 2026-07-16
Spec: [`2026-07-16-sdlc-mode.md`](../specs/2026-07-16-sdlc-mode.md)

## Scope

Integrate the already-implemented SDLC runner and frontend slices through the isolated Playwright
harness, document the shipped mode, and fix only integration defects exposed by that end-to-end
proof. FULL_AUTO behavior and the live `:8766` service are outside this slice.

## Tasks

1. Add Playwright coverage beside the existing FULL_AUTO story for the approved SDLC path: gate,
   plan, plan approval, build, review, review approval, fixture-local ship, and linked Handoffs.
2. Add a rejection story that records required plan feedback and proves no build successor appears.
3. Capture named screenshots at the approval and terminal states.
4. Document SDLC mode in `README.md` and `docs/architecture.md`, including the runner/server
   boundary, approval reconciliation, and fail-closed shipping gates.
5. Run the full isolated Playwright suite through its configured entrypoint, then the Maven and
   frontend unit suites, and record any integration-only fixes.

## Safety and verification contract

- The Playwright harness owns its temporary database and fixture repositories on `:8799`.
- The harness is the only allowed packaging path for this slice; no direct package or deployment
  command is run.
- Approval is required before either successor action. Rejection records feedback on the completed
  stage and enqueues nothing.
- The fixture has no `origin`, so shipping returns local-only before invoking a push command.
- All repository changes remain unstaged.

## Result

- Both SDLC stories passed against the isolated fake-engine harness. The happy path proved all four
  stage tasks `done` with Handoffs; the rejection path proved the durable feedback marker and no
  build or review successor.
- The full suite initially exposed concurrent runner specs contending the daemon instance lock.
  Configuring the isolated Playwright suite for one worker made that fail-closed constraint explicit;
  the complete 21-test suite then passed.
- No backend or frontend runtime integration change was required.
