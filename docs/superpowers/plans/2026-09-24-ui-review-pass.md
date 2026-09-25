# UI review pass (2026-09-24)

## Scope

A practical review of the shipped SolidJS UI: every route exercised in a real Chromium against a
disposable packaged jar on an isolated SQLite database (port 8798, never 8766), at desktop
(1440×900) and phone (390×844) widths, in empty and seeded states. Seed data was synthetic and
posted through the public REST API (`/api/events`, `/api/decisions`, `/api/handoffs`,
`/api/projections`, `/api/specs`, `/api/tasks`, `/api/session-links`). No production record,
service, or configuration was touched.

Out of scope, owned elsewhere: lint/formatter tooling and any broad formatting sweep.

## Findings fixed

| # | Surface | Finding | Fix |
|---|---------|---------|-----|
| 1 | Stream | The `Filter` submit button rendered as an unstyled UA button (near-white block, invisible label) at every width because the shared primary-button rule only matched `.search-form button` and the Stream form is `.stream-filter-bar`. | Added the Stream submit button to the primary-button selector group. |
| 2 | Stream, phone | The query line never wrapped: the input shrank and `Views`/`Options` overlapped `Filter`. | ≤700px: the input takes the full first line; the buttons drop below. Declared after the base rules so it wins on equal specificity. |
| 3 | Stream, phone | Run-header actions (`Filter to this session`, `Copy link`, `View session →`) overflowed the feed's `overflow-x: hidden` and were unreachable. | ≤700px: the run header wraps, the session title owns its own line. |
| 4 | Stream | `Views` and `Options` could both be open at once (one painted over the other), and neither closed on Escape or an outside click, unlike the suggest popover and project picker. | One shared open slot; Escape closes and returns focus to the trigger; outside pointer-down closes. |
| 5 | Utility bar | The `Sources` panel ignored Escape and outside clicks. | Same dismissal contract as the Stream disclosures. |
| 6 | Projects, phone | The catalog pane's project picker kept `min-width: min(420px, 100%)` and bled past the pane's right edge. | `min-width: 0` on the catalog-pane picker. |
| 7 | Browse | The `Show memory events` toggle rendered inline after the date range, colliding with it. | The toggle is a block-level flex row on its own line. |
| 8 | Command palette | Events whose text was ≥120 chars lost their text entirely and showed the bare kind (`Decision`, `Handoff`), and label/meta had no spacing. | Text is trimmed to a single-line 120-char excerpt with an ellipsis; meta gets an 8px gap. |
| 9 | Stream, Browse | Empty states on an empty recorder blamed "the current filters" when none were active. | Copy distinguishes an empty recorder (`No events recorded yet` / `No meaningful events recorded yet` / `No sessions recorded yet`) from a narrow filter, source chip, or project scope. |

Tests added: Stream (panel exclusivity/dismissal, empty-state copy), App shell (Sources dismissal),
Command palette (long-text label), Sessions (empty copy). Static assets regenerated with
`npm run build` (never hand-edited).

## Findings left open

- **Recall form at 1440px** wraps the Window radios onto two lines and squeezes the Scope input so
  its placeholder truncates. It works; the `@media (max-width: 1200px)` reflow could start higher
  (~1500px) or the window options could be a `<select>`. Cosmetic, not blocking.
- **Board empty state icon** is the letter `Q` in a circle; it reads as a glyph accident rather than
  an icon. Consider the utility-bar board icon.
- **Projection rows in the Stream** render with the chatter `·` mark and no badge because
  `Projection` is not a landmark kind. Fine for now; a badge would match Recall/Graph, which do
  treat it as structured.
- **Trajectory graph on phone** is a horizontally scrolling canvas by design; nothing is broken,
  but there is no visible affordance that it scrolls.
- **Light color scheme** is not supported; the theme is dark-only and ignores
  `prefers-color-scheme: light`. Acceptable for a local tool, noted for completeness.
- **Session-link seeding**: the REST link types are `spawned`, `steered`, `continued`; the
  Sessions rail expander appears only for `spawned` children. Not a bug, but easy to trip over.

## Verification

- `cd frontend && npx tsc --noEmit` clean; `npx vitest run` full suite green (including the new
  tests); `npm run build` regenerated `src/main/resources/static`; `mvn -q -DskipTests package`
  built the jar used for the browser pass.
- Browser verification (Playwright, packaged jar, seeded isolated DB): Filter button computed
  style is the accent primary (46px); Views/Options exclusivity, Escape (focus returns to
  trigger) and outside-click dismissal; Sources Escape/outside dismissal; palette labels carry
  trimmed excerpts; memory toggle sits below the date range; phone query line wraps; phone run
  header actions stay inside the viewport; phone project picker stays inside the catalog pane.
  No console errors, page errors, or non-2xx responses across all routes at either width.
- `git diff --check` clean.
