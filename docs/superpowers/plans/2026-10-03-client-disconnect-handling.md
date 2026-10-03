# Handle typed client disconnects without a second response failure

## Scope

NAT-328 changes `ApiExceptionHandler` only. A client that closes the socket mid-response, such as
a companion popup closed during a `font/woff2` download, raised Tomcat's `ClientAbortException`.
The generic handler logged it at ERROR and returned the JSON `ApiError` under the preset font
content type, so the converter failed again with `HttpMessageNotWritableException`. The existing
`AsyncRequestNotUsableException` handler covers only async and SSE responses.

## Approach

- Add a typed `ClientAbortException` handler that logs at DEBUG and returns no body, matching the
  existing async disconnect handler.
- In the generic handler, recognize the same type within a cause chain, such as a converter failure
  caused by an abort. Traversal is type-based, stops on a self-cause, and is capped at 16 hops.
- Do not use Spring's `DisconnectedClientHelper`. It also matches generic EOF types and message
  text, which could hide genuine subprocess or outbound I/O failures.
- Keep the typed 500 `internal_error` body and ERROR logging for every other exception.

## Verification

- Deterministic MVC fixtures: a controller sets `font/woff2` and then throws `ClientAbortException`,
  either directly or wrapped in `HttpMessageNotWritableException`. Log capture covers the handler
  logger and Spring's `ExceptionHandlerExceptionResolver`, so a secondary handler failure is visible.
- Controls: plain `IOException("Broken pipe")`, `EOFException`, a runtime exception caused by
  `IOException("Connection reset by peer")`, an unrelated `HttpMessageNotWritableException`, and a
  cyclic cause chain all still produce a 500 `ApiError` and one ERROR log. The existing async
  disconnect test is unchanged.
- Run the full Maven suite, including the ArchUnit and module tests, and package on Java 21.
- Run an actual HTTP check against a disposable packaged server that uses only its own resources.

## Results

- Before the fix, the two disconnect fixtures failed with 6 controls passing. Each produced
  `[ERROR] Unhandled API exception` and the resolver's `Failure in @ExceptionHandler ...
  handleUnexpected` warning. That warning is the converter cascade seen in the root reproduction at
  companion browser-journey log during a static font response.
- After the fix, all 8 handler tests passed. The full suite ran 1,086 tests with 0 failures and
  74 skipped. It included `PackageArchitectureTest` and the application-module tests. Scoped
  Palantir `spotless:check` passed for the two touched Java files.
- For the actual HTTP check, the packaged jar ran on loopback port 18931 with a fresh temporary
  SQLite DB and a private HOME and TMP. The pinned config was `classpath:/application.yml`.
  Editor, local AI, embeddings, Elasticsearch and the judge were disabled. Process, port and DB
  ownership were checked with `lsof` before any request. Single-request RST aborts of the 300 KB
  JS bundle completed into kernel buffers and raised nothing. Clients that pipelined 20 requests
  on one connection with a 1 KB receive buffer, then closed with `SO_LINGER` 0, produced 5 typed
  DEBUG disconnects from 5 attempts. Each trace ran through `ResourceHttpMessageConverter`. There
  was no request-time ERROR or WARN and no converter cascade, and `/api/status` returned 200 after
  the aborts.
- Cleanup stopped only the owned server process and deleted its temporary storage. The installed
  8766 listener was not touched.

A genuinely cyclic cause chain cannot reach any `@ExceptionHandler`, because Spring's own
handler-method lookup overflows the stack first. That is existing framework behavior, outside this
change. The termination test calls the handler directly.

No installed service, live database, provider or Git mutation was made. The coordinator owns final
review, commit, CI, publication and integration.

## Independent acceptance

A separate source and test review found no remaining issues. The coordinator reran the handler
and package-architecture tests, then used a separately reviewed, artifact-pinned driver against
a fresh private packaged server. Five real aborted static-download connections produced exactly
five typed DEBUG traces through the resource converter, no request-time WARN or ERROR, and no
secondary response failure. Health remained available and canonical event/session counts stayed
zero. The owned server exited, temporary storage was removed, and the installed listener stayed
unchanged. No live history or provider was used.
