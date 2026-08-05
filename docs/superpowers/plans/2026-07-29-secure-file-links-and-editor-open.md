# Secure File Links + Open In Editor (Phase 1, Slice 3)

**Goal:** Turn Slice 1's raw `FileRef` presenter output into safe, useful file actions: open a
catalogued project file in Cursor at the recorded line, copy its path, and reveal it in Finder,
while failing closed for every path that is not inside a filesystem-verified project root.

**Spec:** `docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md`
§4.4, §6–§8, and §13 slice 3.

**Branch/base:** `stream-observatory-file-links` from clean local `main` at `9eab7de`. This slice is
local-only: commit it, deploy it to `:8766`, and do not push or open a PR.

## Observed baseline

The closest live user path was reproduced on `:8766` before implementation:

- a real `Read` event for
  `/Users/nathan/Developer/proj/opensource/t3code/packages/client-runtime/src/authorization/service.ts`
  renders an expanded headline button;
- invoking the real click handler writes that raw absolute path to the clipboard;
- it sends no request, opens no editor, changes no URL, and reports no success or failure;
- the presenter correctly retains the line (`offset: 40`) as `FileRef.line`.

Live service truth at reproduction time: launchd label `com.nathan.sba-agentic`, PID `89718`,
healthy `/api/status`, and the already-shipped Slice 2 bundle. The production service and database
must stay untouched until the final deploy.

## Safety contract

The existing `/api/projects` response is an observational catalog, not an authorization list. The
live catalog has 234 groups and includes `/`, `/Users/nathan`, and `__no_project__`; a naive
longest-prefix match would authorize nearly every absolute path on the machine.

This slice therefore adds a **code-scope projection** of the existing catalog. A scope is eligible
only when all of the following are true:

1. It came from a session-backed current catalog group (`ProjectSummary.scopes`, preserving that scope's opaque
   `projectKey` rather than alias-collapsing to a primary checkout).
2. Its path is absolute, exists as a directory, and is neither the filesystem root, the real user
   home, nor `__no_project__`.
3. It is at or beneath a filesystem-verified Git worktree root. Verification is filesystem-only:
   a `.git` directory or a valid `gitdir:` file with a readable `HEAD`. No `git` subprocess
   establishes authority.

The backend re-derives and revalidates that projection for every open/reveal request. A renderer
never supplies an absolute target to a mutating endpoint.

For a `CodeReference`:

- match `projectKey` exactly against the eligible projection;
- reject a blank or absolute `relativePath`, any `..` segment, or malformed path;
- resolve lexically against the exact matched scope and reject escape;
- require an existing, readable regular file;
- resolve symlinks with `toRealPath()` and require the canonical target to remain inside the exact
  matched scope; use its containing canonical Git root only as the editor workspace;
- validate one-based line/column bounds (column requires line);
- pass only the canonical target to a fixed argv launcher.

No endpoint accepts an executable, argument string, shell fragment, raw `file://` URL, or absolute
target from an event or browser request.

## Documented refinements to the design sketch

1. **Keep presenter `FileRef` values raw and pure.** Slice 1 deliberately deferred
   `FileRef → CodeReference` until click time. Presenter results are WeakMap-cached, so deriving
   catalog state inside a presenter would also go stale.
2. **Expose `GET /api/projects/code-scopes`.** The broad project catalog cannot safely drive
   client-side longest-prefix matching. This additive projection makes the approved derivation
   testable and lets unresolved paths render copy-only instead of looking clickable and failing
   later.
3. **Add `POST /api/reveal-in-finder`.** The spec promises reveal as an always-present fallback but
   defines no browser transport. A plain browser cannot safely reveal through `file://`; the narrow
   endpoint reuses the same `CodeReference` resolver and a fixed `/usr/bin/open -R` argv.
4. **Use the direct Cursor application CLI by default.** The spec's
   `/Users/nathan/.local/bin/cursor` is a PATH-scanning shim. The live launchd service has only
   `/usr/bin:/bin:/usr/sbin:/sbin`, so that shim exits with “No Cursor IDE installation found.”
   The verified production default is
   `/Applications/Cursor.app/Contents/Resources/app/bin/code`.
5. **Only a Cursor/VS Code-compatible CLI adapter ships in this slice.** The Homebrew `idea`
   wrapper uses `open -na` and can create a new IDE instance; it is not an acceptable
   reuse-the-owning-window adapter. Other editor adapters require separate verified argv contracts.

## REST contracts

### `GET /api/projects/code-scopes`

Success `200`:

```json
[
  {
    "projectKey": "opaque-catalog-scope-key",
    "root": "/absolute/catalogued/scope"
  }
]
```

The returned `root` is the exact path base represented by `projectKey`; the editor workspace may be
its containing verified Git root.

### `POST /api/open-in-editor`

Request:

```json
{
  "projectKey": "opaque-catalog-scope-key",
  "relativePath": "src/main/java/example.java",
  "line": 42,
  "column": 7,
  "commit": "optional informational value"
}
```

Success `200`:

```json
{"status":"opened"}
```

### `POST /api/reveal-in-finder`

Request is the same `CodeReference`. Success `200`:

```json
{"status":"revealed"}
```

Typed errors use the existing nested envelope:

```json
{"error":{"status":403,"type":"outside_project_root","message":"The file reference leaves its known project root."}}
```

Mappings:

- `400 invalid_reference` — missing/malformed fields or invalid line/column;
- `403 outside_project_root` — absolute relative path, traversal, lexical escape, or symlink escape;
- `404 file_missing` — missing, unreadable, or non-regular target;
- `409 project_unresolved` — unknown key, protected/broad/stale scope, or no verified Git root;
- `503 editor_disabled` — disabled, missing, non-executable, non-allowlisted, or failed editor;
- `503 reveal_unavailable` — Finder reveal is unavailable or fails.

Successful action responses never echo an absolute target.

## Implementation tasks

### 1. Lock the backend contract with failing tests

**Add:**

- `src/test/java/dev/nathan/sbaagentic/project/internal/application/CodeNavigationServiceTest.java`
- `src/test/java/dev/nathan/sbaagentic/project/internal/adapter/out/process/ProcessBuilderEditorLauncherTest.java`
- `src/test/java/dev/nathan/sbaagentic/project/internal/adapter/in/web/CodeNavigationControllerTest.java`

Cover:

- primary checkout and exact worktree/nested-scope success without alias collapse;
- root, home, no-project, stale, non-Git, and unknown-key rejection;
- `..`, absolute path, path-boundary collision, and symlink escape;
- missing/directory/unreadable targets;
- valid and out-of-range one-based line/column;
- disabled, relative, absent, non-executable, and non-allowlisted editor commands;
- process-start/non-zero failure;
- an existing filename containing spaces, quotes, semicolon, `$()`, and newline remains one argv
  element and creates no shell side effect;
- exact HTTP statuses, error types/messages, and success bodies.

All test repositories/databases are isolated temp files/directories. Tests use fake ports or
run-owned executable stubs and never open a real editor or Finder.

### 2. Add project-owned code navigation

**Add public project contracts:**

- `CodeReference`
- `CodeProjectScope`
- `CodeNavigationResult`
- `CodeNavigationOperations`
- `EditorProperties`

**Add project internals:**

- application service and typed exception/error code;
- code-scope/Git-root verifier;
- small launcher port;
- argv-only `ProcessBuilder` adapter;
- module-owned `@EnableConfigurationProperties(EditorProperties.class)` configuration;
- controller methods and a project-local typed exception handler.

Keep the feature inside the existing `project` module. Do not import `runner.process.ProcessRunner`
and do not add a new application module.

Editor launch is two fixed argv calls: open/focus the verified Git workspace, then use `-g` with the
canonical file and optional position. Redirect process output away from the server, bound how long
the CLI may report an immediate failure, and never kill a process that has successfully handed off
to the editor.

Finder reveal is one fixed argv call equivalent to:

```text
/usr/bin/open -R <canonical-target>
```

### 3. Add configuration and frozen API contracts

**Modify:**

- `src/main/resources/application.yml`
- `src/test/resources/contracts/rest-mappings.txt`
- `src/test/resources/contracts/rest-contract-matrix.json`

Configuration:

- `sba.editor.enabled` / `SBA_EDITOR_ENABLED` (default `true`);
- `sba.editor.command` / `SBA_EDITOR_COMMAND` (direct Cursor app CLI default);
- `sba.editor.allowlist` / `SBA_EDITOR_ALLOWLIST` (absolute code-compatible CLI paths only);
- bounded launch timeout.

Run the REST snapshot and module/architecture tests before touching the frontend.

### 4. Build the frontend resolution and action layer

**Add:**

- `frontend/src/lib/codeReferences.ts`
- `frontend/src/lib/codeReferences.test.ts`
- `frontend/src/lib/codeNavigation.tsx`
- `frontend/src/components/events/FileReferenceActions.tsx`
- focused component tests

**Modify:**

- `frontend/src/App.tsx`
- `frontend/src/lib/api.ts`
- `frontend/src/components/events/InlineSpans.tsx`
- `frontend/src/components/events/blocks/BlockView.tsx`
- `frontend/src/pages/SearchPage.tsx`
- relevant tests and `frontend/src/theme.css`

Behavior:

1. App loads the eligible code-scope projection once and exposes it through context.
2. A pure boundary-aware longest-prefix resolver maps absolute `FileRef.path` to
   `{projectKey, relativePath, line}`. It preserves the exact matched scope key.
3. Resolved path labels open in the editor. Adjacent compact actions copy the raw path and reveal
   the resolved reference.
4. Unresolved/relative/tilde/malformed paths are never open links. Copy remains available; reveal
   is disabled with an honest “not in a known project” state.
5. Clipboard absence/rejection and every typed backend failure render through an `aria-live`
   status instead of silently disappearing.
6. Parsed apply-patch paths (including move targets) reuse the same action component.
7. Search results stop wrapping `EventRenderer` in an anchor; use a non-interactive result
   container plus a separate explicit session link so file buttons are valid HTML.

Unit tests cover longest-prefix/path boundaries, exact worktree keys, protected/unmatched paths,
spaces/Unicode, open/reveal requests containing no absolute path, clipboard success/failure,
typed error text, patch paths, and search-row non-navigation.

### 5. Prove the packaged path with a fake editor

**Modify:**

- `frontend/playwright.config.ts`
- `frontend/src/e2e/e2ePreflight.mjs` and tests as needed
- `frontend/tests/e2e/project-fixture.ts`
- `frontend/tests/e2e/stream.spec.ts`

The run-owned preflight creates:

- a real source file under the isolated symlink-backed project fixture;
- a minimally valid `.git` marker;
- an executable fake editor inside the E2E temp directory;
- an argv log inside the same directory.

The isolated Java server receives the fake command and exact allowlist through environment
variables. The fake writes discrete argv fields and never evaluates them.

Playwright proves:

- expanding a real presenter row and clicking its file path reports success;
- the fake receives the verified workspace and canonical file/line as separate argv;
- a shell-looking existing filename remains data and causes no side effect;
- an outside-root or unknown reference renders copy-only or a typed failure and invokes nothing;
- production port, database identity, and real desktop applications remain untouched.

### 6. Update owned docs and the committed bundle

**Modify:**

- `README.md`
- `docs/architecture.md`
- `NEXT.md` only after the slice is fully verified
- generated `src/main/resources/static/**` via the frontend build, never by hand

Document the transport-neutral `CodeReference`, code-scope projection, fail-closed rules, Cursor
configuration, browser behavior, and typed failures. Keep public copy machine-neutral except for
clearly labelled macOS/Cursor defaults.

### 7. Verification and live closeout

Run the smallest focused backend and frontend tests first, then:

```sh
mvn -q test
(cd frontend && npm run test && npm run build)
mvn -q -Pfrontend -DskipTests package
(cd frontend && npm run e2e)
git diff --check
```

Because packaging overwrites the jar used by launchd, finish with:

```sh
./scripts/deploy-local.sh
curl -fsS http://127.0.0.1:8766/api/status | jq
launchctl print gui/$(id -u)/com.nathan.sba-agentic
```

Live negative proof:

- `/api/projects/code-scopes` includes verified repo/worktree scopes but excludes `/`, the user
  home, no-project, stale, and non-Git scopes;
- unknown project key returns typed `409 project_unresolved`;
- traversal returns typed `403 outside_project_root`;
- missing file returns typed `404 file_missing`;
- no negative request starts Cursor or Finder.

Live positive proof uses the reproduced T3 Code `Read` event on `:8766`. Click the path once, verify
the UI reports `opened`, then inspect Cursor through the desktop UI to prove:

- the T3 Code repository owns the selected editor window;
- `packages/client-runtime/src/authorization/service.ts` is open;
- the recorded line 40 is selected or centered.

Do not use a shell-looking filename in the real live smoke; adversarial inputs belong only in the
fake-launcher tests.

Finally:

1. inspect the exact staged paths and outgoing local commit;
2. commit with Nathan as sole author and no attribution trailer;
3. do not push or open a PR;
4. capture a Black Box Handoff with client session id/transcript pointer, branch, commit, clean/dirty
   state, files/areas, all verification, live PID/bundle/API/UI proof, what was not pushed, open
   loops, and Slice 4 as the next action.

## Observed verification

Implementation and review closed every planned security boundary plus four frontend gaps found by
independent review: catalog loading/error/refresh state, action accessibility, the adversarial
packaged filename path, and Finder/clipboard failure coverage. Final backend re-review found no
high/medium issue; its one low residual was closed by explicitly stopping and awaiting launcher
parent/child processes after timeout or interruption, with PID-level regression coverage.

Final gates:

- targeted backend resolver/controller/launcher/config/contract/module tests green;
- targeted frontend tests green;
- full `mvn -q test` green;
- frontend **316/316** tests across 39 files and production build green;
- final clean packaged Playwright run **24/24** green in 2.1 minutes, including the discrete-argv
  shell-shaped filename and no-side-effect sentinel;
- `git diff --check` green.

An earlier full browser run had one unrelated live-projection miss: the full-auto task detail had
already recorded `in_progress → done`, but the Board column remained stale until timeout. The exact
scenario passed on immediate isolated rerun in 19.1 seconds and again in the final clean 24/24 run.

Live closeout on `:8766`:

- deployed launchd PID `27496`; `/api/status` healthy with Elasticsearch and local AI reachable;
- served `index-XHqOdHft.js` and `index-D-8V2GB4.css`;
- exposed 188 eligible code scopes, including the exact T3 Code checkout key and excluding `/`,
  `/Users/nathan`, and `__no_project__`;
- returned typed `409 project_unresolved`, `403 outside_project_root` for traversal and absolute
  input, and `404 file_missing`, without changing Cursor PID `12766` or Finder PID `645`;
- the real Stream action for
  `packages/client-runtime/src/authorization/service.ts:40` reported `Opened in editor.`;
- Cursor's native live status reported `Window (service.ts — t3code)` and the `t3code` workspace;
  read-only Cursor editor state recorded that exact file as the most-recent editor with cursor
  position line 40, column 1.

Desktop accessibility inspection timed out by both display name and bundle identifier, and the
screen-capture API lacked permission. The positive editor proof therefore comes from the real
browser action plus Cursor's native runtime status and editor state, not a screenshot.

## Acceptance

- Only filesystem-verified catalogued repo/worktree scopes become file links.
- Worktree paths stay in their worktree rather than alias-collapsing to the primary checkout.
- Open/reveal never receive an absolute renderer target and never invoke a shell.
- Traversal, symlink escape, broad roots, unknown projects, stale files, invalid positions, and
  editor misconfiguration fail with honest typed UI states.
- Copy path remains usable even when no secure code reference exists.
- The packaged E2E path proves discrete argv with a fake editor.
- The real `:8766` path opens the expected T3 Code file at line 40 in Cursor.
- Full gates pass, live service is healthy, the branch is committed locally, and nothing is pushed.
