# Frontend standards

The SolidJS frontend under `frontend/` is linted by ESLint and formatted by Prettier. Both run as
plain npm scripts, in CI, and in `scripts/verify.sh`. This page records what is configured, why, and
how to work with it day to day.

## Commands

Run from `frontend/` after `npm ci`.

```bash
npm run lint           # eslint . — reports errors and warnings, exits non-zero on errors
npm run lint:fix       # eslint . --fix — applies the rules' safe autofixes
npm run format         # prettier --write . — rewrites files in place
npm run format:check   # prettier --check . — exits non-zero if any file would change
npm run check          # lint + format:check + tsc --noEmit, the same trio CI runs before tests
```

Prettier covers `.ts`, `.tsx`, `.js`, `.mjs`, `.css`, `.json`, and `.html` under `frontend/`.
Generated and vendored paths are excluded in `frontend/.prettierignore` (`node_modules`, `dist`,
Playwright output, `public/`, `package-lock.json`). The built bundle in
`src/main/resources/static/` is never formatted or linted; it comes from `npm run build` only.

Formatting is idempotent: a second `npm run format` after the first reports no changes, and
`npm run format:check` is clean immediately afterwards.

## Tools and versions

| Tool | Version | Role |
| --- | --- | --- |
| eslint | 10.11.0 | rule engine, flat config (`frontend/eslint.config.js`) |
| @eslint/js | 10.0.1 | `js.configs.recommended` |
| typescript-eslint | 8.70.1 | TypeScript parser and `recommended` rules (syntax-only, no type information) |
| eslint-plugin-solid | 0.18.0 | SolidJS correctness rules via `configs.typescript` (flat preset) |
| eslint-config-prettier | 10.1.8 | turns off every stylistic rule that would fight Prettier |
| prettier | 3.9.9 | formatter for TS/TSX/JS/CSS/JSON/HTML |
| globals | 17.12.0 | browser, Node, and Vitest global names |

Versions are pinned exactly in `frontend/package.json`. typescript-eslint 8.70 declares support for
TypeScript `>=4.8.4 <6.1.0`, which covers the project's TypeScript 6.0; eslint-plugin-solid 0.18
supports ESLint 9 and 10. ESLint 10 needs Node 20.19+, 22.13+, or 24; CI uses Node 22.

## Why ESLint plus Prettier rather than Biome

Biome is a fast single binary that lints and formats TypeScript, CSS, and JSON, and it would have
been the newer choice. It was not selected because the correctness rules that matter most for this
codebase are Solid-specific: `solid/reactivity` (signals and props read outside a tracked scope),
`solid/components-return-once`, `solid/no-destructure`, `solid/prefer-for`, and `solid/jsx-no-undef`.
Those live in `eslint-plugin-solid`, which is maintained by the Solid community and has no
equivalent in Biome's rule set. Prettier is the formatter the Solid, Vite, and typescript-eslint
ecosystems assume, its CSS output is stable, and `eslint-config-prettier` removes every overlap, so
the two tools never disagree. Ecosystem fit won over novelty; revisit if Biome ships a Solid
reactivity rule set.

Typed linting (`projectService`) was left off on purpose. It would add rules such as
`no-floating-promises` at the cost of a slower run and a tsconfig that covers `tests/`, which the
current browser-scoped `tsconfig.json` does not. It is a reasonable follow-up.

## Configuration decisions

`frontend/eslint.config.js` layers, in order: ESLint core recommended, typescript-eslint
recommended, the Solid TypeScript preset for `**/*.{ts,tsx}`, project overrides, Vitest globals
for `src/**/*.test.{ts,tsx}` and `src/test/**`, and eslint-config-prettier last.

Two scoped exemptions exist, each with a comment in the config:

- `no-unassigned-vars` is off for `**/*.tsx` only. Solid assigns
  `let el: HTMLElement | undefined` through the JSX `ref={el}` binding at compile time; core ESLint
  cannot see that write and reports every ref as never assigned. The rule stays on for `.ts` files.
- `solid/reactivity` is off for Vitest specs. Tests read signals and mutate router params outside
  tracked scopes deliberately; the rule is only meaningful for component and store code.

`@typescript-eslint/no-unused-vars` is an error, with a leading underscore marking an intentional
placeholder for arguments, variables, caught errors, and destructured array slots.

`frontend/.prettierrc.json` sets `printWidth` to 100 and otherwise keeps Prettier's defaults
(double quotes, semicolons, trailing commas, two-space indent), which matched the existing code
style; the 100-column width follows the width the sources already used.

## Warnings that remain

The Solid preset reports `solid/reactivity` and `solid/components-return-once` as warnings, and
`npm run lint` exits zero on warnings. The remaining warnings are genuine reactivity findings in
component code (props read outside JSX or a tracked scope; early returns from components). They are
UI behavior changes and are tracked as follow-up work rather than silenced here. Once they are
resolved, tighten the script to `eslint . --max-warnings 0` so new ones cannot accumulate.

## Editor integration

`.editorconfig` at the repo root gives JetBrains and VS Code the same indentation, line endings, and
column widths the formatters use. `.vscode/settings.json` points the ESLint and Prettier extensions
at `frontend/` and enables format-on-save for TS, TSX, JS, CSS, and JSON;
`.vscode/extensions.json` recommends those extensions. JetBrains IDEs read the Prettier and
ESLint config files directly once the bundled plugins are enabled for the project.

## Git hooks (opt-in, not installed by default)

`scripts/git-hooks/` holds a `pre-commit` hook that runs `prettier --check` and `eslint` over the
staged frontend files, and the existing `pre-push` hook that runs `scripts/verify.sh`. Neither is
active until you opt in for your clone:

```bash
git config core.hooksPath scripts/git-hooks
```

That command changes only the local repository configuration. Nothing in this repository sets it
for you, and CI is the enforcement point for everyone else. Skip a single hook run with
`--no-verify`. The pre-commit hook checks the working-tree copy of each staged file, so run
`npm run format` before staging.

## Working with the one-time reformat

The initial `npm run format` pass touched most files under `frontend/`. Its commit is listed in
`.git-blame-ignore-revs`; GitHub's blame view honors that file automatically, and locally you can
opt in with `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

When a branch created before the reformat conflicts on formatting only, resolve it mechanically:
take either side of the conflicted file, run `npm run format` in `frontend/`, and re-run
`npm run check` and `npm test`. The formatter converges on the same output from both sides.

## Verifying a change

```bash
cd frontend
npm run check
npm test
npm run build          # when source changed; commit the regenerated static bundle
git diff --check
```

To prove the checks still bite, drop a file with an unused variable and an unformatted expression
into `frontend/src/`, watch `npm run lint` and `npm run format:check` fail, then delete it. Do not
commit such probes.

## Packaged browser CI

The frontend CI job runs the entire Chromium journey suite after lint, format, type and unit checks.
It installs Temurin 21, Chromium's Linux dependencies, and the Python/SQLite/lsof tools used by the
fixture safety checks. A separate Maven frontend package step completes dependency downloads and
compilation before Playwright's existing 180-second server-start deadline. The runner still rebuilds
and launches the current packaged application itself.

The browser command is:

```bash
npm run e2e -- --workers=1 --retries=0 --forbid-only --trace=retain-on-failure --reporter=list,html
```

Run it from `frontend/`, or use `./scripts/verify.sh --e2e` for the full local verification gate.
Install the matching browser with `npx playwright install chromium` locally; Linux CI uses
`npx playwright install --with-deps chromium`. Keep port 8799 available. The suite only seeds
`127.0.0.1:8799`, uses owned temporary SQLite storage, clears ambient application configuration,
disables model providers/judge, and checks the protected port 8766 before and after. Do not bypass
those guards or point it at an existing service. The editor journey invokes a fixture executable.

Use one worker and zero retries: the suite shares an owned project fixture and checks exact editor
calls. Traces are retained for first-attempt failures, alongside screenshots and an HTML report.
CI uploads only the synthetic fixture's `frontend/test-results/` and `frontend/playwright-report/`
directories, excludes hidden files, and expires artifacts after seven days. It does not upload
temporary databases, environment files or the checkout. Workflow token permissions are read-only.
An Ubuntu Actions run of the full suite is required before accepting a change to this gate;
local macOS success alone does not establish Linux compatibility.

## Responsive utility header

At widths up to 700px, the utility header places navigation and display controls on separate rows.
Browse at compact landscape sizes uses the single-row exception described below.
All destinations, source filters, My turns, connection status, and the command palette remain
available; navigation must not shrink underneath adjacent controls. Keep the header height variable
in sync so sticky panels and viewport-sized pages clear both rows. The source panel stays inside
the viewport. `tests/e2e/mobile-header.spec.ts` verifies non-overlapping controls, viewport bounds,
keyboard navigation, source-menu access, My turns, and command access at 320, 390, 768, and 1440px.

## Browse payload disclosure

Generic tool Input/Result sections over 1,200 characters mount on first disclosure. Their display
preview replaces explicit data URLs and typed media/blob fields with MIME type and encoded-character
counts while retaining surrounding text and metadata. An **Original input/result** disclosure opens
the exact captured payload string. Traversal stops after 64 object/array levels with an explicit
placeholder and note, keeping deeply nested valid JSON safe to preview. Small ordinary sections
stay directly readable. This changes only
presentation: canonical evidence and API responses remain unchanged, and no media is loaded or sent
to a provider. Existing specialized presenters keep their own disclosure behavior.

Generic Input/Result previews flag numeric values that change when decoded into JavaScript numbers,
including rounding, overflow, underflow and displayed negative zero. Each successful JSON decoding
layer is checked, up to the existing two-pass decoding limit. Numeric-looking text inside strings
is unchanged; malformed JSON remains raw text without a misleading precision warning. The legacy
text-result exit-code conversion receives the same diagnostics without changing its return values.

A changed value adds a warning and **Original input/result** even for a small payload. Original
always contains the complete captured string, including any outer JSON encoding. Numeric checking
is bounded to 131,072 UTF-16 code units per decoded layer; larger layers and unsupported legacy
numeric forms receive an explicit unchecked-precision note and original access. A later successful
check does not erase an earlier uncertainty; proven loss takes precedence. Ordinary safe small
payloads gain no warning or extra control. The separate transcript identity bound stays at 32,768
characters, with its existing conservative matching rules. Specialized presenters and metadata
parsing are outside this generic-preview behavior. See the
[numeric preview verification](superpowers/plans/2026-10-03-tool-payload-number-preview.md).

Native `details` controls support keyboard access. As with existing tool disclosures, opened content
stays mounted after closing but is excluded from accessibility while closed. ReaderText instead
renders an actual excerpt (at most 900 characters or 10 nonempty lines, plus an ellipsis) while
collapsed, removes the remaining text from the DOM, and exposes accurate `aria-expanded` and
`aria-controls` on its toggle. Expansion restores the exact captured text. Do not replace this with
CSS-only clipping, hidden full-text attributes, or inferred binary detection for arbitrary strings.

First-turn headers use the same excerpt logic with a 280-character/four-nonempty-line bound and
an explicit Show all/Show less control linked to the text. One-line session and command labels use
at most 160 characters plus an ellipsis. Filtering and title deduplication still use the full
original turn, and opening the header restores that original exactly. CSS may tighten a bounded
header preview visually, but must not leave the entire original behind the collapsed control.


## Replacement session scope

Recall groups manual decision replacements under one generated session per target capture's repo
for the current page visit. Replacements in the same repo reuse that session, including after
changing filters; different target repos never share it, including in All projects. This preserves
project counts and evidence attribution without changing backend session identity or stored history.

## Mobile Browse reader

At widths up to 880px, Browse uses the available workspace for either the selected reader or its
searchable **Sessions** chooser. **Session details** reveals path, first-turn context, summary and
dates; source, title, transcript search and the memory toggle remain available while details are
closed. The existing desktop rail and details stay visible above that breakpoint.

Both disclosures expose expanded state and controlled-region IDs. Escape returns focus to the
opening button; choosing a session focuses its heading. Searching the mobile chooser does not
change the reader until a session is chosen. On a switch to desktop, a chooser filter that would
hide the selected session is cleared so the current reader remains available. Collapsed regions
are hidden from keyboard and accessibility navigation, and responsive changes recover focus from
controls that become hidden.

Exact-source links scroll the mobile transcript container and focus the selected event without
moving the outer app controls offscreen. Direct `/sessions/:id` pages use the same bounded mobile
reader, with scrolling owned by its inner panes. The packaged [reader journey](../frontend/tests/e2e/browse-mobile-reader.spec.ts)
checks this without test-side scrolling at 390×900 and 390×700, plus desktop, selection/search,
keyboard disclosures, memory, pagination and responsive transitions. See the
[verification plan](superpowers/plans/2026-10-03-mobile-browse-reader.md) for measured reader space.

At 600–880px wide and no more than 500px tall, Browse puts its two disclosure buttons beside the
reader, uses a vertical scrollable turn rail, and places the search label beside its input.
The Activity and session headers reduce spacing; project/source/title, memory, search and status
remain available. The utility bar uses one row only while Browse is present at these dimensions.
This avoids stacking controls into the entire landscape viewport without changing portrait or
larger desktop layouts. The same packaged journey checks 667×375 and 844×390, full exact-source
visibility, keyboard turn-rail scrolling, project scope and non-overlapping utility actions.
See the [landscape verification plan](superpowers/plans/2026-10-03-browse-landscape-reader.md).

## Human-only session pickers

The sticky **My turns** setting applies to ordinary Browse session lists, including project scopes,
and command-palette session picks and event search. Requests include `humanOnly=true`; the server
filters before applying the result limit, so recent machine-only sessions cannot displace older
human sessions. The project sessions endpoint accepts this optional parameter (default `false`)
and returns the existing `firstHumanTurn` field for both filtered and ordinary lists.

Switching the setting refreshes lists and immediately hides stale nonhuman rows. If a known ordinary
selected session in the active project/source scope has no human turn, Browse selects a matching human session and updates its link.
Unknown direct session IDs retain the unselected state; they do not silently open another session.
Existing visual fallbacks for source/project mismatches do not rewrite the requested session URL.
An explicit event link can still reveal a nonhuman event and its owning session while **My turns**
is enabled. This exception continues to respect the selected project and source filters.

The [picker journey](../frontend/tests/e2e/human-session-pickers.spec.ts) exercises actual HTTP
capture, global/project selection, off/on/reload, palette picks, source filtering and exact evidence.
See the [verification plan](superpowers/plans/2026-10-03-human-only-session-pickers.md).

## Stream controls and empty states

Views and Options are mutually exclusive disclosures. Escape closes the active panel and returns
focus to its trigger; a pointer press outside closes it without moving focus. The Sources panel
also closes on Escape or an outside press. Interacting inside a panel keeps it open. When query
suggestions are visible, the first Escape dismisses only those suggestions and keeps query focus;
a subsequent Escape closes an open Views, Options or Sources panel.

Narrow Stream layouts wrap the query controls and session actions so they remain reachable.
Command-palette event labels retain a bounded single-line excerpt; My turns continues to use the
cleaned human text, and selection still opens the exact original event.

Empty copy distinguishes an unfiltered recorder, meaningful-only results, My turns, and explicit
project/query/source filtering. Loading and failed requests must not claim the recorder is empty.
The packaged `stream-controls.spec.ts` journey covers the controls at desktop, narrow portrait and
landscape sizes; intercepted empty/error responses supplement real capture and navigation checks.

## Stream query composition

The query box completes the token under the caret, not the last token, and accepting replaces
only that token, or one comma value of it. Negated (`-kind:`, `NOT kind:`), quoted and aliased
tokens complete as typed. Field and operator names complete from their first letter; the hidden
`project_group:` is never offered. Time operators offer grammar values only, and `is:` offers
`all`.

Values come from recorded data only:

- `source:`, `kind:` and `tool:` list the applied query's counted values, labelled "in current
  results". The number is shown only while the draft's other filters match the applied query
  (otherwise the values stay, without numbers), and it stays out of the option's accessible name;
  a polite live region announces it.
- When counts are unavailable or leave room, the value index fills in, labelled "recorded" (it is
  global and ignores the current filters).
- `project:` and `project_exact:` complete from the current results' project paths and the
  project catalog, by substring and by exact prefix; only `project_exact:` shows counts, because
  they count exact paths. While a project is pinned, `project:` shows a note instead, because the
  pinned scope replaces project filters.
- `session:` lists recent top-level sessions (subagent sessions are not listed), filtered to the
  pinned project, by title and last-seen age, and inserts the server id.

Values containing a quote or a control character are never offered; values with whitespace or
commas are quoted, never escaped. Inside an unclosed quote the value has no known end, so
completion waits until whitespace or the end of the query follows the caret, and nothing after
the caret is replaced.

Up and Down move the highlight. Enter accepts a highlighted option and otherwise submits the raw
text. Tab accepts the highlighted option, or the first one once the token has a typed prefix; it
never accepts with a modifier, during IME composition, or on an empty draft, and otherwise moves
focus. This is a deliberate deviation from the ARIA combobox pattern. A value or field name typed
in full is listed first, so Tab keeps it rather than a longer or more frequent one, and a whole
word that already names a field (`fix session`) becomes a field only when highlighted. Accepting
a value closes the list until the next keystroke; accepting a field name opens its values.
Options keep their elements while counts or values arrive, so a press that began on one still
accepts it. When the applied query replaces the draft (Back, Forward, a fix), the caret moves to
the end of the new text and the list closes.

After a query runs, notes under the chips explain what the parser did not apply as a filter: an
unknown field (suggesting a field only for a unique close match, and never for a name with
nothing after its colon, such as `TODO:`), a time value the grammar rejects (date-shaped values
excepted, because the server parses more date-time forms), a negated operator or excluded free
text, an unclosed quote, a lowercase `not` that negated the next filter, or a project token the
pinned project removed. An empty result offers one-click fixes that each change one token and run
the query:

- A close value for an exact `source:`, `kind:` or `tool:` value, when that field's counted list
  is complete and the rest of the query still matches rows. Each field's counts drop only that
  field's own values, while free text, other filters and time bounds still apply, so free text or
  another filter that matches nothing leaves no value to offer.
- `is:all` when the meaningful filter may be hiding rows (not under My turns, which replaces that
  filter).

`is:all`, like the pinned project's hidden scope, goes first when the query ends in an unclosed
quote or a bare `NOT`, which would otherwise swallow or negate it.

A pinned project removes positive `project:`/`cwd:` includes from the URL's `q` by span, leaving
every other byte as typed, and replaces the history entry instead of pushing one, so Back never
lands on a URL that normalizes forward again.

The packaged `stream-compose.spec.ts` journey covers keyboard-only composition, a mid-string edit
and a quoted project path at desktop and phone widths.
