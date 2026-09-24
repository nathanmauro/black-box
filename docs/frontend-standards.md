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
| eslint-plugin-solid | 0.18.0 | SolidJS correctness rules via `configs["flat/typescript"]` |
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
