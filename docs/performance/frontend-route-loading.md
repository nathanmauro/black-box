# Frontend route loading

NAT-623 replaces eager page imports with on-demand route modules. The application shell stays
mounted while Solid loads a page. A shared route wrapper shows module loading and reload recovery
without putting the existing page resources inside a new Suspense boundary.

## Measured result

Measured on 2026-11-10 against the packaged application with seeded, isolated SQLite storage.
The baseline is commit `13c05529`, which ships all routes in one JavaScript file.

| JavaScript footprint                               | Before, bytes | After, bytes | Reduction |
| -------------------------------------------------- | ------------: | -----------: | --------: |
| Entry plus all static imports                      |       341,154 |       75,063 |     78.0% |
| Cold Activity `/`, all requested scripts           |       341,154 |      230,404 |     32.5% |
| Cold companion `/companion`, all requested scripts |       341,154 |       96,218 |     71.8% |

The Activity browser request set contains nine scripts; the companion contains four, compared
with one before. These totals match the build manifest's recursive static import closure for
each route, including the entry and shared dependencies. Counting only the 42,123-byte entry
would overstate the reduction.

Applying Node's default `gzipSync` separately to every requested response body yields:

| Cold route | Before, bytes | After, bytes |
| ---------- | ------------: | -----------: |
| Activity   |       104,035 |       78,657 |
| Companion  |       104,035 |       35,431 |

These are compression estimates, not measured network transfer sizes. The change reduces code
downloaded and made available for parsing on the initial route; it does not establish a latency
or parse-time speedup. Extra requests and separate compression add overhead if every route is
visited: all generated JavaScript totals 349,539 bytes, versus 341,154 before (117,398 versus
104,035 with the same gzip estimate).

Activity still imports its Browse and Ask modes eagerly. This slice changes route loading only.
Vite chooses the shared chunks; there is no manual chunk map or new build dependency.

## Behavior and verification

`lazyRoute` uses Solid's [lazy](https://docs.solidjs.com/reference/component-apis/lazy) and tracks
its module promise for loading feedback. It deliberately does not wrap the page in Suspense:
existing page-level API loading and error states must remain visible. A failed module presents a
reload button at the current URL, while the utility bar can still navigate to another route.
Successfully loaded modules are reused on return navigation.

Verification passed:

- Frontend lint, format, and TypeScript checks; 976 unit tests; production build.
- 28 packaged Chromium tests across route loading, smoke, companion, request budgets, project
  alias deep links, retired route redirects, and Ideas.
- The module-delay test keeps the utility bar and command palette usable, then verifies the
  page's own pending project-catalog state after the module resolves.
- The failed-chunk test navigates away and back, then reloads successfully with its query intact.
- Repeated navigation requests each visited route module once. Companion remains chrome-less,
  including embedded mode and its scoped Recall popup.

The new packaged budgets count every JavaScript response body on a fresh browser page. Activity
must remain below 250,000 bytes and companion below 110,000 bytes; neither may request unrelated
page modules. These bounds leave modest build variation while rejecting the eager baseline.

Run from `frontend/` with JDK 21 selected for the packaged tests:

```bash
npm ci
npm run check
npm test
npm run build
npm run e2e -- tests/e2e/route-loading.spec.ts tests/e2e/smoke.spec.ts \
  tests/e2e/companion.spec.ts tests/e2e/frontend-request-budget.spec.ts \
  tests/e2e/project-alias-deeplink.spec.ts tests/e2e/retirement.spec.ts \
  tests/e2e/ideas.spec.ts
```

For static import accounting, run `npm run build -- --manifest`, then traverse each entry's
`imports` recursively in `src/main/resources/static/.vite/manifest.json`, counting each referenced
JavaScript file once. Do not include `dynamicImports` unless that route is being measured. The
manifest is measurement output only; a normal `npm run build` removes it before committing the
generated static assets.
