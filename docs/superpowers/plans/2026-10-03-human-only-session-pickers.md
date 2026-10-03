# Keep human-only session pickers consistent

NAT-225 acceptance audit reproduced sticky My turns → Browse fetching an unfiltered session list,
selecting the newest machine-only session and displaying an empty reader despite an older human
aside. The actual human-only sessions API already returned the correct list. Reproduction used
only synthetic HTTP captures and a disposable packaged app; no live corpus was copied or queried.

## Contract and implementation

- Global Browse and command-palette session requests react to human mode. Palette fallback search
  uses the existing human-only API and cleaned, bounded labels; all-events label behavior stays intact.
- Project session retrieval gains optional `humanOnly=false`, applied in SQL before ordering/LIMIT
  across the existing logical project aliases. Existing Java overloads and unfiltered behavior remain.
- Project session mapping retains the existing firstHumanTurn field rather than discarding it.
  No schema migration, new DTO field or generated wire type is required.
- Stale nonhuman picker rows disappear immediately on toggle. A verified ordinary nonhuman selection
  can fall back to a matching human session and synchronize its URL. A failed/unknown direct session
  lookup cannot trigger that fallback. Existing default selection behavior remains intact.
- Exact event links retain their nonhuman evidence and owner-session exception; project and source
  filters still bound that exception. Canonical captures and classification rules do not change.

## Verification

The original packaged regression and the new HTTP project limit/alias regression failed before the
fix. Review also reproduced an overly broad unknown-direct-ID fallback, then narrowed it and added
component plus actual-route coverage. Final checks and packaged suite results are recorded below.

Final verification:

- All 699 frontend tests across 60 files passed. `npm run check` passed TypeScript, formatting and
  lint (zero errors, 70 existing warnings).
- All 59 packaged Chromium journeys passed together with zero retries, including desktop/mobile
  human pickers, the existing sticky human-turn stream test and the full mobile Browse journeys.
  The browser fixture stays small and asserts actual global/project `limit=1&humanOnly=true`
  responses; it does not crowd out other tests' shared seed rows.
- Twelve actual session/project HTTP tests passed across SQLite and PostgreSQL with no skips.
  The new case places five newer machine-only sessions ahead of two human sessions across aliases,
  then requests limit two. It verifies both human rows and their first-turn metadata survive,
  default/false remains unchanged, foreign project rows stay excluded, and direct access remains.
- Forty-two related project/alias/controller tests and eleven contract/architecture tests passed.
  The affected curated REST matrix row records the optional parameter and existing response field.
  No wire DTO or generated TypeScript contract changed.
- Scoped Java formatting and `git diff --check` passed. The normal packaged build regenerated
  the JavaScript asset and static index; CSS is unchanged.

Independent review confirmed an auto-selection scope regression: a requested nonhuman session
excluded by source/project scope could have its URL replaced by the visual fallback. Both cases
failed focused regressions before sharing one scoped human-exclusion predicate between selection
and navigation. Actual project-mismatch navigation now preserves the requested URL. A separate
palette regression demonstrated retained results when its query became too short; restoring the
empty-query response cleared those results. All checks above include these corrections.

The isolated server, project fixture and database were cleaned up and port 8799 released. The
protected 8766 listener remained unchanged. Production database identity/row-count checks were
unavailable from this checkout and are not claimed. PostgreSQL used only a coordinator-provided
loopback fixture and a fresh schema; the worker did not start or stop that service. No provider or
production storage was used. Screenshot inspection covered 390px, 320px and the landscape finding
below; physical devices and non-Chromium browsers remain untested.

## Separate follow-up

The inherited mobile layout was measured without changing CSS: 320×700 remained bounded and had
184px of visible transcript. At 844×390 landscape only about 16px of its 38px transcript pane was
visible, despite no document horizontal overflow. A compact-height layout follow-up is separate
from this selection/filter correction; no broad redesign is included here.

## Handoff

Codex owns the uncommitted source/tests/docs and normally regenerated assets on
`codex/human-only-session-pickers`. Coordinator owns fresh acceptance, commits as Nathan,
integration, PR and merge. No live app/configuration, provider, Linear or Git mutation was performed
by the worker. The unused createSessionsResource helper is deliberately unchanged.
