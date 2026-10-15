# Stream Query Composition

Date: 2026-10-15. Branch: `claude/stream-next`. Linear: NAT-369. Companion slice: project
continuation prompts (NAT-370, branch `claude/continuation-prompts`).

## Goal

Make the Stream query box help a person narrow to the relevant work without knowing the grammar by
heart:

- complete the token being edited without corrupting the rest of the query;
- ground every offered value in recorded data and say where it came from;
- explain what a submitted query did, especially when it returns nothing.

Out of scope:

- an anchored reading position inside the Stream;
- pairing PreToolUse/PostToolUse rows;
- a server-side prefix facet endpoint;
- decision sub-kinds (unmerged);
- free-text chips;
- empty-box orientation;
- offering `NOT`/`-` as options;
- any visual redesign.

## Before: observed friction

Observed in Chromium against an isolated build of `a032c8f1` (temporary SQLite database, optional
systems off), seeded with three synthetic projects, an alias, a replaced decision, ideas, a
projection and evidence.

| Interaction | Observed | Why it matters |
|---|---|---|
| Editing the middle token `kind:Dec|` in `kind:Dec source:claude` | Suggestions are for `source:claude`; accepting yields `kind:Deci source:claude ` | Suggestions follow the last whitespace token, not the caret, and can rewrite the wrong token |
| `project:"/tmp/…/harb` | The quote is sent as part of the value prefix; nothing is offered | Quoted values never complete, so a project path containing a space cannot be completed |
| `-kind:Obs` | Nothing is offered | Negated tokens get no help, although the grammar supports `-field:` and `NOT` |
| `ki` | Nothing is offered | Field names must be memorised from the placeholder |
| `kind:` | A fixed list, including kinds with no recorded events | Static quick values invent data and can lead to empty results |
| `session:` | Raw client session ids, global, refetched on every keystroke | Not human-readable; ignores the selected project |
| `kind:Hand`, then Enter | Runs an exact match that returns 0 rows; generic empty state | Partial values and typos fail silently |
| `source:cl`, then Tab | Nothing is accepted, and the popover stays open as focus leaves | Keyboard flow breaks |
| Filter to this session | The chip shows a UUID prefix | Scope is not legible |
| Pinned project with an unclosed or embedded quote | Normalization re-serializes the query and never settles | Possible navigation loop; typed text can be erased |

## Slice 1: grammar-aware, caret-aware completion

**`frontend/src/lib/queryCompletion.ts` (pure, unit-tested)**
- **Span tokenizer.** UTF-16 offsets, the same quote rules as `query.ts`. Boundaries are identical,
  because surrogate halves are neither whitespace nor `"`.
- **`completionAt(input, caret)`.**
  - It identifies the token or comma segment under the caret, its field (keeping the typed alias),
    the unquoted prefix before the caret, and the replacement range.
  - Negation follows the parser's own `negateNext` state, not a lexical check: a leading `-`, a
    preceding `NOT` that the parser would apply, and lowercase `not` all count.
  - A token ending at the caret wins. A token starting at the caret is edited with an empty prefix
    and its whole range.
  - With the caret in whitespace, or an empty draft, nothing is offered until a letter is typed;
    ArrowDown still opens the list.
- **`applyCompletion(...)`.**
  - It replaces only the range. It quotes values containing whitespace or commas, never emits `\"`,
    and never offers a value containing `"`, a newline or a control character.
  - With an unclosed quote, the prefix is the text after the opening quote up to the caret, and the
    range is [token start, caret). It inserts a closed quoted value, and everything after the caret
    stays byte-identical.
- **Accept rules.**
  - Append one space only when the range ends at the end of the input. Mid-string, the suffix is
    untouched and the caret lands at range start plus the inserted length.
  - Accepting a value closes the popover; it reopens on the next `input`.
  - Accepting a field name (`ki` → `kind:`) appends no space and opens the value list.
  - Submitting closes the popover.
  - The caret is set with `setSelectionRange` after the draft update.

**Vocabulary and value sources**
- **Vocabulary.** The exported field and operator list from `query.ts` (not `project_group`), each
  with one line of help. Time operators offer grammar values only; `is:` offers `all`.
- **Counted values.** These are facet counts for the applied query. A number is shown only when the
  draft, minus the edited token, has the same canonical form as the applied visible query; otherwise
  the value appears uncounted. The count is `aria-hidden`, so the option name stays the value, and
  counts are announced through the polite live region. Case variants are merged only for
  source/kind/tool, which the server lowercases. Project counts are exact-path counts, so they are
  shown only for `project_exact:`.
- **Value index.** Values from `/api/search/values` are labelled "recorded" (global). Requests are
  debounced and aborted, and results are filtered client-side against the literal prefix.
- **Projects.** Values come from the loaded catalog. Matching is by substring for `project:`
  (mirroring the filter) and exact for `project_exact:`. `__no_project__` is offered only as
  `project_exact:`. While a project is pinned, `project:`/`cwd:` shows a single non-selectable note
  instead, because submit strips those tokens.
- **Sessions.** Recent sessions (`getSessions`), fetched once per popover opening and filtered
  client-side, by the pinned project when one is set. Each shows its title and "last seen" age (the
  age is omitted when unknown), and accepting inserts the server id.

**Keyboard and accessibility**
- Up/Down move the highlight.
- Enter with a highlight accepts without running; Enter without one submits the raw text
  (unchanged).
- Tab accepts the highlighted option, or the first option when the caret token has a non-empty
  prefix. It never accepts with a modifier, during IME composition, or on an empty draft; otherwise
  it closes the popover and focus moves. This is a documented deviation from the APG combobox
  pattern.
- Escape closes the popover first (unchanged).
- The listbox stays mounted with the `hidden` attribute, so `aria-controls` never dangles.
- Options keep focus on pointerdown.
- The input sets `autocapitalize`, `autocorrect` and `spellcheck` off, and `enterkeyhint=search`.

**Prerequisite fix**
- Pinned-project normalization removes positive `project:`/`cwd:` tokens by span: the token plus
  one adjacent whitespace run, then trim.
- It is idempotent, and it leaves tokens with unbalanced quotes untouched.
- The Ask surface swaps in the same function; its dead find-mode code is left for a separate
  cleanup.

## Slice 2: interpreted query and zero-result recovery

**`frontend/src/lib/queryInterpretation.ts` (pure)**
- **Unknown field names searched as text.** A field is suggested only for names of at least 3
  characters within Damerau-Levenshtein distance 2, with a unique best match. URL schemes and
  1-letter names are never suggested.
- **Time values.** A note appears only when the value cannot be ISO-shaped (for example `last:7`).
  The wording matches the server's diagnostics.
- **Grammar limits.** Notes for exclusions the grammar cannot apply (`-word`, negated operators),
  an unclosed quote, a lowercase `not` that negates, and a project token stripped because a project
  is pinned.
- **Zero-result "did you mean".**
  - Only for exact-match facet values (source/kind/tool), and only when the counted list for that
    field is complete (fewer than 25 rows).
  - Only when the rest of the query still matches rows: each field's counts drop only that
    field's own values, so free text, other filters and time bounds still narrow the list.
  - Never for `session:` or `project:`.
  - The one-click fix rewrites only that token, by span, and runs the query.
- **Meaningful filter.** When a query returns no rows and does not already include `is:all`, a
  one-click `is:all` fix appears, because the meaningful filter hides some kinds.

**Page changes**
- The notes render under the chip rail, and in the empty state.
- The session chip uses the loaded rows' session title when one is present.
- The "Prompts today" preset adds `is:all`, because prompts are not meaningful-filter rows.

## Tests changed on purpose

- `StreamPage.test.tsx`:
  - the static `kind:` list;
  - session suggestion values (now server ids with titles);
  - counted options' accessible names (`name: "Bash"`, count asserted separately);
  - the preset q.
- Spec `docs/superpowers/specs/2026-08-20-stream-legibility-and-queriability-design.md`:
  - §4.6, static quick values: replaced by recorded values;
  - §6.4, session titles and time values: delivered.
- These stay byte-identical: `smoke.spec.ts:61-91`, `stream-controls.spec.ts:105-112,145-182`,
  `stream.spec.ts:78-132`.

## Acceptance

- **Unit**
  - `kind:dec| source:claude` gives field `kind`, prefix `dec`, range [0,8). Accepting `Decision`
    gives `kind:Decision source:claude`, the caret at 13, and a byte-identical suffix.
  - Negation and lists: `-kind:obs` becomes `-kind:Observation`; `NOT kind:obs` becomes
    `NOT kind:Observation`; `source:codex,cl` becomes `source:codex,claude`.
  - `event_type:Dec` keeps its alias, and `ki` offers `kind:`.
  - `project:"/tmp/x/harb| kind:Decision` gives `project:"/tmp/x/harbor notes" kind:Decision`.
  - `"😀 bug" kind:dec|` gives range [9,17).
  - `last:` offers durations only.
  - Parsing the result differs from parsing the input only in the edited field.
  - Strip is idempotent over the fixture inputs plus the unbalanced and escaped cases.
- **Component**
  - With counts kind=[Decision 12, Handoff 3], `kind:` lists exactly those, with `aria-hidden`
    counts.
  - A draft that changes another field shows no counts.
  - Tab accepts on a non-empty prefix and is inert on an empty draft.
  - Enter without a highlight submits the raw text.
  - A session option shows its title.
- **Interpretation**
  - `knd:Decision recall last:7` gives two notes: `knd:` (kind?) and `last:7` (needs a unit).
  - With Decision and Handoff events in the last week, `kind:Hand last:7d` returns no rows. The
    server's kind counts drop only the kind values, so `last:7d` still applies and they list
    [Decision, Handoff]. `kind:Hand` gets the fix `kind:Handoff`.
  - Free text that matches nothing empties the counted lists, so `knd:Decision kind:Hand recall
    last:7` gets no value fix against a real server. The pure unit test still covers all three
    notes, with the counts passed in directly.
  - There is no "did you mean" while counts are unavailable.
- **Browser, 1440 and 390 px, scoped to an owned `project_exact:` repo**
  - Keyboard-only composition narrows the rows, and the URL `q` equals the composed string.
  - A mid-string edit and a quoted path with a space complete correctly.

## Verification

```bash
cd frontend
npx vitest run src/lib/__tests__/queryCompletion.test.ts src/lib/__tests__/queryInterpretation.test.ts
npx vitest run src/pages/__tests__/StreamPage.test.tsx
npm test && npm run check && npm run build
npm run e2e -- tests/e2e/stream-compose.spec.ts tests/e2e/stream-controls.spec.ts tests/e2e/stream.spec.ts tests/e2e/smoke.spec.ts
cd .. && git diff --check
```

## Checkpoints

- 2026-10-15: friction reproduced in the browser on an isolated build (table above). The plan was
  revised after an independent design review (counts accessibility, Tab, accept rules, unclosed
  quotes, honest counts, pinned project, case merging, hidden listbox).
- 2026-10-15: implemented on `claude/stream-next` in verified commits: span tokenizer, parser
  readings and the idempotent pinned-project strip (`fccec336`); Slice 1, caret-aware completion
  (`8308cd83`); Slice 2, interpretation notes and zero-result fixes (`08f3013f`); then this
  documentation.
  - Verified: `npm test` (66 files, 912 tests), `npm run check` (0 errors; the 68 existing Solid
    warnings unchanged), `npm run build`, `mvn -q test` (1086 tests, 0 failures, 74 skipped),
    `git diff --check`. `stream-compose.spec.ts` lints and typechecks; the packaged e2e suite was
    not run here, because it needs the exclusive :8799 runner.
  - A real Chromium drove an isolated build of the branch (temporary SQLite, optional systems
    off, port 18777) through each Acceptance case at 1440 and 390 px. It also replayed the
    byte-identical `smoke.spec.ts:61-91` and `stream-controls.spec.ts:145-182` flows, the Enter
    and Filter submissions of `stream-controls.spec.ts:105-112` and `stream.spec.ts:78-132`, and
    the `stream-compose.spec.ts` bodies. All passed.
  - Interpretations made while building:
    - A field-name edit replaces the typed name and its colon, so `|source:claude` plus `kind:`
      gives `kind:claude`; a bare word is replaced whole.
    - The keyboard highlight follows an option id, so values that arrive later do not move it.
    - `is:all` is not offered under My turns, which replaces the meaningful filter.
    - Notes also cover `is:` values other than `all`, valueless filters (`kind:`) and a trailing
      `NOT`.
    - The hidden `project_group` scope goes first when the visible query ends inside an unclosed
      quote, so the quote cannot swallow it.
    - Inherited object member names (`constructor:`) now read as unknown fields, as in Java.
  - Tests changed beyond the planned list: the pinned "Codex right now" view now keeps `last:2h`
    as typed, and the value-index mock returns sources for `source:`.
- 2026-10-15: an independent review approved the branch with no must-fix items. Every should-fix
  item was applied:
  - A trailing bare `NOT` negated whatever was appended after it, and both parsers read a negated
    `is:all` or `project_group:` as text. So "Include all events" narrowed the query, and a
    pinned project's scope dropped out of the request. A shared `appendQueryToken` in `query.ts`
    now puts the token first after an unclosed quote or a bare `NOT` (`982d9595`).
  - The stripped-project note came back for a later query that lost nothing, and after unpinning
    and pinning again. Its record now names the pinned project, and the next query or project
    change retires it (`c9e47add`).
  - Value-index lookups are cancelled when the page unmounts (`bbb931db`).
  - The did-you-mean acceptance example and its component test now use `kind:Hand last:7d`.
    The server's per-field counts keep free text, other filters and time bounds, so the old
    example gets no value fix against a real server. The standards and changelog wording now say
    so.
  - Verified: `npm test` (66 files, 920 tests), `npm run check` (0 errors; the same 68
    warnings), `npm run build` (reproduces the committed bundle), `mvn -q test` (1086 tests, 0
    failures, 74 skipped) and `git diff --check`. Each new test failed before its fix. The packaged
    e2e suite was not run.
- 2026-10-15 integration, after rebasing onto `main` with the macOS helper and Java test PRs:
  - **Packaged browser suite:** `npm run e2e`, 97 passed and 1 skipped (the opt-in showcase), including the 6
    `stream-compose.spec.ts` journeys at 1440 and 390 px.
  - **After behaviour**, from the same isolated fixture and probe scripts as the "Before" table:
    - Editing the middle token `kind:Dec|i source:claude` completes it in place: `kind:Decision source:claude`, caret
      13.
    - `project:"/tmp/…/harb` completes to the quoted path with a space.
    - `-kind:Obs` gives `-kind:Observation`.
    - `ki` offers `kind:` with help text, then counted values.
    - Tab accepts.
    - Session options show titles and last-seen ages, and the session chip shows the title.
    - `kind:Hand` returns 0 rows plus "Did you mean Handoff?" and a one-click fix (4 rows).
    - `knd:` and `last:7` are explained.
    - `kind:UserPromptSubmit` offers "Include all events" (16 rows).
    - An unclosed quote is flagged.
    - On a phone viewport, tapping a field then a value keeps focus.
