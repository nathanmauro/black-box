# Validate structured capture through every client

## Observed problem

REST validates required request fields, but MCP directly invokes structured capture. Missing handoff context dereferences null; missing client identity and blank content can reach ingestion without the REST contract. An agent needs actionable errors and confidence that a rejected write produced no event.

## Frozen acceptance

- Real MCP calls with missing, null, or blank required source, session identity, or content return field-specific errors, without implementation traces or writes.
- Cover Handoff, Decision, Observation, and Projection identity; retain existing projection-path validation.
- A valid Handoff preserves its structured context, recipient, open loops, next action, repository and session identity through MCP and HTTP recall.
- Keep existing successful schemas compatible, optional fields optional, and REST validation intact.
- Use a disposable database and disabled external/model systems. No malformed production writes or service restart.

## Smallest intervention

Validate the common capture-service boundary before persistence, reusing the existing invalid-argument error path. Exercise Streamable HTTP using the actual server with an isolated SQLite store; run capture, MCP and API regression tests and independent review.

## Results

- Reproduced through real Streamable HTTP against an isolated SQLite store: omitted `source`
  exposed `EventIngestRequest.source()` / `String.trim()` implementation details. The valid handoff
  round trip already passed, establishing a compatibility baseline.
- Added twelve lines of validation at the shared capture service boundary.
- Focused verification: 35 tests passed across `StructuredCaptureHttpTest`,
  `StructuredCaptureServiceTest`, `MemoryMcpToolsTest`, `ContextLoopTest`, and `TaskApiContractTest`.
  The HTTP test exercises 33 rejected tool calls (11 required fields, each missing/null/blank),
  verifies unchanged event counts after each, and checks a valid Handoff through both recall routes.
- Full backend regression suite: 595 tests, zero failures/errors, 11 platform/environment skips.
- Fresh independent review found no material correctness or compatibility issues. Its two
  verification suggestions were applied: require an integral event count with a positive write
  control, and assert exact recalled source/event identity. Final HTTP rerun verifies those checks.
- The implementation slice did not deploy. Coordinator integration subsequently passed the final
  637-test suite (zero failures/errors, three intentional skips) and deployed the reviewed server
  locally through the verified deployment procedure. No malformed production probe or publication.
