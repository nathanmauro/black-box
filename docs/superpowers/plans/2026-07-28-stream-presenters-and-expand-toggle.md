# Stream Presenters + Expand Toggle (Phase 1, Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Black Box stream typed, tool-aware rendering (bash, edit, write, read, apply_patch), client-side diffs, and a persisted Collapsed/Expanded toggle — plus the free deletions of `/stats` and `/overview`.

**Architecture:** A registry of pure presenter functions in `frontend/src/lib/presenters/` maps raw `AgentEvent`s to a typed `Presentation` (headline spans + detail blocks). Components become dumb renderers of a fixed set of block components; heavy content (diffs, outputs) mounts lazily behind native `<details>` with size labels. The existing `ToolPayload` generic renderer is kept as the fallback block, so no tool ever renders worse than today. Expand state is a global density mode plus a per-row *exceptions* set, so live SSE rows follow the mode with zero bookkeeping.

**Tech Stack:** SolidJS 1.9 + @solidjs/router (only npm deps — unchanged), Vitest + @solidjs/testing-library (jsdom), Playwright e2e, Spring Boot backend (one 2-line controller edit), hand-rolled LCS line diff (no new dependency).

**Spec:** `docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md` §4.1–§4.3, §4.5, slice 1 of §13. Read it before starting.

## Global Constraints

- **No new npm dependency** (spec §11). The diff is hand-rolled; no syntax-highlighting library — token-level highlighting is explicitly out of Phase 1 (§4.3).
- **No icons or emoji anywhere** in stream UI; identity is colour dots and text pills (§4.1).
- **Colour only via CSS custom properties in `theme.css`**; presenters express only the `Tone` vocabulary; no component hard-codes a colour (§4.1).
- **Presenters never throw** — any parse failure degrades to the generic fallback presentation (§7).
- **Diffs are computed only when their block is opened**, memoized per event id; unopened `<details>` content is never constructed (§4.3, §4.5).
- **Every collapsed heavy block carries a size label**, e.g. `Output (18,003 chars)` (§4.1).
- **Perf budget (§9):** no first-paint regression vs. today's collapsed baseline; expand-all toggle under 100ms at 500 rows; measured before and after, numbers recorded.
- **Never `git add -A`** — stage exact paths. Exception: `git add -A src/main/resources/static` after a bundle rebuild (built assets are committed).
- **Commits show Nathan as sole author** — no co-author or generated-by attribution lines, ever. Match repo message style: Title Case sentence (e.g. `Tighten Phase 1 After Adversarial Review`).
- **Jar-swap gotcha:** any `mvn package` (including the Playwright webServer) overwrites the jar the live `:8766` launchd service runs from → run `scripts/deploy-local.sh` at the end; `launchctl kickstart -k` if 500s persist.
- **Playwright waits on `domcontentloaded`, never `networkidle`** — the SSE connection never idles.
- **Never point a second application at the live DB** (`sba-agentic.db`, ~3.2GB); read via `sqlite3 "file:sba-agentic.db?mode=ro"` and `.backup` snapshots only.
- Frontend layering: **`frontend/src/lib/**` must never import from `frontend/src/components/**`** (presenters are pure; components consume presenters, never the reverse).
- Full verification gate (run in Task 16): `mvn -q test && (cd frontend && npm run test && npm run build) && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)`.

## Documented deviations from the spec's type sketch

Deliberate, reviewed refinements — later slices should follow these, not §4.1's sketch:

1. **`FileRef { path, line? }` instead of `CodeReference` in presenter output.** `CodeReference { projectKey, relativePath, … }` requires the project catalog to resolve `projectKey`, which is slice 3's server-side job (§4.4 step 1: *never trust a renderer-supplied absolute path*). Presenters extract the raw stored path (`FileRef`); slice 3 maps `FileRef → CodeReference` at click time. Copy-path (the always-present fallback) needs the raw path anyway.
2. **`diff` blocks carry inputs (`oldText`/`newText`), not computed `hunks`.** Computing hunks at presentation time would violate the §4.3 cost rule ("diffs are computed only when their block is opened"). The `DiffBlock` component computes hunks lazily on first open, memoized per `eventId:blockIndex`.
3. **`apply_patch` gets its own `patch` block kind** carrying the raw command plus cheaply-scanned file stubs (headline needs the file names); full hunk parsing is deferred to block open.
4. **`plan` and `markdown` block kinds are declared now but rendered generically** (as text/JSON) until slice 5 ships their presenters and components.
5. **Interactive headline spans render only inside the expanded event card.** The collapsed `StreamRow` is a `<button>`; nesting `fileLink` buttons inside it would be invalid HTML. The collapsed row keeps its plain-text headline via `eventHeadline()`.

---

### Task 1: Capture the perf baseline (before any change)

**Files:**
- Create: `docs/superpowers/plans/2026-07-28-stream-perf-notes.md`
- Create (NOT committed — deleted in Task 16): `frontend/measure-stream.mjs` — it must live inside `frontend/` because Node ESM resolves the bare `@playwright/test` specifier from the script file's own location, not the cwd

**Interfaces:**
- Consumes: the live app at `http://127.0.0.1:8766` (read-only page loads; browsing does not mutate).
- Produces: baseline numbers that Task 16 compares against. Nothing imports this.

- [ ] **Step 1: Confirm the live service is healthy**

Run: `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8766/api/health || curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8766/`
Expected: `200`. If 500, run `launchctl kickstart -k gui/501/$(launchctl list | grep -o 'sba[^ ]*' | head -1)` per `NEXT.md` and re-check.

- [ ] **Step 2: Write the measurement script** at `frontend/measure-stream.mjs` (it uses the frontend's own Playwright install; no new dependency; never staged for commit):

```js
// frontend/measure-stream.mjs — run with: cd frontend && node measure-stream.mjs
// Lives inside frontend/ so the bare @playwright/test import resolves from this file's path.
import { chromium } from "@playwright/test";

const BASE = process.env.BB_URL || "http://127.0.0.1:8766";
const browser = await chromium.launch();
const page = await browser.newPage();

const t0 = Date.now();
await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
await page.locator(".stream-row").first().waitFor();
const firstRowMs = Date.now() - t0;

// Grow the feed to MAX_ROWS (500) via Load more.
for (let i = 0; i < 4; i += 1) {
  const btn = page.getByRole("button", { name: /Load more/ });
  if (!(await btn.isVisible().catch(() => false))) break;
  await btn.click();
  await page.waitForTimeout(800);
}
const rows = await page.locator(".stream-row").count();

// Single-row expand interaction time (the only expansion that exists pre-change).
const expandStart = Date.now();
await page.locator(".stream-row").first().click();
await page.locator(".stream-row-expanded").first().waitFor();
const expandOneMs = Date.now() - expandStart;

console.log(JSON.stringify({ firstRowMs, rows, expandOneMs }, null, 2));
await browser.close();
```

- [ ] **Step 3: Run it 3 times** (`cd frontend && node measure-stream.mjs`), record the median of each metric. Do not stage or commit `frontend/measure-stream.mjs` — it is a throwaway measurement tool, removed in Task 16.

- [ ] **Step 4: Write `docs/superpowers/plans/2026-07-28-stream-perf-notes.md`:**

```markdown
# Stream perf notes — slice 1 (spec §9 budget)

Method: Playwright chromium against the live :8766 service, `domcontentloaded` +
first `.stream-row` visible; feed grown to MAX_ROWS via Load more; median of 3 runs.

## Baseline (before presenters/expand-toggle), 2026-07-28
- first stream row visible: <N> ms
- rows loaded: <N>
- single-row expand: <N> ms

## After slice 1 (filled in by Task 16)
- first stream row visible: <N> ms (budget: no regression)
- expand-all toggle at 500 rows: <N> ms (budget: < 100ms)
- single-row expand: <N> ms
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-07-28-stream-perf-notes.md
git commit -m "Record The Stream Perf Baseline Before Slice 1"
```

---

### Task 2: Delete `/stats` and `/overview`

The spec's "free deletions" (§4.10, slice 1): both pages are orphaned (no nav link; only `index.tsx` imports them).

**Files:**
- Delete: `frontend/src/pages/StatsPage.tsx`, `frontend/src/pages/OverviewPage.tsx`, `frontend/src/pages/__tests__/StatsPage.test.tsx`
- Modify: `frontend/src/index.tsx` (imports at lines 7 and 11; routes at lines 26 and 33)
- Modify: `frontend/src/lib/api.ts` (remove the StatsPage-only client: `getDashboardStats` and the `DashboardStats`/`DashboardBreakdown` types)
- Modify: `src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingController.java:15`
- Modify: `frontend/tests/e2e/smoke.spec.ts` (line ~114 `/overview` SSE test; line ~133 stats test)

⚠️ The backend `GET /api/stats` endpoint (`SystemController.java`) and its test stay — spec §6: "All existing endpoints are unchanged." Only the frontend consumers die.

**Interfaces:**
- Consumes: nothing.
- Produces: nothing later tasks depend on; keeps `/graph` untouched (parked in slice 6, not here).

- [ ] **Step 1: Remove the routes and imports from `frontend/src/index.tsx`**

Delete these four lines (leaving all other routes, including `/graph`, untouched):

```tsx
import OverviewPage from "./pages/OverviewPage";
import StatsPage from "./pages/StatsPage";
      <Route path="/overview" component={OverviewPage} />
      <Route path="/stats" component={StatsPage} />
```

- [ ] **Step 2: Delete the page files, the stats test, and the orphaned API client**

```bash
git rm frontend/src/pages/StatsPage.tsx frontend/src/pages/OverviewPage.tsx frontend/src/pages/__tests__/StatsPage.test.tsx
```

Then in `frontend/src/lib/api.ts`, delete `getDashboardStats` (the function calling `getJson("/api/stats")`, around line 578) and the `DashboardStats` / `DashboardBreakdown` types it returns — their only importers were the two files just deleted. Verify that claim before deleting: `grep -rn "getDashboardStats\|DashboardStats\|DashboardBreakdown" frontend/src` must show no remaining importers afterward.

- [ ] **Step 3: Shrink the SPA forwarding whitelist**

In `SpaForwardingController.java`, replace the `@GetMapping` value with:

```java
    @GetMapping(value = {"/sessions", "/sessions/**", "/search", "/recall", "/projects", "/projects/**", "/graph", "/board"})
```

(Removes `/overview` and `/stats`; a hard refresh on either now 404s — correct, the routes are gone.)

- [ ] **Step 4: Update the two smoke e2e tests**

In `frontend/tests/e2e/smoke.spec.ts`:
- In `"live feed receives a newly ingested event over SSE"`: change `await page.goto("/overview");` to `await page.goto("/");` and change the locator `.live-inline--live` (which lived on OverviewPage) to `.live-pill--live` (the App header pill, present on every page).
- Delete the entire `"stats shows headline totals and activity breakdowns"` test.

- [ ] **Step 5: Verify no dangling references**

Run: `grep -rn "StatsPage\|OverviewPage\|/overview\|/stats" frontend/src frontend/tests src/main/java src/test/java --include="*.ts" --include="*.tsx" --include="*.java"`
Expected: **zero hits under `frontend/`**. Exactly two `/stats` hits survive on the backend and MUST be left alone: the `GET /api/stats` endpoint in `SystemController.java` and its MockMvc test in `AgenticControllerTest.java` (spec §6 keeps all API endpoints). Any other hit is a dangling reference — read it and fix it.

- [ ] **Step 6: Run the affected suites**

Run: `cd frontend && npm run test && npm run build` and `mvn -q test -Dtest=SpaForwardingTest`
Expected: all green (SpaForwardingTest has no stats/overview cases; it must still pass).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/index.tsx frontend/src/lib/api.ts frontend/tests/e2e/smoke.spec.ts src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/SpaForwardingController.java
git commit -m "Delete The Orphaned Stats And Overview Pages"
```

(The `git rm` in Step 2 already staged the deletions.)

---

### Task 3: Extract payload parsing into `lib/payload.ts`

Presenters (pure, in `lib/`) need the parsing helpers that currently live inside `components/events/ToolPayload.tsx` and `components/events/eventData.ts`. Move them; keep components importing the same names.

**Files:**
- Create: `frontend/src/lib/payload.ts`
- Create: `frontend/src/lib/payload.test.ts`
- Modify: `frontend/src/components/events/ToolPayload.tsx` (delete moved functions, import them)
- Modify: `frontend/src/components/events/eventData.ts` (delete moved functions, re-export from lib)

(`EventRow.tsx:9-10` is deliberately NOT modified — its imports keep resolving through the re-exports below; verify, don't assume.)

**Interfaces:**
- Consumes: nothing new.
- Produces (all later presenter tasks import these from `../payload`):
  - `parsePayload(raw: string | null | undefined): unknown | null` — two-pass JSON deserialization (moved verbatim from ToolPayload.tsx:128-141).
  - `payloadText(raw: string | null | undefined): string | null` — first string of `output|stdout|result|content` (moved verbatim from ToolPayload.tsx:143-151).
  - `parseToolResult(raw: string | null | undefined): unknown | null` — Codex `Exit code:\nWall time:\nOutput:` extraction (moved from ToolPayload.tsx:153-163, now **exported**).
  - `looksLikeJson(value: string | null | undefined): boolean` and `parseJsonObject(value: string | null | undefined): Record<string, unknown> | null` (moved verbatim from eventData.ts).

- [ ] **Step 1: Write the failing test** `frontend/src/lib/payload.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { looksLikeJson, parseJsonObject, parsePayload, parseToolResult, payloadText } from "./payload";

describe("parsePayload", () => {
  it("unwraps double-serialized JSON in two passes", () => {
    expect(parsePayload(JSON.stringify(JSON.stringify({ command: "ls" })))).toEqual({ command: "ls" });
  });
  it("returns raw text for non-JSON strings", () => {
    expect(parsePayload("plain text")).toBe("plain text");
  });
  it("returns null for empty input", () => {
    expect(parsePayload("  ")).toBeNull();
    expect(parsePayload(null)).toBeNull();
  });
});

describe("parseToolResult", () => {
  it("extracts the Codex exit/wall/output result format into structured fields", () => {
    const raw = JSON.stringify("Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed");
    expect(parseToolResult(raw)).toEqual({ exit_code: 0, wall_time: "1.2 seconds", output: "42 tests passed" });
  });
  it("passes through strings that do not match the format", () => {
    expect(parseToolResult(JSON.stringify("just output"))).toBe("just output");
  });
});

describe("payloadText", () => {
  it("prefers the output field of structured results", () => {
    expect(payloadText(JSON.stringify({ output: "hello", exit_code: 0 }))).toBe("hello");
  });
});

describe("parseJsonObject", () => {
  it("parses objects and rejects arrays", () => {
    expect(parseJsonObject('{"a":1}')).toEqual({ a: 1 });
    expect(parseJsonObject("[1]")).toBeNull();
  });
});

describe("looksLikeJson", () => {
  it("detects object and array shapes", () => {
    expect(looksLikeJson('{"a":1}')).toBe(true);
    expect(looksLikeJson("plain")).toBe(false);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/payload.test.ts`
Expected: FAIL — `Cannot find module './payload'` (or equivalent resolve error).

- [ ] **Step 3: Create `frontend/src/lib/payload.ts`** by moving these functions **verbatim** (cut, don't copy — the originals are deleted in Step 4):
  - From `ToolPayload.tsx`: `parsePayload`, `payloadText`, `parseToolResult` (add `export`), `looksSerialized`, `numericOrText` (both stay private helpers here).
  - From `eventData.ts`: `looksLikeJson`, `parseJsonObject`.
  - **`isRecord` is the one exception — copy, don't cut.** `payloadText` calls it (`ToolPayload.tsx:146`), so `payload.ts` needs a private copy; but `orderedEntries` (`ToolPayload.tsx:117`) also calls it, so `ToolPayload.tsx` keeps its own. Two 3-line private copies beat a cross-layer export.

- [ ] **Step 4: Re-point the components**
  - `ToolPayload.tsx`: delete the moved functions; add `import { parsePayload, parseToolResult } from "../../lib/payload";` and re-export for existing importers: `export { parsePayload, payloadText } from "../../lib/payload";`
  - `eventData.ts`: delete the moved two functions; then add an **import plus re-export** — `export ... from` alone creates no local binding, and `parseMetadata` (line 3 of the file) still calls `parseJsonObject` locally, so it must be:

```ts
import { looksLikeJson, parseJsonObject } from "../../lib/payload";
export { looksLikeJson, parseJsonObject };
```
  - `EventRow.tsx` lines 9–10 keep working unchanged thanks to the re-exports — verify, don't assume.

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npm run test`
Expected: PASS — the moved code is verbatim, so `EventCards.test.tsx` and every other consumer stays green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/payload.ts frontend/src/lib/payload.test.ts frontend/src/components/events/ToolPayload.tsx frontend/src/components/events/eventData.ts
git commit -m "Extract Payload Parsing Into A Lib Module"
```

---

### Task 4: Line diff engine (`lib/diff.ts`)

Hand-rolled LCS line diff with common prefix/suffix trimming and a cell budget that degrades to whole-block replace — no new dependency (§4.3).

**Files:**
- Create: `frontend/src/lib/diff.ts`
- Create: `frontend/src/lib/diff.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces (Tasks 5, 10, 12 import these):
  - `type DiffLineKind = "context" | "add" | "del"`
  - `type DiffLine = { kind: DiffLineKind; text: string; oldLine: number | null; newLine: number | null }`
  - `type Hunk = { lines: DiffLine[] }`
  - `diffLines(oldText: string, newText: string, context?: number): Hunk[]` — `[]` when texts are equal.
  - `allAdditions(text: string): Hunk[]` — one hunk, every line `add` (Write presenter).
  - `memoizedDiffLines(key: string, oldText: string, newText: string): Hunk[]` — LRU-ish cache (200 entries) keyed by `eventId:blockIndex`.

- [ ] **Step 1: Write the failing test** `frontend/src/lib/diff.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { allAdditions, diffLines, memoizedDiffLines } from "./diff";

const flat = (hunks: ReturnType<typeof diffLines>) =>
  hunks.flatMap((hunk) => hunk.lines).map((line) => `${line.kind[0]}:${line.text}`);

describe("diffLines", () => {
  it("returns no hunks for identical text", () => {
    expect(diffLines("a\nb", "a\nb")).toEqual([]);
  });

  it("diffs a one-line change with surrounding context", () => {
    const hunks = diffLines("const a = 1;\nconst b = 2;\nconst c = 3;", "const a = 1;\nconst b = 9;\nconst c = 3;");
    expect(flat(hunks)).toEqual(["c:const a = 1;", "d:const b = 2;", "a:const b = 9;", "c:const c = 3;"]);
  });

  it("numbers old and new lines correctly", () => {
    const [hunk] = diffLines("keep\nold", "keep\nnew");
    const del = hunk.lines.find((line) => line.kind === "del");
    const add = hunk.lines.find((line) => line.kind === "add");
    expect(del).toMatchObject({ oldLine: 2, newLine: null });
    expect(add).toMatchObject({ oldLine: null, newLine: 2 });
  });

  it("limits context and splits distant changes into separate hunks", () => {
    const oldText = ["x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10"].join("\n");
    const newText = ["CHANGED0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "CHANGED10"].join("\n");
    const hunks = diffLines(oldText, newText, 3);
    expect(hunks).toHaveLength(2);
  });

  it("degrades huge inputs to whole-block replace instead of hanging", () => {
    const oldText = Array.from({ length: 3000 }, (_, index) => `left ${index}`).join("\n");
    const newText = Array.from({ length: 3000 }, (_, index) => `right ${index}`).join("\n");
    const started = Date.now();
    const hunks = diffLines(oldText, newText);
    expect(Date.now() - started).toBeLessThan(2000);
    expect(hunks.length).toBeGreaterThan(0);
  });

  it("handles empty old text (pure addition)", () => {
    expect(flat(diffLines("", "a\nb"))).toEqual(["a:a", "a:b"]);
  });
});

describe("allAdditions", () => {
  it("renders every line as an add", () => {
    expect(flat(allAdditions("a\nb"))).toEqual(["a:a", "a:b"]);
  });
});

describe("memoizedDiffLines", () => {
  it("returns the identical array for a repeated key", () => {
    const first = memoizedDiffLines("evt:0", "a", "b");
    expect(memoizedDiffLines("evt:0", "a", "b")).toBe(first);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/diff.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `frontend/src/lib/diff.ts`:**

```ts
export type DiffLineKind = "context" | "add" | "del";

export type DiffLine = {
  kind: DiffLineKind;
  text: string;
  oldLine: number | null;
  newLine: number | null;
};

export type Hunk = { lines: DiffLine[] };

/** Above this many DP cells the diff degrades to whole-block replace (perf guard). */
const LCS_CELL_BUDGET = 2_000_000;
const CACHE_MAX = 200;

export function diffLines(oldText: string, newText: string, context = 3): Hunk[] {
  if (oldText === newText) return [];
  const oldLines = splitLines(oldText);
  const newLines = splitLines(newText);

  let prefix = 0;
  while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix] === newLines[prefix]) prefix += 1;
  let suffix = 0;
  while (
    suffix < oldLines.length - prefix
    && suffix < newLines.length - prefix
    && oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  ) suffix += 1;

  const ops: DiffLine[] = [];
  for (let index = 0; index < prefix; index += 1) {
    ops.push({ kind: "context", text: oldLines[index], oldLine: index + 1, newLine: index + 1 });
  }
  ops.push(...middleOps(oldLines.slice(prefix, oldLines.length - suffix), newLines.slice(prefix, newLines.length - suffix), prefix));
  for (let index = 0; index < suffix; index += 1) {
    const oldLine = oldLines.length - suffix + index + 1;
    const newLine = newLines.length - suffix + index + 1;
    ops.push({ kind: "context", text: oldLines[oldLine - 1], oldLine, newLine });
  }
  return groupIntoHunks(ops, context);
}

export function allAdditions(text: string): Hunk[] {
  const lines = splitLines(text);
  if (!lines.length) return [];
  return [{ lines: lines.map((line, index) => ({ kind: "add" as const, text: line, oldLine: null, newLine: index + 1 })) }];
}

const cache = new Map<string, Hunk[]>();

export function memoizedDiffLines(key: string, oldText: string, newText: string): Hunk[] {
  const hit = cache.get(key);
  if (hit) return hit;
  const hunks = diffLines(oldText, newText);
  if (cache.size >= CACHE_MAX) {
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) cache.delete(oldest);
  }
  cache.set(key, hunks);
  return hunks;
}

function splitLines(text: string): string[] {
  if (text === "") return [];
  return text.split(/\r?\n/);
}

function middleOps(oldMid: string[], newMid: string[], offset: number): DiffLine[] {
  const ops: DiffLine[] = [];
  if (!oldMid.length && !newMid.length) return ops;
  const overBudget = oldMid.length * newMid.length > LCS_CELL_BUDGET;
  if (overBudget || !oldMid.length || !newMid.length) {
    oldMid.forEach((text, index) => ops.push({ kind: "del", text, oldLine: offset + index + 1, newLine: null }));
    newMid.forEach((text, index) => ops.push({ kind: "add", text, oldLine: null, newLine: offset + index + 1 }));
    return ops;
  }

  const cols = newMid.length + 1;
  const table = new Uint32Array((oldMid.length + 1) * cols);
  for (let row = oldMid.length - 1; row >= 0; row -= 1) {
    for (let col = newMid.length - 1; col >= 0; col -= 1) {
      table[row * cols + col] = oldMid[row] === newMid[col]
        ? table[(row + 1) * cols + col + 1] + 1
        : Math.max(table[(row + 1) * cols + col], table[row * cols + col + 1]);
    }
  }

  let row = 0;
  let col = 0;
  while (row < oldMid.length && col < newMid.length) {
    if (oldMid[row] === newMid[col]) {
      ops.push({ kind: "context", text: oldMid[row], oldLine: offset + row + 1, newLine: offset + col + 1 });
      row += 1;
      col += 1;
    } else if (table[(row + 1) * cols + col] >= table[row * cols + col + 1]) {
      ops.push({ kind: "del", text: oldMid[row], oldLine: offset + row + 1, newLine: null });
      row += 1;
    } else {
      ops.push({ kind: "add", text: newMid[col], oldLine: null, newLine: offset + col + 1 });
      col += 1;
    }
  }
  while (row < oldMid.length) {
    ops.push({ kind: "del", text: oldMid[row], oldLine: offset + row + 1, newLine: null });
    row += 1;
  }
  while (col < newMid.length) {
    ops.push({ kind: "add", text: newMid[col], oldLine: null, newLine: offset + col + 1 });
    col += 1;
  }
  return ops;
}

function groupIntoHunks(ops: DiffLine[], context: number): Hunk[] {
  if (!ops.some((op) => op.kind !== "context")) return [];
  const keep = new Array<boolean>(ops.length).fill(false);
  ops.forEach((op, index) => {
    if (op.kind === "context") return;
    const from = Math.max(0, index - context);
    const to = Math.min(ops.length - 1, index + context);
    for (let mark = from; mark <= to; mark += 1) keep[mark] = true;
  });
  const hunks: Hunk[] = [];
  let current: Hunk | null = null;
  ops.forEach((op, index) => {
    if (!keep[index]) {
      current = null;
      return;
    }
    if (!current) {
      current = { lines: [] };
      hunks.push(current);
    }
    current.lines.push(op);
  });
  return hunks;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/diff.test.ts`
Expected: PASS, all cases.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/diff.ts frontend/src/lib/diff.test.ts
git commit -m "Add A Hand-Rolled LCS Line Diff Engine"
```

---

### Task 5: `apply_patch` parser (`lib/patch.ts`)

The stored `command` is already a patch (`*** Begin Patch` / `*** Update File:` / `@@` hunks). Parse it directly; malformed input degrades, never throws (§4.3, §7).

**Files:**
- Create: `frontend/src/lib/patch.ts`
- Create: `frontend/src/lib/patch.test.ts`

**Interfaces:**
- Consumes: `DiffLine`, `Hunk` types from `./diff` (Task 4).
- Produces (Tasks 11 and 12 import these):
  - `type PatchOp = "update" | "add" | "delete"`
  - `type PatchFileStub = { op: PatchOp; path: string; movedTo: string | null }`
  - `type PatchFile = PatchFileStub & { hunks: Hunk[] }`
  - `patchFileStubs(command: string): PatchFileStub[]` — cheap header-only scan for headlines.
  - `parseApplyPatch(command: string): PatchFile[] | null` — full parse; `null` when malformed (caller falls back to raw text).

- [ ] **Step 1: Write the failing test** `frontend/src/lib/patch.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { parseApplyPatch, patchFileStubs } from "./patch";

const UPDATE_PATCH = [
  "*** Begin Patch",
  "*** Update File: /Users/nathan/Developer/proj/sba-agentic/README.md",
  "@@",
  " intro line",
  "-old line",
  "+new line",
  "*** End Patch",
].join("\n");

const MULTI_PATCH = [
  "*** Begin Patch",
  "*** Add File: /tmp/a.txt",
  "+hello",
  "*** Delete File: /tmp/b.txt",
  "*** End Patch",
].join("\n");

describe("patchFileStubs", () => {
  it("lists file operations without parsing hunks", () => {
    expect(patchFileStubs(MULTI_PATCH)).toEqual([
      { op: "add", path: "/tmp/a.txt", movedTo: null },
      { op: "delete", path: "/tmp/b.txt", movedTo: null },
    ]);
  });
});

describe("parseApplyPatch", () => {
  it("parses an update patch into hunks with add/del/context kinds", () => {
    const files = parseApplyPatch(UPDATE_PATCH);
    expect(files).toHaveLength(1);
    expect(files![0]).toMatchObject({ op: "update", path: "/Users/nathan/Developer/proj/sba-agentic/README.md" });
    const kinds = files![0].hunks[0].lines.map((line) => `${line.kind}:${line.text}`);
    expect(kinds).toEqual(["context:intro line", "del:old line", "add:new line"]);
  });

  it("parses add-file bodies as additions", () => {
    const files = parseApplyPatch(MULTI_PATCH);
    expect(files![0].hunks[0].lines).toEqual([{ kind: "add", text: "hello", oldLine: null, newLine: null }]);
    expect(files![1].hunks).toEqual([]);
  });

  it("captures Move to targets", () => {
    const moved = parseApplyPatch("*** Begin Patch\n*** Update File: /tmp/old.txt\n*** Move to: /tmp/new.txt\n@@\n+x\n*** End Patch");
    expect(moved![0].movedTo).toBe("/tmp/new.txt");
  });

  it("returns null for content that is not a patch", () => {
    expect(parseApplyPatch("just some text")).toBeNull();
    expect(parseApplyPatch("*** Begin Patch\ngarbage before any file header\n*** End Patch")).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/patch.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `frontend/src/lib/patch.ts`:**

```ts
import type { DiffLine, Hunk } from "./diff";

export type PatchOp = "update" | "add" | "delete";

export type PatchFileStub = { op: PatchOp; path: string; movedTo: string | null };

export type PatchFile = PatchFileStub & { hunks: Hunk[] };

const FILE_HEADER = /^\*\*\*\s+(Update|Add|Delete)\s+File:\s*(.+?)\s*$/;
const MOVE_HEADER = /^\*\*\*\s+Move to:\s*(.+?)\s*$/;
const PATCH_FENCE = /^\*\*\*\s+(Begin|End)\s+Patch/;

export function patchFileStubs(command: string): PatchFileStub[] {
  const stubs: PatchFileStub[] = [];
  const lines = command.split(/\r?\n/);
  lines.forEach((line, index) => {
    const header = FILE_HEADER.exec(line.trim());
    if (!header) return;
    const move = MOVE_HEADER.exec(lines[index + 1]?.trim() ?? "");
    stubs.push({
      op: header[1].toLowerCase() as PatchOp,
      path: header[2],
      movedTo: move ? move[1] : null,
    });
  });
  return stubs;
}

export function parseApplyPatch(command: string): PatchFile[] | null {
  if (!/\*\*\*\s+Begin Patch/.test(command) && !FILE_HEADER.test(command.split(/\r?\n/, 1)[0] ?? "")) return null;
  const files: PatchFile[] = [];
  let current: PatchFile | null = null;
  let hunk: Hunk | null = null;

  for (const rawLine of command.split(/\r?\n/)) {
    const trimmed = rawLine.trim();
    if (PATCH_FENCE.test(trimmed)) continue;

    const header = FILE_HEADER.exec(trimmed);
    if (header) {
      current = { op: header[1].toLowerCase() as PatchOp, path: header[2], movedTo: null, hunks: [] };
      files.push(current);
      hunk = null;
      continue;
    }

    const move = MOVE_HEADER.exec(trimmed);
    if (move && current) {
      current.movedTo = move[1];
      continue;
    }

    if (!current) {
      if (!trimmed) continue;
      return null; // body content before any file header — not a patch we understand
    }

    if (trimmed.startsWith("@@")) {
      hunk = { lines: [] };
      current.hunks.push(hunk);
      continue;
    }

    const kind: DiffLine["kind"] = rawLine.startsWith("+") ? "add" : rawLine.startsWith("-") ? "del" : "context";
    const text = rawLine.startsWith("+") || rawLine.startsWith("-") || rawLine.startsWith(" ") ? rawLine.slice(1) : rawLine;
    if (!hunk) {
      hunk = { lines: [] };
      current.hunks.push(hunk);
    }
    hunk.lines.push({ kind, text, oldLine: null, newLine: null });
  }

  return files.length ? files : null;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/patch.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/patch.ts frontend/src/lib/patch.test.ts
git commit -m "Parse Apply-Patch Commands Into Structured Hunks"
```

---

### Task 6: Golden fixtures from the live corpus

Real payloads for the presenter smoke suite (§8). Extraction reads a `.backup` snapshot — never the live DB directly — and every fixture is human-reviewed before commit (payloads may contain sensitive command lines).

**Files:**
- Create: `scripts/extract-presenter-fixtures.sh`
- Create: `frontend/src/lib/presenters/__fixtures__/bash.json`, `edit.json`, `write.json`, `read.json`, `apply_patch.json`

**Interfaces:**
- Consumes: `sba-agentic.db` (repo root), read-only.
- Produces: JSON arrays of `{ toolName, eventType, toolInputJson, toolOutputJson }` — the corpus smoke test in Task 7 and the presenter tests in Tasks 8–11 import these files directly (`resolveJsonModule` is already on in tsconfig).

- [ ] **Step 1: Write `scripts/extract-presenter-fixtures.sh`:**

```sh
#!/bin/sh
# Extract golden presenter fixtures from a read-only snapshot of the live Black Box DB.
# Usage: scripts/extract-presenter-fixtures.sh [db-path]   (default: sba-agentic.db)
set -eu
DB="${1:-sba-agentic.db}"
SNAP_DIR="$(mktemp -d)"
SNAP="$SNAP_DIR/fixtures-snapshot.db"
OUT="frontend/src/lib/presenters/__fixtures__"
mkdir -p "$OUT"
sqlite3 "file:$DB?mode=ro" ".backup $SNAP"
for tool in Bash Edit Write Read apply_patch; do
  file="$OUT/$(printf '%s' "$tool" | tr '[:upper:]' '[:lower:]').json"
  sqlite3 -json "$SNAP" "
    SELECT tool_name AS toolName, event_type AS eventType,
           tool_input_json AS toolInputJson, tool_output_json AS toolOutputJson
    FROM agent_events
    WHERE tool_name = '$tool'
      AND tool_input_json IS NOT NULL
      AND length(coalesce(tool_input_json, '')) BETWEEN 40 AND 4000
      AND length(coalesce(tool_output_json, '')) < 8000
    ORDER BY observed_at DESC
    LIMIT 3;" > "$file"
  [ -s "$file" ] || printf '[]\n' > "$file"   # sqlite3 -json emits nothing (not []) for empty results
  echo "wrote $file"
done
rm -rf "$SNAP_DIR"
```

- [ ] **Step 2: Run it**

Run: `chmod +x scripts/extract-presenter-fixtures.sh && scripts/extract-presenter-fixtures.sh`
Expected: five files written, each a JSON array of up to 3 rows. If any file is empty (`[]`… possible for low-volume tools), widen the length bounds for that tool and re-run.

- [ ] **Step 3: SECURITY REVIEW — read every fixture file in full before committing.** Look for tokens, API keys, passwords, private URLs, or personal content inside commands and outputs. Replace any hit by re-running with different LIMIT/OFFSET or hand-editing the value to a scrubbed equivalent. Also run:

Run: `grep -inE "api[_-]?key|secret|token|password|authorization|bearer" frontend/src/lib/presenters/__fixtures__/*.json`
Expected: zero hits (investigate and scrub any match).

- [ ] **Step 4: Commit**

```bash
git add scripts/extract-presenter-fixtures.sh frontend/src/lib/presenters/__fixtures__
git commit -m "Extract Golden Presenter Fixtures From The Live Corpus"
```

---

### Task 7: Presenter types, registry, and generic fallback

The contract of the whole layer (§4.1). Pure functions, no DOM, no signals, no I/O.

**Files:**
- Create: `frontend/src/lib/presenters/types.ts`
- Create: `frontend/src/lib/presenters/generic.ts`
- Create: `frontend/src/lib/presenters/registry.ts`
- Create: `frontend/src/lib/presenters/registry.test.ts`

**Interfaces:**
- Consumes: `AgentEvent` from `../api`, `Hunk` from `../diff`, `PatchFileStub` from `../patch`.
- Produces (every later task builds on these — exact shapes):

```ts
// frontend/src/lib/presenters/types.ts
import type { PatchFileStub } from "../patch";
import type { AgentEvent } from "../api";

export type Tone = "neutral" | "run" | "read" | "write" | "net" | "plan" | "memory" | "error";

export type FileRef = { path: string; line?: number | null };

export type InlineSpan =
  | { kind: "text"; text: string }
  | { kind: "code"; text: string }
  | { kind: "fileLink"; label: string; file: FileRef }
  | { kind: "url"; href: string; label: string };

export type PlanStep = { step: string; status: string };

export type DetailBlock =
  | { kind: "diff"; file: FileRef; oldText: string; newText: string; label: string }
  | { kind: "patch"; command: string; files: PatchFileStub[] }
  | { kind: "bash"; command: string; cwd: string | null; output: string | null; exitCode: number | null; wallTime: string | null }
  | { kind: "plan"; explanation: string | null; steps: PlanStep[] }
  | { kind: "code"; lang: string | null; text: string; file: FileRef | null; label: string }
  | { kind: "markdown"; text: string }
  | { kind: "json"; value: unknown; label: string }
  | { kind: "text"; text: string; label: string }
  | { kind: "fallback"; toolName: string | null; inputJson: string | null; outputJson: string | null };

export type Presentation = {
  kindPill: { label: string; tone: Tone };
  headline: InlineSpan[];
  blocks: DetailBlock[];
  sizes: { inputChars: number; outputChars: number };
  refs: FileRef[];
};

export type Presenter = (event: AgentEvent) => Presentation;
```

  - `normalizeToolName(toolName): string` — lowercase, `mcp__<server>__<tool>` → `<tool>`.
  - `presenterFor(toolName, eventType?): Presenter` — unknown → `genericPresenter`.
  - `presentationOf(event: AgentEvent): Presentation` — WeakMap-cached per event object; **wraps the presenter call in try/catch → generic on throw** (this is the never-throw guarantee).
  - `headlineText(presentation: Presentation): string` — plain-text join for collapsed rows / aria labels.
  - `genericPresenter(event): Presentation` — empty headline (callers fall back to legacy heuristics), one `fallback` block.

- [ ] **Step 1: Write the failing test** `frontend/src/lib/presenters/registry.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import bashFixtures from "./__fixtures__/bash.json";
import editFixtures from "./__fixtures__/edit.json";
import writeFixtures from "./__fixtures__/write.json";
import readFixtures from "./__fixtures__/read.json";
import patchFixtures from "./__fixtures__/apply_patch.json";
import { headlineText, normalizeToolName, presentationOf, presenterFor } from "./registry";
import { genericPresenter } from "./generic";

export function fixtureEvent(row: { toolName: string; eventType: string; toolInputJson: string | null; toolOutputJson: string | null }, index: number): AgentEvent {
  return {
    id: `fixture-${row.toolName}-${index}`,
    sessionId: "fixture-session",
    source: "claude",
    clientSessionId: "fixture-client",
    eventType: row.eventType,
    toolName: row.toolName,
    toolInputJson: row.toolInputJson,
    toolOutputJson: row.toolOutputJson,
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("normalizeToolName", () => {
  it("lowercases and strips mcp prefixes", () => {
    expect(normalizeToolName("Bash")).toBe("bash");
    expect(normalizeToolName("mcp__sba-agentic__captureDecision")).toBe("capturedecision");
    expect(normalizeToolName(null)).toBe("");
  });
});

describe("presenterFor", () => {
  it("resolves unknown tools to the generic presenter", () => {
    expect(presenterFor("SomeUnknownTool")).toBe(genericPresenter);
    expect(presenterFor(null)).toBe(genericPresenter);
  });
});

describe("presentationOf", () => {
  it("caches per event object", () => {
    const event = fixtureEvent({ toolName: "Nope", eventType: "PostToolUse", toolInputJson: "{}", toolOutputJson: null }, 0);
    expect(presentationOf(event)).toBe(presentationOf(event));
  });

  it("never throws across the entire golden corpus and always reports sizes", () => {
    const corpus = [...bashFixtures, ...editFixtures, ...writeFixtures, ...readFixtures, ...patchFixtures];
    expect(corpus.length).toBeGreaterThan(0);
    corpus.forEach((row, index) => {
      const event = fixtureEvent(row, index);
      const presentation = presentationOf(event);
      expect(presentation.blocks.length).toBeGreaterThanOrEqual(0);
      expect(presentation.sizes.inputChars).toBe(row.toolInputJson?.length ?? 0);
      expect(presentation.sizes.outputChars).toBe(row.toolOutputJson?.length ?? 0);
    });
  });
});

describe("genericPresenter", () => {
  it("emits a single fallback block and an empty headline", () => {
    const event = fixtureEvent({ toolName: "Mystery", eventType: "PostToolUse", toolInputJson: '{"a":1}', toolOutputJson: null }, 0);
    const presentation = genericPresenter(event);
    expect(presentation.headline).toEqual([]);
    expect(presentation.blocks).toEqual([{ kind: "fallback", toolName: "Mystery", inputJson: '{"a":1}', outputJson: null }]);
    expect(headlineText(presentation)).toBe("");
  });
});
```

Note: at this point the corpus test exercises only the generic presenter (registry is empty until Tasks 8–11 fill it) — it must already hold the never-throw/sizes invariants, and it keeps holding as presenters land.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/presenters/registry.test.ts`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement.** Create `types.ts` exactly as in the Interfaces section above. Create `generic.ts`:

```ts
import type { AgentEvent } from "../api";
import type { Presentation } from "./types";

export function genericPresenter(event: AgentEvent): Presentation {
  return {
    kindPill: { label: event.toolName || event.eventType || "Event", tone: "neutral" },
    headline: [],
    blocks: [{
      kind: "fallback",
      toolName: event.toolName ?? null,
      inputJson: event.toolInputJson ?? null,
      outputJson: event.toolOutputJson ?? null,
    }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [],
  };
}
```

Create `registry.ts` (the `REGISTRY` map starts empty; Tasks 8–11 add entries):

```ts
import type { AgentEvent } from "../api";
import { genericPresenter } from "./generic";
import type { Presentation, Presenter } from "./types";

const REGISTRY: Record<string, Presenter> = {};

export function normalizeToolName(toolName: string | null | undefined): string {
  const raw = String(toolName || "").trim().toLowerCase();
  const mcp = /^mcp__.+__(.+)$/.exec(raw);
  return mcp ? mcp[1] : raw;
}

export function presenterFor(toolName: string | null | undefined, _eventType?: string): Presenter {
  return REGISTRY[normalizeToolName(toolName)] ?? genericPresenter;
}

const presentationCache = new WeakMap<AgentEvent, Presentation>();

export function presentationOf(event: AgentEvent): Presentation {
  const hit = presentationCache.get(event);
  if (hit) return hit;
  let presentation: Presentation;
  try {
    presentation = presenterFor(event.toolName, event.eventType)(event);
  } catch {
    presentation = genericPresenter(event);
  }
  presentationCache.set(event, presentation);
  return presentation;
}

export function headlineText(presentation: Presentation): string {
  return presentation.headline
    .map((span) => (span.kind === "fileLink" || span.kind === "url" ? span.label : span.text))
    .join("")
    .trim();
}

export function registerPresenter(name: string, presenter: Presenter): void {
  REGISTRY[name] = presenter;
}
```

Registration style: Tasks 8–11 add plain static entries to the `REGISTRY` literal (e.g. `bash: bashPresenter`) — `registerPresenter` exists only so tests can install a throwing presenter to prove the try/catch (see Step 4). Do not use it in app code.

- [ ] **Step 4: Add the never-throw proof test** (append to `registry.test.ts`):

```ts
import { registerPresenter } from "./registry";

describe("presentationOf error containment", () => {
  it("degrades a throwing presenter to the generic fallback", () => {
    registerPresenter("explosive", () => {
      throw new Error("presenter bug");
    });
    const event = fixtureEvent({ toolName: "explosive", eventType: "PostToolUse", toolInputJson: "{}", toolOutputJson: null }, 0);
    expect(presentationOf(event).blocks[0].kind).toBe("fallback");
  });
});
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd frontend && npx vitest run src/lib/presenters/registry.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/presenters/types.ts frontend/src/lib/presenters/generic.ts frontend/src/lib/presenters/registry.ts frontend/src/lib/presenters/registry.test.ts
git commit -m "Define The Presenter Contract, Registry, And Generic Fallback"
```

---

### Task 8: Bash presenter

147,026 events — the single highest-value presenter. Headline: first non-comment line + `+N lines`; block: command, cwd, output, exit code, wall time; error tone on non-zero exit (§4.2).

**Files:**
- Create: `frontend/src/lib/presenters/bash.ts`
- Create: `frontend/src/lib/presenters/bash.test.ts`
- Modify: `frontend/src/lib/presenters/registry.ts` (add imports + `bash` and `shell` entries)

**Interfaces:**
- Consumes: `parseJsonObject`, `parseToolResult` from `../payload`; `genericPresenter`; types from `./types`.
- Produces: `bashPresenter(event: AgentEvent): Presentation` — registered as `bash` and `shell`.

- [ ] **Step 1: Write the failing test** `frontend/src/lib/presenters/bash.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { bashPresenter } from "./bash";
import { headlineText } from "./registry";

function bashEvent(overrides: Partial<AgentEvent>): AgentEvent {
  return {
    id: "evt-bash",
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "Bash",
    observedAt: "2026-07-28T12:00:00Z",
    ...overrides,
  };
}

describe("bashPresenter", () => {
  it("headlines the first non-comment line with a +N lines suffix", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({ command: "# setup\nnpm test\nnpm run build", cwd: "/tmp/proj" }),
    });
    const presentation = bashPresenter(event);
    expect(headlineText(presentation)).toBe("npm test +2 lines");
    expect(presentation.kindPill).toEqual({ label: "Bash", tone: "run" });
  });

  it("builds a bash block from the Codex structured result", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({ command: "ls", cwd: "/tmp" }),
      toolOutputJson: JSON.stringify("Exit code: 0\nWall time: 0.1 seconds\nOutput:\nfile.txt"),
    });
    const [block] = bashPresenter(event).blocks;
    expect(block).toEqual({ kind: "bash", command: "ls", cwd: "/tmp", output: "file.txt", exitCode: 0, wallTime: "0.1 seconds" });
  });

  it("turns non-zero exits into the error tone", () => {
    const event = bashEvent({
      toolInputJson: JSON.stringify({ command: "false" }),
      toolOutputJson: JSON.stringify({ exit_code: 1, output: "boom" }),
    });
    expect(bashPresenter(event).kindPill.tone).toBe("error");
  });

  it("falls back to generic when there is no command at all", () => {
    const event = bashEvent({ toolInputJson: JSON.stringify({ description: "no command key" }) });
    expect(bashPresenter(event).blocks[0].kind).toBe("fallback");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/presenters/bash.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `frontend/src/lib/presenters/bash.ts`:**

```ts
import type { AgentEvent } from "../api";
import { parseJsonObject, parseToolResult } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function bashPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const command = firstString(input, ["command", "cmd", "script"]);
  if (!command) return genericPresenter(event);
  const cwd = firstString(input, ["cwd", "workdir"]);
  const result = normalizeResult(parseToolResult(event.toolOutputJson));

  const lines = command.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const first = lines.find((line) => !line.startsWith("#")) ?? lines[0] ?? command.trim();
  const suffix = lines.length > 1 ? ` +${lines.length - 1} lines` : "";
  const failed = result.exitCode !== null && result.exitCode !== 0;

  return {
    kindPill: { label: "Bash", tone: failed ? "error" : "run" },
    headline: [{ kind: "code", text: `${first.slice(0, 180)}${suffix}` }],
    blocks: [{ kind: "bash", command, cwd: cwd ?? null, output: result.output, exitCode: result.exitCode, wallTime: result.wallTime }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [],
  };
}

function firstString(record: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return null;
}

function normalizeResult(value: unknown): { output: string | null; exitCode: number | null; wallTime: string | null } {
  if (typeof value === "string") return { output: value, exitCode: null, wallTime: null };
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const record = value as Record<string, unknown>;
    const output = ["output", "stdout", "result", "content"]
      .map((key) => record[key])
      .find((candidate): candidate is string => typeof candidate === "string") ?? null;
    const exitRaw = record.exit_code ?? record.exitCode ?? record.code;
    const exitCode = typeof exitRaw === "number"
      ? exitRaw
      : typeof exitRaw === "string" && exitRaw.trim() !== "" && Number.isFinite(Number(exitRaw)) ? Number(exitRaw) : null;
    const wallRaw = record.wall_time ?? record.wall_time_seconds ?? record.wallTime;
    const wallTime = typeof wallRaw === "string" ? wallRaw : typeof wallRaw === "number" ? String(wallRaw) : null;
    return { output, exitCode, wallTime };
  }
  return { output: null, exitCode: null, wallTime: null };
}
```

- [ ] **Step 4: Register it.** In `registry.ts`, add `import { bashPresenter } from "./bash";` and change the map to:

```ts
const REGISTRY: Record<string, Presenter> = {
  bash: bashPresenter,
  shell: bashPresenter,
};
```

- [ ] **Step 5: Run the presenter suites**

Run: `cd frontend && npx vitest run src/lib/presenters`
Expected: PASS — including the golden-corpus invariants now flowing through the real bash presenter.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/presenters/bash.ts frontend/src/lib/presenters/bash.test.ts frontend/src/lib/presenters/registry.ts
git commit -m "Present Bash Events With Structured Command And Result"
```

---

### Task 9: Read presenter

**Files:**
- Create: `frontend/src/lib/presenters/read.ts`
- Create: `frontend/src/lib/presenters/read.test.ts`
- Modify: `frontend/src/lib/presenters/registry.ts` (add `read` entry)

**Interfaces:**
- Consumes: `parseJsonObject`, `payloadText` from `../payload`; `truncatePath` from `../format`; `genericPresenter`.
- Produces: `readPresenter(event): Presentation` and `langForPath(path: string): string | null` (exported — slice 5 presenters will reuse it).

- [ ] **Step 1: Write the failing test** `frontend/src/lib/presenters/read.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { langForPath, readPresenter } from "./read";
import { headlineText } from "./registry";

function readEvent(overrides: Partial<AgentEvent>): AgentEvent {
  return {
    id: "evt-read",
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "Read",
    observedAt: "2026-07-28T12:00:00Z",
    ...overrides,
  };
}

describe("readPresenter", () => {
  it("headlines the home-shortened path as a file link with a line range", () => {
    const event = readEvent({
      toolInputJson: JSON.stringify({ file_path: "/Users/nathan/Developer/proj/x/a.ts", offset: 10, limit: 40 }),
      toolOutputJson: JSON.stringify({ output: "const x = 1;" }),
    });
    const presentation = readPresenter(event);
    expect(headlineText(presentation)).toBe("~/Developer/proj/x/a.ts:10–50");
    expect(presentation.headline[0]).toMatchObject({ kind: "fileLink", file: { path: "/Users/nathan/Developer/proj/x/a.ts", line: 10 } });
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts", line: 10 }]);
    expect(presentation.kindPill).toEqual({ label: "Read", tone: "read" });
  });

  it("wraps content in a code block with a size label and inferred language", () => {
    const event = readEvent({
      toolInputJson: JSON.stringify({ file_path: "/tmp/app.py" }),
      toolOutputJson: JSON.stringify({ output: "print('hi')" }),
    });
    const [block] = readPresenter(event).blocks;
    expect(block).toMatchObject({ kind: "code", lang: "python", text: "print('hi')", label: "Content (11 chars)" });
  });

  it("emits no blocks when there is no output yet (PreToolUse)", () => {
    const event = readEvent({ eventType: "PreToolUse", toolInputJson: JSON.stringify({ file_path: "/tmp/a.txt" }) });
    expect(readPresenter(event).blocks).toEqual([]);
  });

  it("falls back to generic without a path", () => {
    expect(readPresenter(readEvent({ toolInputJson: "{}" })).blocks[0].kind).toBe("fallback");
  });
});

describe("langForPath", () => {
  it("maps common extensions and returns null for unknown ones", () => {
    expect(langForPath("/a/b.tsx")).toBe("tsx");
    expect(langForPath("/a/b.java")).toBe("java");
    expect(langForPath("/a/b.unknownext")).toBeNull();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/presenters/read.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `frontend/src/lib/presenters/read.ts`:**

```ts
import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject, payloadText } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

const LANGS: Record<string, string> = {
  ts: "typescript", tsx: "tsx", js: "javascript", jsx: "jsx", mjs: "javascript",
  java: "java", py: "python", rb: "ruby", go: "go", rs: "rust", sh: "shell", zsh: "shell",
  css: "css", html: "html", json: "json", yml: "yaml", yaml: "yaml", md: "markdown",
  sql: "sql", xml: "xml", toml: "toml",
};

export function langForPath(path: string): string | null {
  const extension = /\.([a-z0-9]+)$/i.exec(path)?.[1]?.toLowerCase();
  return extension ? LANGS[extension] ?? null : null;
}

export function readPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath ?? input.path;
  if (typeof pathValue !== "string" || !pathValue.trim()) return genericPresenter(event);
  const offset = typeof input.offset === "number" ? input.offset : null;
  const limit = typeof input.limit === "number" ? input.limit : null;
  const file = { path: pathValue, line: offset };
  const content = payloadText(event.toolOutputJson);
  const range = offset !== null ? `:${offset}${limit !== null ? `–${offset + limit}` : ""}` : "";

  return {
    kindPill: { label: "Read", tone: "read" },
    headline: [{ kind: "fileLink", label: `${truncatePath(pathValue)}${range}`, file }],
    blocks: content
      ? [{ kind: "code", lang: langForPath(pathValue), text: content, file, label: `Content (${content.length.toLocaleString("en-US")} chars)` }]
      : [],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
```

- [ ] **Step 4: Register it** in `registry.ts`: add `read: readPresenter` to the map with its import.

- [ ] **Step 5: Run tests, expect PASS:** `cd frontend && npx vitest run src/lib/presenters`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/presenters/read.ts frontend/src/lib/presenters/read.test.ts frontend/src/lib/presenters/registry.ts
git commit -m "Present Read Events As Linked File Content"
```

---

### Task 10: Edit + Write presenters

`Edit` carries a diff already (`old_string`/`new_string`); `Write` renders as all-additions (§4.3). Blocks carry the **inputs**; hunks are computed lazily by `DiffBlock` (deviation 2).

**Files:**
- Create: `frontend/src/lib/presenters/edit.ts`
- Create: `frontend/src/lib/presenters/write.ts`
- Create: `frontend/src/lib/presenters/edit.test.ts` (covers both)
- Modify: `frontend/src/lib/presenters/registry.ts` (add `edit` and `write` entries)

**Interfaces:**
- Consumes: `parseJsonObject` from `../payload`; `truncatePath` from `../format`; `genericPresenter`.
- Produces: `editPresenter(event): Presentation`, `writePresenter(event): Presentation`.

- [ ] **Step 1: Write the failing test** `frontend/src/lib/presenters/edit.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { editPresenter } from "./edit";
import { writePresenter } from "./write";
import { headlineText } from "./registry";

function toolEvent(toolName: string, inputJson: string): AgentEvent {
  return {
    id: `evt-${toolName}`,
    sessionId: "ses",
    source: "claude",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName,
    toolInputJson: inputJson,
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("editPresenter", () => {
  it("emits a lazy diff block carrying the raw old and new strings", () => {
    const event = toolEvent("Edit", JSON.stringify({
      file_path: "/Users/nathan/Developer/proj/x/a.ts",
      old_string: "const a = 1;",
      new_string: "const a = 2;",
    }));
    const presentation = editPresenter(event);
    expect(presentation.kindPill).toEqual({ label: "Edit", tone: "write" });
    expect(headlineText(presentation)).toBe("~/Developer/proj/x/a.ts");
    expect(presentation.blocks).toEqual([{
      kind: "diff",
      file: { path: "/Users/nathan/Developer/proj/x/a.ts" },
      oldText: "const a = 1;",
      newText: "const a = 2;",
      label: "Diff (12 → 12 chars)",
    }]);
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts" }]);
  });

  it("falls back to generic when old or new strings are missing", () => {
    const event = toolEvent("Edit", JSON.stringify({ file_path: "/tmp/a.ts" }));
    expect(editPresenter(event).blocks[0].kind).toBe("fallback");
  });
});

describe("writePresenter", () => {
  it("emits an all-additions diff block (empty oldText)", () => {
    const event = toolEvent("Write", JSON.stringify({ file_path: "/tmp/new.txt", content: "hello\nworld" }));
    const presentation = writePresenter(event);
    expect(presentation.kindPill).toEqual({ label: "Write", tone: "write" });
    expect(presentation.blocks).toEqual([{
      kind: "diff",
      file: { path: "/tmp/new.txt" },
      oldText: "",
      newText: "hello\nworld",
      label: "New file (11 chars)",
    }]);
  });

  it("falls back to generic without content", () => {
    const event = toolEvent("Write", JSON.stringify({ file_path: "/tmp/new.txt" }));
    expect(writePresenter(event).blocks[0].kind).toBe("fallback");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/presenters/edit.test.ts`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement `frontend/src/lib/presenters/edit.ts`:**

```ts
import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function editPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath;
  const oldText = input.old_string;
  const newText = input.new_string;
  if (typeof pathValue !== "string" || typeof oldText !== "string" || typeof newText !== "string") {
    return genericPresenter(event);
  }
  const file = { path: pathValue };
  return {
    kindPill: { label: "Edit", tone: "write" },
    headline: [{ kind: "fileLink", label: truncatePath(pathValue), file }],
    blocks: [{ kind: "diff", file, oldText, newText, label: `Diff (${oldText.length} → ${newText.length} chars)` }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
```

And `frontend/src/lib/presenters/write.ts`:

```ts
import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { parseJsonObject } from "../payload";
import { genericPresenter } from "./generic";
import type { Presentation } from "./types";

export function writePresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const pathValue = input.file_path ?? input.filePath;
  const content = input.content ?? input.contents;
  if (typeof pathValue !== "string" || typeof content !== "string") return genericPresenter(event);
  const file = { path: pathValue };
  return {
    kindPill: { label: "Write", tone: "write" },
    headline: [{ kind: "fileLink", label: truncatePath(pathValue), file }],
    blocks: [{ kind: "diff", file, oldText: "", newText: content, label: `New file (${content.length} chars)` }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: [file],
  };
}
```

- [ ] **Step 4: Register both** in `registry.ts`: `edit: editPresenter, write: writePresenter` with imports.

- [ ] **Step 5: Run tests, expect PASS:** `cd frontend && npx vitest run src/lib/presenters`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/presenters/edit.ts frontend/src/lib/presenters/write.ts frontend/src/lib/presenters/edit.test.ts frontend/src/lib/presenters/registry.ts
git commit -m "Present Edit And Write Events As Lazy Diffs"
```

---

### Task 11: apply_patch presenter

8,063 events; the stored command **is** a patch. Headline `Patch <first file> [+N more]`; one `patch` block; degrade to generic when the command isn't patch-shaped (§4.2, §4.3).

**Files:**
- Create: `frontend/src/lib/presenters/applyPatch.ts`
- Create: `frontend/src/lib/presenters/applyPatch.test.ts`
- Modify: `frontend/src/lib/presenters/registry.ts` (add `apply_patch` entry)

**Interfaces:**
- Consumes: `patchFileStubs` from `../patch`; `parseJsonObject` from `../payload`; `truncatePath` from `../format`; `genericPresenter`.
- Produces: `applyPatchPresenter(event): Presentation`.

- [ ] **Step 1: Write the failing test** `frontend/src/lib/presenters/applyPatch.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../api";
import { applyPatchPresenter } from "./applyPatch";
import { headlineText } from "./registry";

function patchEvent(command: string): AgentEvent {
  return {
    id: "evt-patch",
    sessionId: "ses",
    source: "codex",
    clientSessionId: "client",
    eventType: "PostToolUse",
    toolName: "apply_patch",
    toolInputJson: JSON.stringify({ command }),
    observedAt: "2026-07-28T12:00:00Z",
  };
}

describe("applyPatchPresenter", () => {
  it("headlines the first patched file and counts the rest", () => {
    const command = "*** Begin Patch\n*** Update File: /Users/nathan/Developer/proj/x/a.ts\n@@\n+x\n*** Add File: /tmp/b.txt\n+y\n*** End Patch";
    const presentation = applyPatchPresenter(patchEvent(command));
    expect(presentation.kindPill).toEqual({ label: "Patch", tone: "write" });
    expect(headlineText(presentation)).toBe("Patch ~/Developer/proj/x/a.ts +1 more");
    expect(presentation.refs).toEqual([{ path: "/Users/nathan/Developer/proj/x/a.ts" }, { path: "/tmp/b.txt" }]);
    const [block] = presentation.blocks;
    expect(block.kind).toBe("patch");
    if (block.kind === "patch") {
      expect(block.command).toBe(command);
      expect(block.files.map((stub) => stub.op)).toEqual(["update", "add"]);
    }
  });

  it("falls back to generic when the command is not patch-shaped", () => {
    expect(applyPatchPresenter(patchEvent("echo not-a-patch")).blocks[0].kind).toBe("fallback");
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/presenters/applyPatch.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `frontend/src/lib/presenters/applyPatch.ts`:**

```ts
import type { AgentEvent } from "../api";
import { truncatePath } from "../format";
import { patchFileStubs } from "../patch";
import { parseJsonObject } from "../payload";
import { genericPresenter } from "./generic";
import type { InlineSpan, Presentation } from "./types";

export function applyPatchPresenter(event: AgentEvent): Presentation {
  const input = parseJsonObject(event.toolInputJson) ?? {};
  const commandValue = input.command ?? input.input ?? input.patch;
  if (typeof commandValue !== "string" || !/\*\*\*\s+(Begin Patch|Update File|Add File|Delete File)/.test(commandValue)) {
    return genericPresenter(event);
  }
  const files = patchFileStubs(commandValue);
  const first = files[0];
  const headline: InlineSpan[] = first
    ? [
        { kind: "text", text: "Patch " },
        { kind: "fileLink", label: truncatePath(first.path), file: { path: first.path } },
        ...(files.length > 1 ? [{ kind: "text", text: ` +${files.length - 1} more` } satisfies InlineSpan] : []),
      ]
    : [{ kind: "text", text: "Patch" }];

  return {
    kindPill: { label: "Patch", tone: "write" },
    headline,
    blocks: [{ kind: "patch", command: commandValue, files }],
    sizes: { inputChars: event.toolInputJson?.length ?? 0, outputChars: event.toolOutputJson?.length ?? 0 },
    refs: files.map((stub) => ({ path: stub.path })),
  };
}
```

- [ ] **Step 4: Register it** in `registry.ts`: `apply_patch: applyPatchPresenter` with its import.

- [ ] **Step 5: Run tests, expect PASS:** `cd frontend && npx vitest run src/lib/presenters`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/presenters/applyPatch.ts frontend/src/lib/presenters/applyPatch.test.ts frontend/src/lib/presenters/registry.ts
git commit -m "Present Apply-Patch Events From Their Stored Patch"
```

---

### Task 12: Block components, inline spans, and the tone system

The fixed rendering surface (§4.1): ~6 block components regardless of how many presenters exist. Heavy content mounts lazily behind native `<details>` with size labels (§4.5); tones live only in `theme.css`.

**Files:**
- Create: `frontend/src/components/events/blocks/LazyDetails.tsx`
- Create: `frontend/src/components/events/blocks/DiffBlock.tsx`
- Create: `frontend/src/components/events/blocks/BashBlock.tsx`
- Create: `frontend/src/components/events/blocks/BlockView.tsx`
- Create: `frontend/src/components/events/InlineSpans.tsx`
- Create: `frontend/src/components/events/blocks/BlockView.test.tsx`
- Modify: `frontend/src/theme.css` (append tone variables + block styles at end of file)

**Interfaces:**
- Consumes: `DetailBlock`, `InlineSpan`, `FileRef` from `../../../lib/presenters/types`; `memoizedDiffLines`, `Hunk` from `../../../lib/diff`; `parseApplyPatch` from `../../../lib/patch`; `truncatePath` from `../../../lib/format`; existing `ToolPayload`.
- Produces (Task 13 imports these):
  - `BlockView(props: { block: DetailBlock; eventId: string; index: number }): JSX.Element`
  - `InlineSpans(props: { spans: InlineSpan[] }): JSX.Element`
  - `LazyDetails(props: { summary: string; class?: string; children: JSX.Element }): JSX.Element`
  - `DiffHunks(props: { hunks: Hunk[] })` (exported from `DiffBlock.tsx`; PatchBlock reuses it)

- [ ] **Step 1: Write the failing test** `frontend/src/components/events/blocks/BlockView.test.tsx`:

```tsx
import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import BlockView from "./BlockView";

describe("BlockView diff", () => {
  it("keeps diff content unmounted until the details block is opened, then renders hunks", () => {
    const { container } = render(() => (
      <BlockView
        eventId="evt-1"
        index={0}
        block={{ kind: "diff", file: { path: "/tmp/a.ts" }, oldText: "const a = 1;", newText: "const a = 2;", label: "Diff (12 → 12 chars)" }}
      />
    ));
    expect(screen.getByText("Diff (12 → 12 chars)")).toBeInTheDocument();
    expect(container.querySelector(".diff-line")).toBeNull(); // lazy: nothing mounted yet

    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));

    expect(container.querySelector(".diff-line--del")?.textContent).toContain("const a = 1;");
    expect(container.querySelector(".diff-line--add")?.textContent).toContain("const a = 2;");
  });
});

describe("BlockView bash", () => {
  it("shows the command immediately and the output behind a sized details block", () => {
    const { container } = render(() => (
      <BlockView
        eventId="evt-2"
        index={0}
        block={{ kind: "bash", command: "npm test", cwd: "/tmp/proj", output: "42 tests passed", exitCode: 0, wallTime: "1.2 seconds" }}
      />
    ));
    expect(screen.getByText("npm test")).toBeInTheDocument();
    expect(screen.getByText("exit 0")).toBeInTheDocument();
    expect(screen.getByText("Output (15 chars)")).toBeInTheDocument();
    expect(screen.queryByText("42 tests passed")).toBeNull(); // lazy

    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(screen.getByText("42 tests passed")).toBeInTheDocument();
  });
});

describe("BlockView patch", () => {
  it("renders parsed patch hunks per file when opened", () => {
    const command = "*** Begin Patch\n*** Update File: /tmp/a.ts\n@@\n-old\n+new\n*** End Patch";
    const { container } = render(() => (
      <BlockView eventId="evt-3" index={0} block={{ kind: "patch", command, files: [{ op: "update", path: "/tmp/a.ts", movedTo: null }] }} />
    ));
    const details = container.querySelector("details") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(container.querySelector(".diff-line--del")?.textContent).toContain("old");
    expect(container.querySelector(".diff-line--add")?.textContent).toContain("new");
  });
});

describe("BlockView fallback", () => {
  it("renders the existing ToolPayload for fallback blocks", () => {
    render(() => (
      <BlockView eventId="evt-4" index={0} block={{ kind: "fallback", toolName: "Mystery", inputJson: JSON.stringify({ query: "hello" }), outputJson: null }} />
    ));
    expect(screen.getByText("Query")).toBeInTheDocument();
    expect(screen.getByText("hello")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/components/events/blocks/BlockView.test.tsx`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement the components.**

`frontend/src/components/events/blocks/LazyDetails.tsx`:

```tsx
import { createSignal, Show, type JSX } from "solid-js";

/**
 * Native <details> whose children mount on first open and stay mounted (spec §4.5):
 * zero re-render cost, keyboard and find-in-page friendly, no expand-state bookkeeping.
 */
export default function LazyDetails(props: { summary: string; class?: string; children: JSX.Element }) {
  const [mounted, setMounted] = createSignal(false);
  return (
    <details
      class={props.class || "detail-block"}
      onToggle={(toggleEvent) => {
        if (toggleEvent.currentTarget.open) setMounted(true);
      }}
    >
      <summary>{props.summary}</summary>
      <Show when={mounted()}>{props.children}</Show>
    </details>
  );
}
```

`frontend/src/components/events/blocks/DiffBlock.tsx`:

```tsx
import { createMemo, For, Show } from "solid-js";
import { memoizedDiffLines, type Hunk } from "../../../lib/diff";
import type { FileRef } from "../../../lib/presenters/types";
import LazyDetails from "./LazyDetails";

type DiffBlockProps = {
  eventId: string;
  index: number;
  file: FileRef;
  oldText: string;
  newText: string;
  label: string;
};

export default function DiffBlock(props: DiffBlockProps) {
  return (
    <LazyDetails summary={props.label} class="detail-block detail-block--diff">
      <DiffBody eventId={props.eventId} index={props.index} oldText={props.oldText} newText={props.newText} />
    </LazyDetails>
  );
}

function DiffBody(props: { eventId: string; index: number; oldText: string; newText: string }) {
  const hunks = createMemo(() => memoizedDiffLines(`${props.eventId}:${props.index}`, props.oldText, props.newText));
  return <DiffHunks hunks={hunks()} />;
}

export function DiffHunks(props: { hunks: Hunk[] }) {
  return (
    <div class="diff">
      <Show when={props.hunks.length} fallback={<p class="diff-empty">No line changes.</p>}>
        <For each={props.hunks}>
          {(hunk, hunkIndex) => (
            <>
              <Show when={hunkIndex() > 0}>
                <div class="diff-hunk-sep" aria-hidden="true" />
              </Show>
              <For each={hunk.lines}>
                {(line) => (
                  <div
                    classList={{
                      "diff-line": true,
                      "diff-line--add": line.kind === "add",
                      "diff-line--del": line.kind === "del",
                    }}
                  >
                    <span class="diff-gutter">{line.kind === "add" ? "+" : line.kind === "del" ? "−" : " "}</span>
                    <span class="diff-text">{line.text}</span>
                  </div>
                )}
              </For>
            </>
          )}
        </For>
      </Show>
    </div>
  );
}
```

`frontend/src/components/events/blocks/BashBlock.tsx`:

```tsx
import { Show } from "solid-js";
import { truncatePath } from "../../../lib/format";
import LazyDetails from "./LazyDetails";

type BashBlockProps = {
  block: { command: string; cwd: string | null; output: string | null; exitCode: number | null; wallTime: string | null };
};

export default function BashBlock(props: BashBlockProps) {
  const failed = () => props.block.exitCode !== null && props.block.exitCode !== 0;
  return (
    <div class="bash-block">
      <pre class="bash-command">{props.block.command}</pre>
      <div class="bash-meta">
        <Show when={props.block.cwd}>
          {(cwd) => <span class="bash-cwd" title={cwd()}>{truncatePath(cwd())}</span>}
        </Show>
        <Show when={props.block.exitCode !== null}>
          <span classList={{ "bash-exit": true, "bash-exit--error": failed() }}>exit {props.block.exitCode}</span>
        </Show>
        <Show when={props.block.wallTime}>{(wall) => <span class="bash-wall">{wall()}</span>}</Show>
      </div>
      <Show when={props.block.output}>
        {(output) => (
          <LazyDetails summary={`Output (${output().length.toLocaleString("en-US")} chars)`} class="detail-block detail-block--output">
            <pre class="detail-pre">{output()}</pre>
          </LazyDetails>
        )}
      </Show>
    </div>
  );
}
```

`frontend/src/components/events/blocks/BlockView.tsx`:

```tsx
import { createMemo, For, Show, type JSX } from "solid-js";
import { parseApplyPatch } from "../../../lib/patch";
import type { DetailBlock } from "../../../lib/presenters/types";
import type { PatchFileStub } from "../../../lib/patch";
import ToolPayload from "../ToolPayload";
import BashBlock from "./BashBlock";
import DiffBlock, { DiffHunks } from "./DiffBlock";
import LazyDetails from "./LazyDetails";

/**
 * A block value is immutable for a given event, so a plain switch (not <Switch>)
 * is safe: no prop path here is reactive.
 */
export default function BlockView(props: { block: DetailBlock; eventId: string; index: number }): JSX.Element {
  const block = props.block;
  switch (block.kind) {
    case "bash":
      return <BashBlock block={block} />;
    case "diff":
      return <DiffBlock eventId={props.eventId} index={props.index} file={block.file} oldText={block.oldText} newText={block.newText} label={block.label} />;
    case "patch":
      return <PatchBlock command={block.command} files={block.files} />;
    case "code":
      return (
        <LazyDetails summary={block.label} class="detail-block detail-block--code">
          <pre class="detail-pre">{block.text}</pre>
        </LazyDetails>
      );
    case "markdown": // rendered as plain text until slice 5
      return <pre class="detail-pre detail-pre--inline">{block.text}</pre>;
    case "text":
      return (
        <LazyDetails summary={block.label} class="detail-block">
          <pre class="detail-pre">{block.text}</pre>
        </LazyDetails>
      );
    case "json":
    case "plan": // JSON view until slice 5 ships the plan component
      return (
        <LazyDetails summary={block.kind === "json" ? block.label : "Plan"} class="detail-block detail-block--json">
          <pre class="detail-pre">{safeStringify(block.kind === "json" ? block.value : block)}</pre>
        </LazyDetails>
      );
    case "fallback":
      return <ToolPayload toolName={block.toolName} inputJson={block.inputJson} outputJson={block.outputJson} />;
  }
}

function PatchBlock(props: { command: string; files: PatchFileStub[] }) {
  const summary = () => `Patch — ${props.files.length || "?"} file${props.files.length === 1 ? "" : "s"} (${props.command.length.toLocaleString("en-US")} chars)`;
  return (
    <LazyDetails summary={summary()} class="detail-block detail-block--diff">
      <PatchBody command={props.command} />
    </LazyDetails>
  );
}

function PatchBody(props: { command: string }) {
  const parsed = createMemo(() => parseApplyPatch(props.command));
  return (
    <Show when={parsed()} fallback={<pre class="detail-pre">{props.command}</pre>}>
      {(files) => (
        <For each={files()}>
          {(file) => (
            <div class="patch-file">
              <div class="patch-file-head">
                <span class={`patch-op patch-op--${file.op}`}>{file.op}</span>
                <span class="patch-path" title={file.path}>{file.path}</span>
                <Show when={file.movedTo}>{(target) => <span class="patch-move">→ {target()}</span>}</Show>
              </div>
              <DiffHunks hunks={file.hunks} />
            </div>
          )}
        </For>
      )}
    </Show>
  );
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
```

`frontend/src/components/events/InlineSpans.tsx`:

```tsx
import { For } from "solid-js";
import type { InlineSpan } from "../../lib/presenters/types";

/**
 * Renders presenter headline spans inside the EXPANDED event card only —
 * the collapsed StreamRow is itself a <button>, so interactive spans must
 * never render there (nested buttons are invalid HTML).
 * fileLink click copies the path; slice 3 upgrades this to open-in-editor.
 */
export default function InlineSpans(props: { spans: InlineSpan[] }) {
  return (
    <For each={props.spans}>
      {(span) => {
        if (span.kind === "code") return <code class="inline-span-code">{span.text}</code>;
        if (span.kind === "fileLink") {
          return (
            <button
              type="button"
              class="inline-file-link"
              title={`${span.file.path} — click to copy path`}
              onClick={(clickEvent) => {
                clickEvent.stopPropagation();
                void navigator.clipboard?.writeText(span.file.path);
              }}
            >
              {span.label}
            </button>
          );
        }
        if (span.kind === "url") {
          return (
            <a class="inline-url" href={span.href} target="_blank" rel="noreferrer" onClick={(clickEvent) => clickEvent.stopPropagation()}>
              {span.label}
            </a>
          );
        }
        return <span>{span.text}</span>;
      }}
    </For>
  );
}
```

- [ ] **Step 4: Append the tone system and block styles to `frontend/src/theme.css`** (at the end of the file; the ONLY place tone colours exist — spec §4.1):

```css
/* ── Presenter tone system (spec §4.1) — sole source of tone colour ───────── */
:root {
  --tone-run: var(--blue);
  --tone-read: var(--text-faint);
  --tone-write: var(--orange);
  --tone-net: var(--green);
  --tone-plan: var(--accent);
  --tone-memory: var(--yellow);
  --tone-error: var(--red);
  --tone-neutral: var(--text-dim);
}

.tone-pill {
  --pill-color: var(--tone-neutral);
  border: 1px solid color-mix(in srgb, var(--pill-color) 45%, transparent);
  border-radius: var(--radius-pill);
  color: var(--pill-color);
  font-size: 10.5px;
  letter-spacing: 0.04em;
  padding: 1px 8px;
  text-transform: uppercase;
  white-space: nowrap;
}
.tone-pill--run { --pill-color: var(--tone-run); }
.tone-pill--read { --pill-color: var(--tone-read); }
.tone-pill--write { --pill-color: var(--tone-write); }
.tone-pill--net { --pill-color: var(--tone-net); }
.tone-pill--plan { --pill-color: var(--tone-plan); }
.tone-pill--memory { --pill-color: var(--tone-memory); }
.tone-pill--error { --pill-color: var(--tone-error); }

.detail-block { border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); margin-top: 8px; padding: 4px 10px; }
.detail-block > summary { color: var(--text-dim); cursor: pointer; font-size: 12px; user-select: none; }
.detail-block > summary:hover { color: var(--text); }
.detail-pre { font-family: var(--font-mono); font-size: 12px; line-height: 1.5; margin: 8px 0 4px; max-height: 420px; overflow: auto; white-space: pre-wrap; word-break: break-word; }
.detail-pre--inline { border: 0; margin: 8px 0 0; }

.bash-block { margin-top: 8px; }
.bash-command { background: var(--bg-surface); border: 1px solid var(--border-subtle); border-radius: var(--radius-sm); font-family: var(--font-mono); font-size: 12px; line-height: 1.5; margin: 0; max-height: 220px; overflow: auto; padding: 8px 10px; white-space: pre-wrap; word-break: break-word; }
.bash-meta { color: var(--text-dim); display: flex; font-size: 11.5px; gap: 12px; margin-top: 4px; }
.bash-exit--error { color: var(--tone-error); }

.diff { font-family: var(--font-mono); font-size: 12px; line-height: 1.45; margin: 6px 0 2px; overflow-x: auto; }
.diff-line { display: flex; white-space: pre-wrap; word-break: break-word; }
.diff-line--add { background: color-mix(in srgb, var(--green) 13%, transparent); }
.diff-line--del { background: color-mix(in srgb, var(--red) 13%, transparent); }
.diff-gutter { color: var(--text-faint); flex: none; user-select: none; width: 1.3em; }
.diff-text { flex: 1; }
.diff-hunk-sep { border-top: 1px dashed var(--border); margin: 6px 0; }
.diff-empty { color: var(--text-faint); font-size: 12px; margin: 6px 0; }

.patch-file + .patch-file { border-top: 1px solid var(--border-subtle); margin-top: 10px; padding-top: 8px; }
.patch-file-head { align-items: baseline; display: flex; font-size: 12px; gap: 8px; }
.patch-op { text-transform: uppercase; font-size: 10.5px; color: var(--text-dim); }
.patch-op--add { color: var(--tone-net); }
.patch-op--delete { color: var(--tone-error); }
.patch-path { font-family: var(--font-mono); overflow-wrap: anywhere; }
.patch-move { color: var(--text-dim); }

.inline-span-code { font-family: var(--font-mono); font-size: 0.95em; }
.inline-file-link { background: none; border: 0; color: var(--accent); cursor: pointer; font: inherit; font-family: var(--font-mono); padding: 0; }
.inline-file-link:hover { text-decoration: underline; }
.inline-url { color: var(--accent); overflow-wrap: anywhere; }
```

- [ ] **Step 5: Run tests, expect PASS:** `cd frontend && npx vitest run src/components/events/blocks/BlockView.test.tsx && npm run build`

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/events/blocks frontend/src/components/events/InlineSpans.tsx frontend/src/theme.css
git commit -m "Render Detail Blocks Lazily With The Tone System"
```

---

### Task 13: Wire EventRow and StreamRow to presentations

Tool events now render through the presenter layer; everything else is untouched. Decision/Handoff/Observation cards keep their components (§4.7 restyle is slice 5, not here).

**Files:**
- Modify: `frontend/src/components/events/EventRow.tsx`
- Modify: `frontend/src/components/events/StreamRow.tsx` (add `textExpanded` pass-through only)
- Modify: `frontend/src/components/events/EventCards.test.tsx` (update the Bash rendering test — see Step 4)

**Interfaces:**
- Consumes: `presentationOf`, `headlineText` from `../../lib/presenters/registry`; `BlockView`; `InlineSpans`.
- Produces (Task 14 relies on these exact signatures):
  - `EventRow(props: { event: AgentEvent; textExpanded?: boolean })`
  - `EventRenderer(props: { event: AgentEvent; textExpanded?: boolean })`
  - `ReaderText(props: { text: string; expanded?: boolean })` — changed in Task 14, referenced here unchanged.
  - `eventHeadline(event: AgentEvent): string` — same signature; now presentation-first with the legacy heuristics as fallback.

- [ ] **Step 1: Write the failing tests.** In `EventCards.test.tsx`, **replace** the existing `"renders command payloads as readable fields with decoded multiline output"` test with:

```tsx
  it("renders bash events as a structured command block with lazy output", () => {
    const event: AgentEvent = {
      id: "evt-command",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "Bash",
      toolInputJson: JSON.stringify({
        command: "npm test\nnpm run build",
        cwd: "/Users/nathan/Developer/proj/sba-agentic/frontend",
        timeout: 30_000,
      }),
      toolOutputJson: JSON.stringify("Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed"),
      text: "Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed",
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => <EventRow event={event} />);

    expect(container.querySelector(".tone-pill")?.textContent).toBe("Bash");
    expect(container.querySelector(".bash-command")?.textContent).toBe("npm test\nnpm run build");
    expect(screen.getByText("exit 0")).toBeInTheDocument();
    expect(screen.getByText("Output (15 chars)")).toBeInTheDocument();
    expect(screen.queryByText("42 tests passed")).toBeNull(); // lazy until opened

    const details = container.querySelector(".detail-block--output") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(screen.getByText("42 tests passed")).toBeInTheDocument();
  });
```

And append a new test to the same `describe("EventRow")`:

```tsx
  it("keeps unknown tools on the generic ToolPayload path", () => {
    const event: AgentEvent = {
      id: "evt-unknown",
      sessionId: "ses-1",
      source: "claude",
      clientSessionId: "client-1",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "SomeNewTool",
      toolInputJson: JSON.stringify({ query: "hello world" }),
      observedAt: "2026-06-16T20:00:00Z",
    };

    render(() => <EventRow event={event} />);

    expect(screen.getByRole("region", { name: "Input" })).toBeInTheDocument();
    expect(screen.getByText("Query")).toBeInTheDocument();
    expect(screen.getByText("hello world")).toBeInTheDocument();
  });
```

Also **replace the body of** `"summarizes patch commands by their target file"` — its `getByText("Patch ~/Developer/proj/sba-agentic/README.md")` requires the full string inside one element, but the presenter headline is now split across a text span and a file-link button. Keep the same event literal; replace the render/assert lines with (and add `eventHeadline` to the `./EventRow` import at the top of the file):

```tsx
    const { container } = render(() => <EventRow event={event} />);

    expect(container.querySelector(".tone-pill")?.textContent).toBe("Patch");
    expect(screen.getByRole("button", { name: "~/Developer/proj/sba-agentic/README.md" })).toBeInTheDocument();
    expect(eventHeadline(event)).toBe("Patch ~/Developer/proj/sba-agentic/README.md");
```

(`eventHeadline` still returns the joined plain string — that is what collapsed StreamRows show.)

- [ ] **Step 2: Run to verify the new bash test fails** (`.tone-pill` doesn't exist yet):

Run: `cd frontend && npx vitest run src/components/events/EventCards.test.tsx`
Expected: FAIL on the two new/replaced tests; PASS on the rest.

- [ ] **Step 3: Rewire `EventRow.tsx`.**
- Add imports: `presentationOf`, `headlineText` from `../../lib/presenters/registry`; `BlockView` from `./blocks/BlockView`; `InlineSpans` from `./InlineSpans`; `For` from solid-js.
- `EventRowProps` becomes `{ event: AgentEvent; textExpanded?: boolean }`.
- Inside `EventRow`, add `const presentation = () => (event().toolName ? presentationOf(event()) : null);`
- Replace the card head `<strong>{headline()}</strong>` with:

```tsx
        <Show when={presentation()} fallback={<strong>{headline()}</strong>}>
          {(current) => (
            <>
              <span class={`tone-pill tone-pill--${current().kindPill.tone}`}>{current().kindPill.label}</span>
              <strong>
                <Show when={current().headline.length} fallback={headline()}>
                  <InlineSpans spans={current().headline} />
                </Show>
              </strong>
            </>
          )}
        </Show>
```

- In the meta row, suppress the duplicate tool name when the pill already shows it: change `{event().toolName ? <span>{event().toolName}</span> : null}` to `{event().toolName && !presentation() ? <span>{event().toolName}</span> : null}`.
- Thread `textExpanded`: `<ReaderText text={event().text ?? ""} expanded={props.textExpanded} />` (the prop lands on ReaderText in Task 14; passing an extra prop before then is harmless).
- Replace the `ToolPayload` branch (lines 55–61) with the block loop:

```tsx
      <Show
        when={presentation()}
        fallback={
          event().toolInputJson || event().toolOutputJson ? (
            <ToolPayload toolName={event().toolName} inputJson={event().toolInputJson} outputJson={event().toolOutputJson} />
          ) : null
        }
      >
        {(current) => (
          <For each={current().blocks}>
            {(block, index) => <BlockView block={block} eventId={event().id} index={index()} />}
          </For>
        )}
      </Show>
```

- Rewrite `eventHeadline` to be presentation-first, keeping the legacy body verbatim as fallback (delete the now-redundant apply_patch special case in `commandHeadline` only if the patch presenter covers it — verify with the untouched patch headline test):

```ts
export function eventHeadline(event: AgentEvent): string {
  if (event.toolName) {
    const text = headlineText(presentationOf(event));
    if (text) return text;
  }
  const input = parseJsonObject(event.toolInputJson);
  const key = input ? primaryArgKey(input) : null;
  if (key && input) return commandHeadline(String(input[key]), event.toolName);
  if (event.text && !looksLikeJson(event.text)) return event.text || "";
  return event.toolName || event.role || event.eventType || "Event";
}
```

- `EventRenderer` passes the new prop through:

```tsx
export function EventRenderer(props: { event: AgentEvent; textExpanded?: boolean }) {
  switch (props.event.eventType) {
    case "Decision":
      return <DecisionCard event={props.event} />;
    case "Handoff":
      return <HandoffCard event={props.event} />;
    case "Observation":
      return <ObservationCard event={props.event} />;
    default:
      return <EventRow event={props.event} textExpanded={props.textExpanded} />;
  }
}
```

- [ ] **Step 4: Thread `textExpanded` through `StreamRow.tsx`:** add `textExpanded?: boolean` to `StreamRowProps` and change line 47 to `<EventRenderer event={item()} textExpanded={props.textExpanded} />`. Nothing else in StreamRow changes — the collapsed headline stays `eventHeadline(item())` (plain text; see deviation 5).

- [ ] **Step 5: Run the full frontend suite**

Run: `cd frontend && npm run test`
Expected: PASS — including `SessionsPage`/`ProjectsPage`/`SearchPage` tests (their `EventRenderer` calls are signature-compatible), the patch headline test, and the StreamPage suite.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/events/EventRow.tsx frontend/src/components/events/StreamRow.tsx frontend/src/components/events/EventCards.test.tsx
git commit -m "Route Tool Events Through The Presenter Layer"
```

---

### Task 14: Expand model — density toggle, exceptions set, ReaderText override

The §4.5 core: `streamDensity` persisted mode + `overrides: Set<string>` interpreted as **exceptions to the mode** — `expanded(id) = (mode === "expanded") !== overrides.has(id)` — so SSE rows arriving in expanded mode render expanded with zero bookkeeping.

**Files:**
- Create: `frontend/src/lib/streamDensity.ts`
- Modify: `frontend/src/pages/StreamPage.tsx` (`:50` signal, resets at `:82/:95/:103`, toggle UI at `:315-318`, row wiring at `:333-341`)
- Modify: `frontend/src/components/events/EventRow.tsx:74-89` (`ReaderText`)
- Modify: `frontend/src/pages/__tests__/StreamPage.test.tsx` (new density tests)
- Modify: `frontend/src/theme.css` (density toggle styles, appended)

**Interfaces:**
- Consumes: Task 13's `textExpanded` threading.
- Produces:
  - `type StreamDensity = "collapsed" | "expanded"`; `loadStreamDensity(): StreamDensity`; `saveStreamDensity(density: StreamDensity): void` (localStorage key `bb.streamDensity`).
  - `ReaderText(props: { text: string; expanded?: boolean })` — private signal becomes the per-instance override.

- [ ] **Step 1: Write the failing tests.** Append to `frontend/src/pages/__tests__/StreamPage.test.tsx` (inside the existing `describe`; also add `localStorage.clear();` at the top of the existing `beforeEach`):

```tsx
  it("expanded density expands every row and per-row toggling still overrides it", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([eventItem("event-1", "Make stream default"), eventItem("event-2", "Second row", "2026-07-01T11:58:00Z")]),
    );
    render(() => <StreamPage />);
    const rowOne = await screen.findByRole("button", { name: /Make stream default/ });
    const rowTwo = screen.getByRole("button", { name: /Second row/ });
    expect(rowOne).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(screen.getByRole("button", { name: "Expanded" }));
    expect(rowOne).toHaveAttribute("aria-expanded", "true");
    expect(rowTwo).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(rowOne); // exception to the mode
    expect(rowOne).toHaveAttribute("aria-expanded", "false");
    expect(rowTwo).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(screen.getByRole("button", { name: "Collapsed" })); // mode switch clears exceptions
    expect(rowOne).toHaveAttribute("aria-expanded", "false");
    expect(rowTwo).toHaveAttribute("aria-expanded", "false");
  });

  it("persists density to localStorage and restores it on mount", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(screen.getByRole("button", { name: "Expanded" }));
    expect(localStorage.getItem("bb.streamDensity")).toBe("expanded");
  });

  it("shows pending rows expanded when they arrive in expanded mode", async () => {
    localStorage.setItem("bb.streamDensity", "expanded");
    const old = eventItem("event-old", "Existing row");
    const fresh = eventItem("event-a", "Live row", "2026-07-01T12:01:00Z");
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValueOnce(feed([old])).mockResolvedValueOnce(feed([fresh, old]));

    try {
      render(() => <StreamPage />);
      await screen.findByRole("button", { name: /Existing row/ });
      const feedEl = document.querySelector(".stream-feed") as HTMLElement;
      feedEl.scrollTop = 120;
      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(500);
      fireEvent.click(screen.getByRole("button", { name: "1 new" }));
      expect(screen.getByRole("button", { name: /Live row/ })).toHaveAttribute("aria-expanded", "true");
    } finally {
      vi.useRealTimers();
    }
  });
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd frontend && npx vitest run src/pages/__tests__/StreamPage.test.tsx`
Expected: FAIL — no density toggle exists.

- [ ] **Step 3: Create `frontend/src/lib/streamDensity.ts`:**

```ts
const KEY = "bb.streamDensity";

export type StreamDensity = "collapsed" | "expanded";

export function loadStreamDensity(): StreamDensity {
  try {
    return localStorage.getItem(KEY) === "expanded" ? "expanded" : "collapsed";
  } catch {
    return "collapsed";
  }
}

export function saveStreamDensity(density: StreamDensity): void {
  try {
    localStorage.setItem(KEY, density);
  } catch {
    // Private-mode storage failures degrade to session-only density.
  }
}
```

- [ ] **Step 4: Rewire `StreamPage.tsx`.**
- Import: `import { loadStreamDensity, saveStreamDensity, type StreamDensity } from "../lib/streamDensity";`
- Replace line 50 `const [expandedId, setExpandedId] = createSignal<string | null>(null);` with:

```tsx
  const [density, setDensity] = createSignal<StreamDensity>(loadStreamDensity());
  // Exceptions to the density mode, not a list of expanded rows: absence means "follow the mode",
  // so SSE rows arriving in expanded mode render expanded with zero bookkeeping (spec §4.5).
  const [overrides, setOverrides] = createSignal<Set<string>>(new Set());

  const isExpanded = (id: string) => (density() === "expanded") !== overrides().has(id);

  function toggleRow(id: string) {
    setOverrides((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function switchDensity(next: StreamDensity) {
    if (next === density()) return;
    setDensity(next);
    saveStreamDensity(next);
    setOverrides(new Set<string>());
  }
```

- Replace the three `setExpandedId(null)` resets (lines 82, 95, 103 pre-change) with `setOverrides(new Set<string>());` — do NOT reset the density mode there; only exceptions clear on reload/refilter.
- Replace the row wiring (lines 333–341 pre-change):

```tsx
            {(item) => (
              <StreamRow
                item={item}
                expanded={isExpanded(item.id)}
                textExpanded={density() === "expanded"}
                sessionHref={sessionHref(item, props.project)}
                onToggle={() => toggleRow(item.id)}
              />
            )}
```

- Add the toggle beside the meaningful checkbox (after the `</label>` at line 318 pre-change):

```tsx
          <div class="density-toggle" role="group" aria-label="Stream density">
            <button
              type="button"
              classList={{ active: density() === "collapsed" }}
              onClick={() => switchDensity("collapsed")}
            >
              Collapsed
            </button>
            <button
              type="button"
              classList={{ active: density() === "expanded" }}
              onClick={() => switchDensity("expanded")}
            >
              Expanded
            </button>
          </div>
```

- [ ] **Step 5: Give `ReaderText` the override semantics** (EventRow.tsx):

```tsx
export function ReaderText(props: { text: string; expanded?: boolean }) {
  const [override, setOverride] = createSignal<boolean | null>(null);
  const expanded = () => override() ?? props.expanded ?? false;
  const compact = () => shouldCompactText(props.text);
  const collapsed = () => compact() && !expanded();

  return (
    <>
      <p classList={{ "reader-text": true, "reader-text--collapsed": collapsed() }}>{props.text}</p>
      <Show when={compact()}>
        <button type="button" class="reader-text-toggle" onClick={() => setOverride(!expanded())}>
          {collapsed() ? "Show full message" : "Collapse message"}
        </button>
      </Show>
    </>
  );
}
```

- [ ] **Step 6: Append density-toggle styles to `theme.css`:**

```css
.density-toggle { border: 1px solid var(--border); border-radius: var(--radius-pill); display: inline-flex; overflow: hidden; }
.density-toggle button { background: none; border: 0; color: var(--text-dim); cursor: pointer; font-size: 12px; padding: 3px 10px; }
.density-toggle button.active { background: var(--bg-selected); color: var(--text-bright); }
```

- [ ] **Step 7: Run the full frontend suite**

Run: `cd frontend && npm run test`
Expected: PASS — every pre-existing StreamPage test (they exercised `expandedId` only through `aria-expanded`, which the new model preserves in collapsed mode) plus the three new ones.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/lib/streamDensity.ts frontend/src/pages/StreamPage.tsx frontend/src/components/events/EventRow.tsx frontend/src/pages/__tests__/StreamPage.test.tsx frontend/src/theme.css
git commit -m "Add The Persisted Density Toggle With Exception-Set Expand Semantics"
```

---

### Task 15: E2E coverage

Real-browser proof of the two headline behaviors (§8): the expand-all toggle and a rendered diff. Uses the isolated e2e server (temp DB) — the Playwright `webServer` runs `mvn -q -Pfrontend -DskipTests package`, which is why Task 16 must redeploy the live service afterward.

**Files:**
- Modify: `frontend/tests/e2e/stream.spec.ts` (append two tests)

**Interfaces:**
- Consumes: `POST /api/events`, whose DTO is `EventIngestRequest` (`src/main/java/dev/nathan/sbaagentic/recording/EventIngestRequest.java`) with fields `toolName`, `toolInput`, `toolOutput` — the tool payloads are **objects, not pre-stringified JSON**, and the record is `@JsonIgnoreProperties(ignoreUnknown = true)`, so a wrongly-named field is silently dropped (the POST still 200s). `toolInputJson` exists only on the read side. Also consumes the Task 14 density toggle and the Task 12 diff renderer.
- Produces: nothing downstream.

- [ ] **Step 1: Append to `frontend/tests/e2e/stream.spec.ts`:**

```ts
test("density toggle expands every row and survives a reload", async ({ page }) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator(".stream-row").first()).toBeVisible();

  await page.getByRole("group", { name: "Stream density" }).getByRole("button", { name: "Expanded" }).click();
  const rows = page.locator(".stream-row");
  const rowCount = await rows.count();
  for (let index = 0; index < rowCount; index += 1) {
    await expect(rows.nth(index)).toHaveAttribute("aria-expanded", "true");
  }

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator(".stream-row").first()).toHaveAttribute("aria-expanded", "true");
});

test("an edit event renders a readable diff behind a lazy details block", async ({ page, request }) => {
  const seeded = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: "black-box-e2e-edit-diff",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "Edit",
      toolInput: {
        file_path: "/tmp/black-box-e2e/app.ts",
        old_string: "const total = 1;\nconst kept = 2;",
        new_string: "const total = 9;\nconst kept = 2;",
      },
      cwd: "/tmp/black-box-e2e",
      metadata: { title: "Edit diff seed" },
    },
  });
  expect(seeded.ok()).toBeTruthy();

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await page.getByLabel(/meaningful events only/i).uncheck(); // PostToolUse is filtered by default
  const row = page.locator(".stream-row").filter({ hasText: "app.ts" }).first();
  await expect(row).toBeVisible();
  await row.click();

  await page.locator(".detail-block--diff > summary").first().click();
  await expect(page.locator(".diff-line--del").first()).toContainText("const total = 1;");
  await expect(page.locator(".diff-line--add").first()).toContainText("const total = 9;");
});
```

- [ ] **Step 2: Run the e2e suite**

Run: `cd frontend && npm run e2e`
Expected: PASS (all specs, including the two smoke tests edited in Task 2). Note this rebuilds the jar — the live service is now running stale-or-swapped bits until Task 16 redeploys.

- [ ] **Step 3: Commit**

```bash
git add frontend/tests/e2e/stream.spec.ts
git commit -m "Cover The Density Toggle And Diff Rendering End To End"
```

---

### Task 16: Full gate, perf after-numbers, bundle, deploy, breadcrumb

**Files:**
- Modify: `docs/superpowers/plans/2026-07-28-stream-perf-notes.md` (after-numbers)
- Modify: `src/main/resources/static/**` (rebuilt bundle — scoped `git add -A` allowed here only)
- Modify: `NEXT.md` (state refresh)

**Interfaces:**
- Consumes: everything above.
- Produces: slice 1 shipped; acceptance criteria §14.1–3 and the §9 budget demonstrably met.

- [ ] **Step 1: Run the full gate**

Run: `mvn -q test && (cd frontend && npm run test && npm run build) && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)`
Expected: green end to end. Report any failure verbatim — do not proceed past a red gate.

- [ ] **Step 2: Rebuild + commit the bundle**

```bash
(cd frontend && npm run build)
git add -A src/main/resources/static
git commit -m "Rebuild The Frontend Bundle For Stream Presenters"
```

(Skip the commit if `git status` shows no static changes — but it will, the bundle content changed.)

- [ ] **Step 3: Deploy locally and verify the live service**

Run: `scripts/deploy-local.sh`
Then: `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8766/` → expect `200`. If 500s: `launchctl kickstart -k` the service per `NEXT.md`, re-check.

- [ ] **Step 4: Measure the after-numbers** — re-run the Task 1 script (`cd frontend && node measure-stream.mjs`, same method, same medians), plus expand-all:

Append to the Task 1 script before `browser.close()` and re-run:

```js
// Expand-all interaction time at MAX_ROWS.
const toggleStart = Date.now();
await page.getByRole("group", { name: "Stream density" }).getByRole("button", { name: "Expanded" }).click();
await page.locator(".stream-row[aria-expanded='true']").last().waitFor();
const expandAllMs = Date.now() - toggleStart;
console.log(JSON.stringify({ expandAllMs }, null, 2));
```

Fill in the "After slice 1" section of `2026-07-28-stream-perf-notes.md`. **Budget check (§9):** first-row time not worse than baseline; expand-all < 100ms. If expand-all misses the budget, the sanctioned fallback is a lower `MAX_ROWS` in expanded mode (spec §9) — raise this as a finding rather than silently shipping. When the numbers are recorded, delete the throwaway tool: `rm frontend/measure-stream.mjs`.

- [ ] **Step 5: Verify acceptance criteria against the live app** (spec §14, the slice-1 subset): open `http://127.0.0.1:8766/` and confirm by use — (1) Collapsed/Expanded toggle persists across reload and per-row toggling overrides it; (2) Bash/Edit/Write/Read/apply_patch render structured with tones, unknown tools render as before; (3) Edit and apply_patch show readable diffs.

- [ ] **Step 6: Update `NEXT.md`** — record slice 1 shipped, perf numbers, and that slices 2 (recall links) and 3 (file links) are next per the spec's §13 ordering.

- [ ] **Step 7: Commit docs + breadcrumb**

```bash
git add docs/superpowers/plans/2026-07-28-stream-perf-notes.md NEXT.md
git commit -m "Ship Slice 1: Stream Presenters And The Expand Toggle"
```

Then capture a Black Box `Handoff` (via `mcp__sba-agentic__captureHandoff`) scoped to `/Users/nathan/Developer/proj/sba-agentic`: branch, HEAD SHA, files/areas changed, gate result, perf numbers, live-service state, and next action (slice 2).

---

## Review provenance

Beyond self-review, this plan survived a 3-reviewer adversarial pass (code-vs-repo correctness, spec coverage, cold-execution consistency) on 2026-07-28. Eleven findings, six distinct defects, all fixed in place: fixtures SQL table name (`agent_events`, not `events`), e2e ingest field names (`toolInput` object — `EventIngestRequest` silently drops unknown fields), `isRecord` scope in the payload extraction, `export…from` needing a local import in `eventData.ts`, the Task 2 grep expectation + dead `getDashboardStats` client, and ESM resolution for the perf script.

## Self-review notes (already applied)

- **Spec coverage:** §4.1 (Task 7, 12), §4.2 slice-1 presenters (Tasks 8–11), §4.3 (Tasks 4, 5, 10, 12), §4.5 (Tasks 13, 14), slice-1 deletions from §4.10 (Task 2), §7 error handling (Tasks 5, 7, 8–11), §8 test layers (unit: 3–11; components: 12–14; e2e: 15), §9 measured budget (Tasks 1, 16). Out of scope, deliberately: §4.4 file-link backend (slice 3 — `fileLink` spans copy the path until then), §4.6 live (slice 4), §4.7 narrative + remaining presenters (slice 5), §4.9 recall links (slice 2), route promotion/Memory merge (slice 6).
- **Type consistency:** `FileRef`/`DetailBlock`/`Presentation` defined once in Task 7 and imported everywhere; `Hunk` defined once in Task 4 and reused by patch/patch-render; `textExpanded` threads StreamPage → StreamRow → EventRenderer → EventRow → ReaderText with one name.
- **Known intentional behavior changes:** Bash/Edit/Write/Read/apply_patch events stop rendering the generic Input/Result field grid (replaced by typed blocks); `/stats`, `/overview` return 404 on hard refresh; everything else renders byte-identical to today via the fallback path.
