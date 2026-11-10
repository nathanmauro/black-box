# Frontend request budgets

The command palette and project story pane avoid requests for intermediate input or hidden views.
These are request-count improvements, not claims about end-to-end latency on a particular network.

## Measured scenarios

The November 10, 2026 fixture comparison renders the actual Solid components with their existing API
mocks. The packaged Playwright check uses a disposable recorder and SQLite database on port 8799.

| Interaction                                   | Before                          | After                   |
| --------------------------------------------- | ------------------------------- | ----------------------- |
| Rapidly type `performance` in the palette     | 10 remote search requests       | 1 remote search request |
| Open a project in the default Trajectory view | 1 graph and 2 timeline requests | 1 graph request         |
| Reload a project with Timeline selected       | 1 graph and 2 timeline requests | 2 timeline requests     |

The timeline fixture contains more than one block, so its count probe and latest-page read require
two requests. Empty or single-block timelines need only the probe. The optimized path still uses
the existing count-change retry to preserve the latest timeline window.

Remote palette search waits for 200 milliseconds without another input change. Navigation commands
and local session filtering remain immediate. Closing the palette, shortening the query below two
characters, changing human-only mode, or unmounting cancels a pending search. Results from an older
request are hidden immediately, including while the next request is pending.

Project story requests follow the selected view, including project switches and alias refreshes.
An explicit `focus` deep link can need the trajectory detail card even when Timeline is selected;
that case still loads both the selected timeline and its requested trajectory evidence.

## Reproduce

From `frontend/`, with Node dependencies installed:

```bash
npx vitest run src/components/CommandPalette.test.tsx src/pages/__tests__/ProjectsPage.test.tsx
```

For the packaged browser check, select Java 21 for Maven first, then run:

```bash
npm run e2e -- tests/e2e/frontend-request-budget.spec.ts
```

The browser check counts real HTTP requests while typing, follows the result to its exact recorded
event, switches project story views, and reloads the persisted Timeline selection. The regular
component checks also cover stale results, close/reopen, query clearing, human-mode changes,
project switching, focus links, and alias refreshes.
