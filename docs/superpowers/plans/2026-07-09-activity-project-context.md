# Activity Project Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Activity project-aware by adding a shared project picker, hidden project scoping for Stream and Find, a flat project-scoped Browse rail, negative facet filters, and Find-result target highlighting.

**Architecture:** Keep SQLite and existing REST endpoints as the source of truth. Use `GET /api/projects` and `GET /api/projects/{projectKey}/sessions` for project context, translate selected project context into an internal `project:` facet for Stream and Find, and leave Ask unscoped in the first pass with a visible notice. Extend the shared backend/frontend query parsers before wiring UI so Stream and Find keep identical positive and negative facet semantics.

**Tech Stack:** Java 21, Spring Boot, SQLite/JdbcTemplate, SolidJS, Vite, TypeScript, Vitest, Testing Library, Maven.

---

## Scope Decisions

- Project context scopes Stream, Browse, and Find in this plan.
- Ask remains available, but shows that the selected Activity project is not applied to Ask yet.
- Project autocomplete uses existing derived projects from `GET /api/projects`; no alias editing or merge UI.
- Browse uses a flat session rail in both project and All Projects states. Existing project timeline/meld endpoints remain untouched.
- Find result navigation uses loaded session events and DOM ids shaped as `event-${event.id}` first. This plan does not add a targeted event endpoint.

## File Structure

- Modify `src/main/java/dev/nathan/sbaagentic/search/QueryFacets.java`: parse positive and negative facet tokens.
- Modify `src/main/java/dev/nathan/sbaagentic/event/EventRepository.java`: apply positive and negative facets consistently in `searchEvents` and `feed`.
- Modify backend tests:
  - `src/test/java/dev/nathan/sbaagentic/search/QueryFacetsTest.java`
  - `src/test/java/dev/nathan/sbaagentic/event/EventSearchFacetTest.java`
  - `src/test/java/dev/nathan/sbaagentic/event/EventFeedTest.java`
  - `src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java`
- Modify `frontend/src/lib/query.ts` and `frontend/src/lib/query.test.ts`: add excluded facets and canonical serialization.
- Create `frontend/src/lib/projects.ts` and `frontend/src/lib/projects.test.ts`: project display, matching, ranking, remembered state, and session/project comparisons.
- Create `frontend/src/components/ProjectPicker.tsx` and `frontend/src/components/ProjectPicker.test.tsx`: shared Activity project picker.
- Modify Activity pages:
  - `frontend/src/pages/ActivityPage.tsx`
  - `frontend/src/pages/StreamPage.tsx`
  - `frontend/src/pages/SearchPage.tsx`
  - `frontend/src/pages/SessionsPage.tsx`
- Modify page tests:
  - `frontend/src/pages/__tests__/ActivityPage.test.tsx`
  - `frontend/src/pages/__tests__/StreamPage.test.tsx`
  - `frontend/src/pages/__tests__/SearchPage.test.tsx`
  - `frontend/src/pages/__tests__/SessionsPage.test.tsx`
- Modify `frontend/src/theme.css`: project picker, exclude chips, flat rail cleanup, and target highlight styles.

## Task 1: Backend Negative Facet Grammar

**Files:**
- Modify: `src/test/java/dev/nathan/sbaagentic/search/QueryFacetsTest.java`
- Modify: `src/test/java/dev/nathan/sbaagentic/event/EventSearchFacetTest.java`
- Modify: `src/test/java/dev/nathan/sbaagentic/event/EventFeedTest.java`
- Modify: `src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/search/QueryFacets.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/event/EventRepository.java`

- [ ] **Step 1: Add failing parser tests**

Add these tests to `QueryFacetsTest`:

```java
@Test
void parsesReadableAndTerseNegativeFacets() {
    QueryFacets readable = QueryFacets.parse("source:codex NOT kind:PostToolUse recall");
    assertThat(readable.source()).isEqualTo("codex");
    assertThat(readable.excludedEventType()).isEqualTo("PostToolUse");
    assertThat(readable.freeText()).containsExactly("recall");
    assertThat(readable.hasAnyFacet()).isTrue();

    QueryFacets terse = QueryFacets.parse("-tool:Read -project:\"/tmp/sba agentic\"");
    assertThat(terse.excludedToolName()).isEqualTo("Read");
    assertThat(terse.excludedCwd()).isEqualTo("/tmp/sba agentic");
    assertThat(terse.freeText()).isEmpty();
}

@Test
void danglingNotFallsBackToFreeText() {
    QueryFacets f = QueryFacets.parse("NOT recall bug");
    assertThat(f.hasAnyFacet()).isFalse();
    assertThat(f.freeText()).containsExactly("NOT", "recall", "bug");
}
```

- [ ] **Step 2: Run parser tests and verify failure**

Run:

```bash
mvn -q -Dtest=QueryFacetsTest test
```

Expected: fails because `excludedEventType()`, `excludedToolName()`, and `excludedCwd()` do not exist.

- [ ] **Step 3: Extend `QueryFacets`**

In `QueryFacets.java`, add excluded fields beside the existing positive fields:

```java
private final String excludedSource;
private final String excludedEventType;
private final String excludedToolName;
private final String excludedCwd;
```

Change the constructor signature to carry both sets:

```java
private QueryFacets(
        String source,
        String eventType,
        String toolName,
        String cwd,
        String excludedSource,
        String excludedEventType,
        String excludedToolName,
        String excludedCwd,
        List<String> freeText) {
    this.source = source;
    this.eventType = eventType;
    this.toolName = toolName;
    this.cwd = cwd;
    this.excludedSource = excludedSource;
    this.excludedEventType = excludedEventType;
    this.excludedToolName = excludedToolName;
    this.excludedCwd = excludedCwd;
    this.freeText = List.copyOf(freeText);
}
```

Replace `parse` with this behavior:

```java
public static QueryFacets parse(String query) {
    String source = null;
    String eventType = null;
    String toolName = null;
    String cwd = null;
    String excludedSource = null;
    String excludedEventType = null;
    String excludedToolName = null;
    String excludedCwd = null;
    List<String> free = new ArrayList<>();
    boolean negateNext = false;

    for (String token : tokenize(query == null ? "" : query)) {
        if ("NOT".equalsIgnoreCase(token)) {
            if (negateNext) {
                free.add("NOT");
            }
            negateNext = true;
            continue;
        }

        boolean leadingMinus = token.startsWith("-") && token.length() > 1;
        boolean negated = negateNext || leadingMinus;
        String candidate = leadingMinus ? token.substring(1) : token;
        Matcher m = FACET.matcher(candidate);
        if (m.matches()) {
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String value = stripQuotes(m.group(2)).trim();
            if (!value.isEmpty()) {
                switch (field) {
                    case "source", "agent" -> {
                        if (negated) excludedSource = value;
                        else source = value;
                    }
                    case "kind", "event_type" -> {
                        if (negated) excludedEventType = value;
                        else eventType = value;
                    }
                    case "tool", "tool_name" -> {
                        if (negated) excludedToolName = value;
                        else toolName = value;
                    }
                    case "project", "cwd" -> {
                        if (negated) excludedCwd = value;
                        else cwd = value;
                    }
                    default -> { }
                }
                negateNext = false;
                continue;
            }
        }

        if (negateNext) {
            free.add("NOT");
            negateNext = false;
        }
        String term = stripQuotes(token).trim();
        if (!term.isEmpty()) {
            free.add(term);
        }
    }
    if (negateNext) {
        free.add("NOT");
    }
    return new QueryFacets(source, eventType, toolName, cwd,
            excludedSource, excludedEventType, excludedToolName, excludedCwd, free);
}
```

Update `hasAnyFacet` and add getters:

```java
public boolean hasAnyFacet() {
    return source != null || eventType != null || toolName != null || cwd != null
            || excludedSource != null || excludedEventType != null
            || excludedToolName != null || excludedCwd != null;
}

public String excludedSource() {
    return excludedSource;
}

public String excludedEventType() {
    return excludedEventType;
}

public String excludedToolName() {
    return excludedToolName;
}

public String excludedCwd() {
    return excludedCwd;
}
```

- [ ] **Step 4: Run parser tests**

Run:

```bash
mvn -q -Dtest=QueryFacetsTest test
```

Expected: pass.

- [ ] **Step 5: Add failing repository tests**

Add this test to `EventSearchFacetTest`:

```java
@Test
void negativeFacetsExcludeMatchingRows() {
    String codexId = seedDecisionFromCodex();
    String claudeId = seedToolFromClaude();

    List<String> noToolNoise = repository.searchEvents("NOT kind:PostToolUse UI rewrite", 50)
            .stream().map(AgentEvent::id).toList();
    assertThat(noToolNoise).contains(codexId).doesNotContain(claudeId);

    List<String> noCodex = repository.searchEvents("-source:codex rewrite", 50)
            .stream().map(AgentEvent::id).toList();
    assertThat(noCodex).contains(claudeId).doesNotContain(codexId);
}
```

Add this test to `EventFeedTest`:

```java
@Test
void feedHonorsNegativeFacets() {
    String key = uniqueKey("negative");
    SeededEvent decision = seed(key, "codex", key + "-decision", "Decision", "assistant",
            "Negative facet target " + key, "/tmp/" + key + "/alpha", null,
            Instant.parse("2026-07-01T12:00:00Z"));
    SeededEvent tool = seed(key, "codex", key + "-tool", "PostToolUse", "assistant",
            "Negative facet tool " + key, "/tmp/" + key + "/alpha", "Edit",
            Instant.parse("2026-07-01T12:01:00Z"));
    SeededEvent otherProject = seed(key, "claude", key + "-other", "Decision", "assistant",
            "Negative facet other project " + key, "/tmp/" + key + "/beta", null,
            Instant.parse("2026-07-01T12:02:00Z"));

    assertThat(repository.feed("NOT kind:PostToolUse " + key, false, null, null, 10).items())
            .extracting(EventFeedItem::id)
            .contains(decision.id(), otherProject.id())
            .doesNotContain(tool.id());

    assertThat(repository.feed("-project:" + key + "/beta " + key, false, null, null, 10).items())
            .extracting(EventFeedItem::id)
            .contains(decision.id())
            .doesNotContain(otherProject.id());
}
```

Add this assertion to `AgenticControllerTest.eventFeedEndpointReturnsEnvelopeFiltersAndClientErrors` after the meaningful check:

```java
mockMvc.perform(get("/api/events")
                .param("q", "NOT kind:UserPromptSubmit " + key)
                .param("meaningful", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].eventType").value("Decision"));
```

- [ ] **Step 6: Run repository tests and verify failure**

Run:

```bash
mvn -q -Dtest=EventSearchFacetTest,EventFeedTest,AgenticControllerTest test
```

Expected: fails because `EventRepository` ignores excluded facets.

- [ ] **Step 7: Apply negative facets in `EventRepository.searchEvents` and `feed`**

In `searchEvents`, make the session join depend on either positive or negative project filters:

```java
boolean joinSessions = facets.cwd() != null || facets.excludedCwd() != null;
```

After existing positive facet clauses, add negative clauses:

```java
if (facets.excludedSource() != null) {
    sql.append("   AND lower(e.source) <> lower(?)\n");
    args.add(facets.excludedSource());
}
if (facets.excludedEventType() != null) {
    sql.append("   AND lower(e.event_type) <> lower(?)\n");
    args.add(facets.excludedEventType());
}
if (facets.excludedToolName() != null) {
    sql.append("   AND lower(coalesce(e.tool_name, '')) <> lower(?)\n");
    args.add(facets.excludedToolName());
}
if (facets.excludedCwd() != null) {
    sql.append("   AND lower(coalesce(s.cwd, '')) NOT LIKE lower(?)\n");
    args.add("%" + facets.excludedCwd() + "%");
}
```

In `feed`, add the same negative clauses after the existing positive clauses. `feed` already joins
`agent_sessions s`, so no join change is needed there.

- [ ] **Step 8: Run backend targeted tests**

Run:

```bash
mvn -q -Dtest=QueryFacetsTest,EventSearchFacetTest,EventFeedTest,AgenticControllerTest test
```

Expected: pass.

- [ ] **Step 9: Commit backend query work**

```bash
git add src/main/java/dev/nathan/sbaagentic/search/QueryFacets.java \
  src/main/java/dev/nathan/sbaagentic/event/EventRepository.java \
  src/test/java/dev/nathan/sbaagentic/search/QueryFacetsTest.java \
  src/test/java/dev/nathan/sbaagentic/event/EventSearchFacetTest.java \
  src/test/java/dev/nathan/sbaagentic/event/EventFeedTest.java \
  src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java
git commit -m "Add Negative Facet Filtering"
```

## Task 2: Frontend Query Parser And Exclude Chips

**Files:**
- Modify: `frontend/src/lib/query.ts`
- Modify: `frontend/src/lib/query.test.ts`
- Modify: `frontend/src/pages/SearchPage.tsx`
- Modify: `frontend/src/pages/StreamPage.tsx`
- Modify: `frontend/src/pages/__tests__/SearchPage.test.tsx`
- Modify: `frontend/src/pages/__tests__/StreamPage.test.tsx`
- Modify: `frontend/src/theme.css`

- [ ] **Step 1: Add failing frontend query tests**

Add tests to `frontend/src/lib/query.test.ts`:

```ts
it("parses readable and terse negative facets", () => {
  expect(parseQuery("source:codex NOT kind:PostToolUse -tool:Read recall")).toEqual({
    facets: { source: "codex" },
    excludeFacets: { kind: "PostToolUse", tool: "Read" },
    text: ["recall"],
  });
});

it("serializes negative facets with readable NOT syntax", () => {
  expect(
    serializeQuery({
      facets: { source: "codex" },
      excludeFacets: { kind: "PostToolUse" },
      text: ["recall"],
    }),
  ).toBe("source:codex NOT kind:PostToolUse recall");
});

it("removes include and exclude facets independently", () => {
  expect(setFacet("source:codex NOT kind:PostToolUse", "kind", null, "exclude")).toBe("source:codex");
  expect(setFacet("source:codex NOT kind:PostToolUse", "source", null)).toBe("NOT kind:PostToolUse");
});
```

Update existing expected parser objects in `query.test.ts` to include `excludeFacets: {}`.

- [ ] **Step 2: Run query tests and verify failure**

Run:

```bash
cd frontend && npm run test -- src/lib/query.test.ts
```

Expected: fails because `excludeFacets` and the fourth `setFacet` argument do not exist.

- [ ] **Step 3: Extend `frontend/src/lib/query.ts`**

Change the types:

```ts
export type QueryState = {
  facets: Partial<Record<FacetField["key"], string>>;
  excludeFacets: Partial<Record<FacetField["key"], string>>;
  text: string[];
};

export type FacetMode = "include" | "exclude";
```

Update `parseQuery` with the same negative-token behavior as the backend:

```ts
export function parseQuery(q: string): QueryState {
  const facets: Partial<Record<FacetField["key"], string>> = {};
  const excludeFacets: Partial<Record<FacetField["key"], string>> = {};
  const text: string[] = [];
  let negateNext = false;

  for (const token of tokenize(q)) {
    if (token.toLowerCase() === "not") {
      if (negateNext) text.push("NOT");
      negateNext = true;
      continue;
    }
    const leadingMinus = token.startsWith("-") && token.length > 1;
    const negated = negateNext || leadingMinus;
    const candidate = leadingMinus ? token.slice(1) : token;
    const separator = candidate.indexOf(":");
    if (separator > 0) {
      const rawField = candidate.slice(0, separator);
      const field = ALIASES[rawField];
      const value = candidate.slice(separator + 1);
      if (field && value) {
        if (negated) excludeFacets[field] = value;
        else facets[field] = value;
        negateNext = false;
        continue;
      }
    }
    if (negateNext) {
      text.push("NOT");
      negateNext = false;
    }
    if (token) text.push(token);
  }
  if (negateNext) text.push("NOT");
  return { facets, excludeFacets, text };
}
```

Update `serializeQuery`:

```ts
export function serializeQuery(state: QueryState): string {
  const parts: string[] = [];
  for (const field of FACET_FIELDS) {
    const value = state.facets[field.key];
    if (value) parts.push(`${field.key}:${quoteToken(value)}`);
  }
  for (const field of FACET_FIELDS) {
    const value = state.excludeFacets[field.key];
    if (value) parts.push(`NOT ${field.key}:${quoteToken(value)}`);
  }
  for (const token of state.text) {
    if (token) parts.push(quoteToken(token));
  }
  return parts.join(" ");
}
```

Update `setFacet`:

```ts
export function setFacet(
  query: string,
  key: FacetField["key"],
  value: string | null,
  mode: FacetMode = "include",
): string {
  const parsed = parseQuery(query);
  const target = mode === "exclude" ? parsed.excludeFacets : parsed.facets;
  const opposite = mode === "exclude" ? parsed.facets : parsed.excludeFacets;
  if (!value) {
    delete target[key];
  } else {
    target[key] = value;
    if (opposite[key] === value) delete opposite[key];
  }
  return serializeQuery(parsed);
}
```

- [ ] **Step 4: Run query tests**

Run:

```bash
cd frontend && npm run test -- src/lib/query.test.ts
```

Expected: pass.

- [ ] **Step 5: Add exclude chip tests to Search and Stream**

In `SearchPage.test.tsx`, add:

```ts
it("renders and removes exclude facet chips", async () => {
  [params, setParams] = createStore<{ q?: string }>({ q: "NOT kind:PostToolUse" });
  render(() => <SearchPage />);

  const chip = await screen.findByRole("button", { name: "kind != PostToolUse" });
  expect(chip).toHaveClass("facet-chip--exclude");

  fireEvent.click(chip);
  await waitFor(() => expect(params.q).toBeUndefined());
});
```

In `StreamPage.test.tsx`, add:

```ts
it("renders exclude chips from negative facets", async () => {
  [params, setParams] = createStore<{ q?: string }>({ q: "-kind:PostToolUse" });
  render(() => <StreamPage />);
  await screen.findByRole("link", { name: /Make stream default/ });

  expect(screen.getByRole("button", { name: "kind != PostToolUse" })).toBeInTheDocument();
  expect(getEventFeed).toHaveBeenCalledWith({ limit: 100, q: "-kind:PostToolUse", meaningful: true });
});
```

- [ ] **Step 6: Run page tests and verify failure**

Run:

```bash
cd frontend && npm run test -- src/pages/__tests__/SearchPage.test.tsx src/pages/__tests__/StreamPage.test.tsx
```

Expected: fails because pages only render positive chips.

- [ ] **Step 7: Render exclude chips in `SearchPage` and `StreamPage`**

In both files, after the existing active positive chip branch, render negative chips:

```tsx
<Show when={parsed().excludeFacets[field.key]}>
  {(value) => (
    <button
      type="button"
      class="facet-chip facet-chip--active facet-chip--exclude"
      onClick={() => applyFacet(field.key, null, "exclude")}
    >
      {field.key} != {value()} x
    </button>
  )}
</Show>
```

Change each local `applyFacet` signature:

```ts
function applyFacet(key: FacetField["key"], value: string | null, mode: "include" | "exclude" = "include") {
  run(setFacet(submitted(), key, value, mode));
}
```

In `SearchPage`, normalize empty searches the same way `StreamPage` already does:

```ts
function run(next: string) {
  setParams({ q: next.trim() || undefined });
}
```

In the existing positive chip fallback, do not hide the entire facet group only because an exclude
facet exists. Show an include chip when present, an exclude chip when present, and the quick values
only when neither is present.

Add CSS:

```css
.facet-chip--exclude {
  color: var(--yellow);
  background: color-mix(in srgb, var(--yellow) 12%, var(--bg-surface));
  border-color: color-mix(in srgb, var(--yellow) 42%, var(--border));
}
```

- [ ] **Step 8: Run frontend targeted tests**

Run:

```bash
cd frontend && npm run test -- src/lib/query.test.ts src/pages/__tests__/SearchPage.test.tsx src/pages/__tests__/StreamPage.test.tsx
```

Expected: pass.

- [ ] **Step 9: Commit frontend query work**

```bash
git add frontend/src/lib/query.ts frontend/src/lib/query.test.ts \
  frontend/src/pages/SearchPage.tsx frontend/src/pages/StreamPage.tsx \
  frontend/src/pages/__tests__/SearchPage.test.tsx frontend/src/pages/__tests__/StreamPage.test.tsx \
  frontend/src/theme.css
git commit -m "Add Negative Facet Chips"
```

## Task 3: Project Helpers And Picker Component

**Files:**
- Create: `frontend/src/lib/projects.ts`
- Create: `frontend/src/lib/projects.test.ts`
- Create: `frontend/src/components/ProjectPicker.tsx`
- Create: `frontend/src/components/ProjectPicker.test.tsx`
- Modify: `frontend/src/theme.css`

- [ ] **Step 1: Add project helper tests**

Create `frontend/src/lib/projects.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import type { AgentSession, ProjectSummary } from "./api";
import {
  clearRememberedProjectKey,
  projectMatchesSession,
  projectSearchText,
  projectShortName,
  rankProjects,
  readRememberedProjectKey,
  rememberProjectKey,
} from "./projects";

const projects: ProjectSummary[] = [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 4,
    eventCount: 120,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T20:00:00Z",
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 2,
    eventCount: 40,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T21:00:00Z",
  },
];

describe("project helpers", () => {
  it("derives short names and searchable text", () => {
    expect(projectShortName(projects[0])).toBe("sba-agentic");
    expect(projectSearchText(projects[0])).toContain("sba-agentic");
    expect(projectSearchText(projects[0])).toContain("/users/nathan/developer/proj/sba-agentic");
  });

  it("ranks recent fuzzy matches by short name and path", () => {
    expect(rankProjects(projects, "sba").map((p) => p.projectKey)).toEqual(["sba-key"]);
    expect(rankProjects(projects, "proj").map((p) => p.projectKey)).toEqual(["cockpit-key", "sba-key"]);
  });

  it("matches sessions by canonical project path", () => {
    const session = { cwd: "/Users/nathan/Developer/proj/sba-agentic/" } as AgentSession;
    expect(projectMatchesSession(projects[0], session)).toBe(true);
    expect(projectMatchesSession(projects[1], session)).toBe(false);
  });

  it("remembers and clears the last project key", () => {
    vi.stubGlobal("localStorage", window.localStorage);
    rememberProjectKey("sba-key");
    expect(readRememberedProjectKey()).toBe("sba-key");
    clearRememberedProjectKey();
    expect(readRememberedProjectKey()).toBeNull();
  });
});
```

- [ ] **Step 2: Implement `frontend/src/lib/projects.ts`**

Create:

```ts
import type { AgentSession, ProjectSummary } from "./api";

const REMEMBERED_PROJECT_KEY = "blackbox.activity.projectKey";

export function projectShortName(project: ProjectSummary): string {
  const value = project.canonicalKey || project.label || project.projectKey;
  const parts = value.split("/").filter(Boolean);
  return parts[parts.length - 1] || value;
}

export function projectSearchText(project: ProjectSummary): string {
  return [projectShortName(project), project.label, project.canonicalKey, project.projectKey]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

export function rankProjects(projects: ProjectSummary[], query: string): ProjectSummary[] {
  const needle = query.trim().toLowerCase();
  const ranked = [...projects].sort((left, right) => timestampValue(right.lastSeenAt) - timestampValue(left.lastSeenAt));
  if (!needle) return ranked;
  return ranked
    .map((project) => ({ project, score: scoreProject(project, needle) }))
    .filter((entry) => entry.score > 0)
    .sort((left, right) => right.score - left.score || timestampValue(right.project.lastSeenAt) - timestampValue(left.project.lastSeenAt))
    .map((entry) => entry.project);
}

export function projectMatchesSession(project: ProjectSummary, session: Pick<AgentSession, "cwd">): boolean {
  return canonicalizeProjectPath(session.cwd) === canonicalizeProjectPath(project.canonicalKey);
}

export function canonicalizeProjectPath(value: string | null | undefined): string {
  const trimmed = (value ?? "").trim();
  if (!trimmed) return "__no_project__";
  return trimmed.length > 1 ? trimmed.replace(/\/+$/u, "") : trimmed;
}

export function rememberProjectKey(projectKey: string | null | undefined): void {
  try {
    if (projectKey) localStorage.setItem(REMEMBERED_PROJECT_KEY, projectKey);
    else localStorage.removeItem(REMEMBERED_PROJECT_KEY);
  } catch {
    // Local storage is best-effort UI state.
  }
}

export function readRememberedProjectKey(): string | null {
  try {
    return localStorage.getItem(REMEMBERED_PROJECT_KEY);
  } catch {
    return null;
  }
}

export function clearRememberedProjectKey(): void {
  rememberProjectKey(null);
}

function scoreProject(project: ProjectSummary, needle: string): number {
  const short = projectShortName(project).toLowerCase();
  if (short === needle) return 100;
  if (short.startsWith(needle)) return 80;
  if (short.includes(needle)) return 60;
  return projectSearchText(project).includes(needle) ? 30 : 0;
}

function timestampValue(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}
```

- [ ] **Step 3: Run helper tests**

Run:

```bash
cd frontend && npm run test -- src/lib/projects.test.ts
```

Expected: pass.

- [ ] **Step 4: Add ProjectPicker component tests**

Create `frontend/src/components/ProjectPicker.test.tsx`:

```tsx
import { fireEvent, render, screen, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { ProjectSummary } from "../lib/api";
import ProjectPicker from "./ProjectPicker";

const projects: ProjectSummary[] = [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 4,
    eventCount: 120,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T20:00:00Z",
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 2,
    eventCount: 40,
    savedMeldCount: 0,
    lastSeenAt: "2026-07-08T21:00:00Z",
  },
];

describe("ProjectPicker", () => {
  it("selects all projects and fuzzy project matches", async () => {
    const onSelect = vi.fn();
    render(() => <ProjectPicker projects={projects} selectedProjectKey={undefined} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: /All projects/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "sba" } });

    const listbox = screen.getByRole("listbox", { name: "Project results" });
    expect(within(listbox).getByText("sba-agentic")).toBeInTheDocument();
    expect(within(listbox).getByText("~/Developer/proj/sba-agentic")).toBeInTheDocument();

    fireEvent.click(within(listbox).getByRole("option", { name: /sba-agentic/ }));
    expect(onSelect).toHaveBeenCalledWith("sba-key");

    fireEvent.click(screen.getByRole("button", { name: /sba-agentic/ }));
    fireEvent.click(screen.getByRole("button", { name: "All projects" }));
    expect(onSelect).toHaveBeenLastCalledWith(undefined);
  });
});
```

- [ ] **Step 5: Implement `frontend/src/components/ProjectPicker.tsx`**

Create:

```tsx
import { createMemo, createSignal, For, Show } from "solid-js";
import type { ProjectSummary } from "../lib/api";
import { projectShortName, rankProjects } from "../lib/projects";

type ProjectPickerProps = {
  projects: ProjectSummary[];
  selectedProjectKey?: string;
  loading?: boolean;
  error?: string | null;
  onSelect: (projectKey: string | undefined) => void;
};

export default function ProjectPicker(props: ProjectPickerProps) {
  const [open, setOpen] = createSignal(false);
  const [query, setQuery] = createSignal("");
  const selected = createMemo(() => props.projects.find((project) => project.projectKey === props.selectedProjectKey));
  const results = createMemo(() => rankProjects(props.projects, query()).slice(0, 12));

  function choose(projectKey: string | undefined) {
    props.onSelect(projectKey);
    setOpen(false);
    setQuery("");
  }

  return (
    <div class="project-picker">
      <button type="button" class="project-picker-button" onClick={() => setOpen((current) => !current)}>
        <span class="project-picker-label">Project</span>
        <strong>{selected() ? projectShortName(selected()!) : "All projects"}</strong>
        <small>{selected()?.label || "Global activity"}</small>
      </button>
      <Show when={open()}>
        <div class="project-picker-popover">
          <button type="button" class="project-picker-all" onClick={() => choose(undefined)}>
            All projects
          </button>
          <input
            aria-label="Search projects"
            value={query()}
            onInput={(event) => setQuery(event.currentTarget.value)}
            placeholder="sba-agentic or full path"
            autocomplete="off"
          />
          <Show when={!props.error} fallback={<p class="project-picker-empty">{props.error}</p>}>
            <Show when={!props.loading} fallback={<p class="project-picker-empty">Loading projects...</p>}>
              <ul role="listbox" aria-label="Project results" class="project-picker-results">
                <For each={results()}>
                  {(project) => (
                    <li>
                      <button
                        type="button"
                        role="option"
                        aria-selected={project.projectKey === props.selectedProjectKey}
                        onClick={() => choose(project.projectKey)}
                      >
                        <strong>{projectShortName(project)}</strong>
                        <span>{project.label}</span>
                        <small>{project.sessionCount.toLocaleString()} sessions · {project.eventCount.toLocaleString()} events</small>
                      </button>
                    </li>
                  )}
                </For>
              </ul>
            </Show>
          </Show>
        </div>
      </Show>
    </div>
  );
}
```

- [ ] **Step 6: Add picker CSS**

Add to `frontend/src/theme.css` near Activity styles:

```css
.project-picker { position: relative; min-width: min(420px, 100%); }
.project-picker-button {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 2px 10px;
  width: 100%;
  min-height: 48px;
  padding: 8px 10px;
  text-align: left;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.project-picker-label {
  grid-row: span 2;
  align-self: center;
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: 10px;
  text-transform: uppercase;
}
.project-picker-button strong,
.project-picker-button small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.project-picker-button strong { color: var(--text-bright); font-weight: 600; }
.project-picker-button small { color: var(--text-dim); font-family: var(--font-mono); font-size: 11px; }
.project-picker-popover {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 35;
  width: min(520px, calc(100vw - 32px));
  padding: 8px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: 0 18px 42px rgba(0,0,0,0.48);
}
.project-picker-popover input {
  width: 100%;
  height: 34px;
  margin: 6px 0;
  padding: 0 10px;
  color: var(--text-bright);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.project-picker-all,
.project-picker-results button {
  width: 100%;
  padding: 8px;
  text-align: left;
  background: transparent;
  border-radius: var(--radius);
  color: var(--text);
}
.project-picker-all:hover,
.project-picker-results button:hover { background: var(--bg-hover); }
.project-picker-results { max-height: 320px; overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.project-picker-results strong,
.project-picker-results span,
.project-picker-results small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-picker-results strong { color: var(--text-bright); }
.project-picker-results span,
.project-picker-results small { color: var(--text-dim); font-family: var(--font-mono); font-size: 11px; }
.project-picker-empty { margin: 8px; color: var(--text-dim); font-size: 12px; }
```

- [ ] **Step 7: Run picker tests**

Run:

```bash
cd frontend && npm run test -- src/lib/projects.test.ts src/components/ProjectPicker.test.tsx
```

Expected: pass.

- [ ] **Step 8: Commit project picker primitives**

```bash
git add frontend/src/lib/projects.ts frontend/src/lib/projects.test.ts \
  frontend/src/components/ProjectPicker.tsx frontend/src/components/ProjectPicker.test.tsx \
  frontend/src/theme.css
git commit -m "Add Activity Project Picker Primitives"
```

## Task 4: Activity Project Context And Stream/Find Scoping

**Files:**
- Modify: `frontend/src/pages/ActivityPage.tsx`
- Modify: `frontend/src/pages/StreamPage.tsx`
- Modify: `frontend/src/pages/SearchPage.tsx`
- Modify: `frontend/src/pages/__tests__/ActivityPage.test.tsx`
- Modify: `frontend/src/pages/__tests__/StreamPage.test.tsx`
- Modify: `frontend/src/pages/__tests__/SearchPage.test.tsx`
- Modify: `frontend/src/theme.css`

- [ ] **Step 1: Add failing Activity tests**

In `ActivityPage.test.tsx`, extend the search params type with `project?: string; event?: string`.
Mock `getProjects`:

```ts
getProjects: vi.fn(async () => [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 1,
    eventCount: 4,
    savedMeldCount: 0,
    lastSeenAt: "2026-06-22T20:10:00Z",
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 1,
    eventCount: 8,
    savedMeldCount: 0,
    lastSeenAt: "2026-06-22T19:20:00Z",
  },
]),
```

Add:

```tsx
it("selects a shared Activity project and stores it in the URL", async () => {
  render(() => <ActivityPage />);

  fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
  fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "sba" } });
  fireEvent.click(await screen.findByRole("option", { name: /sba-agentic/ }));

  await waitFor(() => expect(params.project).toBe("sba-key"));
});
```

Add a remembered-state test:

```tsx
it("restores remembered project when the URL has none", async () => {
  localStorage.setItem("blackbox.activity.projectKey", "sba-key");
  render(() => <ActivityPage />);
  expect(await screen.findByRole("button", { name: /sba-agentic/ })).toBeInTheDocument();
});
```

- [ ] **Step 2: Run Activity tests and verify failure**

Run:

```bash
cd frontend && npm run test -- src/pages/__tests__/ActivityPage.test.tsx
```

Expected: fails because Activity has no project picker.

- [ ] **Step 3: Wire project context in `ActivityPage`**

Update imports:

```ts
import { createEffect, createMemo, createResource, createSignal, For, Match, Switch } from "solid-js";
import ProjectPicker from "../components/ProjectPicker";
import { getProjects } from "../lib/api";
import { readRememberedProjectKey, rememberProjectKey } from "../lib/projects";
```

Extend params:

```ts
const [params, setParams] = useSearchParams<{ q?: string; session?: string; view?: string; project?: string; event?: string }>();
```

Load projects and derive selection:

```ts
const [projects] = createResource(getProjects, { initialValue: [] });
const selectedProject = createMemo(() => projects().find((project) => project.projectKey === params.project));

createEffect(() => {
  if (params.project !== undefined || projects.loading) return;
  const remembered = readRememberedProjectKey();
  if (remembered && projects().some((project) => project.projectKey === remembered)) {
    setParams({ project: remembered });
  }
});
```

Add selection handler:

```ts
function selectProject(projectKey: string | undefined) {
  rememberProjectKey(projectKey);
  setParams({ project: projectKey, session: undefined, event: undefined });
}
```

Render `ProjectPicker` in `activity-header` after the title block:

```tsx
<ProjectPicker
  projects={projects()}
  selectedProjectKey={params.project}
  loading={projects.loading}
  onSelect={selectProject}
/>
```

Pass `selectedProject()` into child pages:

```tsx
<SessionsPage
  selectedSessionId={params.session}
  targetEventId={params.event}
  project={selectedProject()}
  defaultToFirst
  onSelectSession={selectSession}
/>
<SearchPage
  mode={mode() as SearchMode}
  showModeTabs={false}
  project={selectedProject()}
  onSelectSession={openSearchResult}
/>
<StreamPage project={selectedProject()} />
```

- [ ] **Step 4: Update `openSearchResult` to carry event ids**

Change the handler:

```ts
function openSearchResult(sessionId: string, eventId?: string) {
  setModeSignal("browse");
  setParams({ session: sessionId, event: eventId, q: undefined, view: "browse" });
}
```

Keep `selectSession` clearing event:

```ts
function selectSession(id: string) {
  setParams({ session: id, event: undefined });
}
```

- [ ] **Step 5: Add project props and hidden API query in Stream and Find**

In `StreamPage.tsx`, add prop type:

```ts
type StreamPageProps = {
  project?: ProjectSummary | null;
};
export default function StreamPage(props: StreamPageProps = {}) {
```

Import `ProjectSummary` and compute API query:

```ts
const apiQuery = createMemo(() => (props.project ? setFacet(submitted(), "project", props.project.canonicalKey) : submitted()));
```

Use `apiQuery()` in `getEventFeed` calls while keeping `submitted()` for input/chips:

```ts
getEventFeed({ limit: FEED_LIMIT, q: apiQuery(), meaningful })
```

In `SearchPage.tsx`, add `project?: ProjectSummary | null` to props, import `ProjectSummary`, and use:

```ts
const apiQuery = createMemo(() => (props.project ? setFacet(submitted(), "project", props.project.canonicalKey) : submitted()));

const searchRequest = createMemo(() => ({ submitted: submitted(), api: apiQuery() }));
const [response] = createResource<SearchResponse, { submitted: string; api: string }>(searchRequest, async (request) =>
  request.submitted.trim() ? search(request.api, 120) : { query: "", local: [], elastic: [], elasticHealth: {} },
);
```

Keep `submitted()` for the empty-state decision so an empty user query inside a selected project
does not run an implicit broad Find query.

- [ ] **Step 6: Add Ask unscoped notice**

In `SearchPage` Ask fallback, pass `project={props.project}` into `AskPanel`. Change `AskPanel`:

```tsx
function AskPanel(props: { project?: ProjectSummary | null } = {}) {
```

Render above the textarea:

```tsx
<Show when={props.project}>
  {(project) => (
    <p class="ask-scope-note">
      Project context is not applied to Ask yet. Ask will search across all recorded memory.
    </p>
  )}
</Show>
```

Add CSS:

```css
.ask-scope-note {
  margin: 0;
  padding: 8px 10px;
  color: var(--text-dim);
  background: color-mix(in srgb, var(--yellow) 8%, var(--bg-surface));
  border: 1px solid color-mix(in srgb, var(--yellow) 30%, var(--border));
  border-radius: var(--radius);
  font-size: 12px;
}
```

- [ ] **Step 7: Add project scoping tests for Stream and Search**

In `StreamPage.test.tsx`, render with a project:

```tsx
render(() => (
  <StreamPage
    project={{
      projectKey: "sba-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "~/Developer/proj/sba-agentic",
      sessionCount: 1,
      eventCount: 1,
      savedMeldCount: 0,
    }}
  />
));
await screen.findByRole("link", { name: /Make stream default/ });
expect(getEventFeed).toHaveBeenCalledWith({
  limit: 100,
  q: "project:/Users/nathan/Developer/proj/sba-agentic",
  meaningful: true,
});
```

In `SearchPage.test.tsx`, import the mocked `search` function and assert:

```tsx
it("passes selected project as a hidden search facet", async () => {
  render(() => (
    <SearchPage
      project={{
        projectKey: "sba-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        label: "~/Developer/proj/sba-agentic",
        sessionCount: 1,
        eventCount: 1,
        savedMeldCount: 0,
      }}
    />
  ));
  fireEvent.input(screen.getByLabelText("Search query"), { target: { value: "kind:Decision" } });
  fireEvent.click(screen.getByRole("button", { name: "Search" }));
  await waitFor(() =>
    expect(search).toHaveBeenLastCalledWith("kind:Decision project:/Users/nathan/Developer/proj/sba-agentic", 120),
  );
});
```

Use Vitest `vi.hoisted` for the `search` mock if the current module-level mock needs to expose it.

- [ ] **Step 8: Run Activity, Stream, and Search tests**

Run:

```bash
cd frontend && npm run test -- src/pages/__tests__/ActivityPage.test.tsx src/pages/__tests__/StreamPage.test.tsx src/pages/__tests__/SearchPage.test.tsx
```

Expected: pass.

- [ ] **Step 9: Commit Activity project context plumbing**

```bash
git add frontend/src/pages/ActivityPage.tsx frontend/src/pages/StreamPage.tsx frontend/src/pages/SearchPage.tsx \
  frontend/src/pages/__tests__/ActivityPage.test.tsx frontend/src/pages/__tests__/StreamPage.test.tsx \
  frontend/src/pages/__tests__/SearchPage.test.tsx frontend/src/theme.css
git commit -m "Add Shared Activity Project Context"
```

## Task 5: Flat Browse Rail And Find Target Highlighting

**Files:**
- Modify: `frontend/src/pages/SessionsPage.tsx`
- Modify: `frontend/src/pages/SearchPage.tsx`
- Modify: `frontend/src/pages/ActivityPage.tsx`
- Modify: `frontend/src/pages/__tests__/SessionsPage.test.tsx`
- Modify: `frontend/src/pages/__tests__/ActivityPage.test.tsx`
- Modify: `frontend/src/theme.css`

- [ ] **Step 1: Add failing SessionsPage tests**

In `SessionsPage.test.tsx`, mock `getProjectSessions`:

```ts
getProjectSessions: vi.fn(async () => [sessions[0]]),
```

Add:

```tsx
it("uses a flat session rail scoped to the selected project", async () => {
  render(() => (
    <SessionsPage
      project={{
        projectKey: "sba-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        label: "~/Developer/proj/sba-agentic",
        sessionCount: 1,
        eventCount: 4,
        savedMeldCount: 0,
      }}
      defaultToFirst
    />
  ));

  const rail = document.querySelector(".session-list-pane") as HTMLElement;
  expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
  expect(within(rail).queryByText("Cockpit cleanup")).not.toBeInTheDocument();
  expect(rail.querySelector(".session-group")).not.toBeInTheDocument();
});
```

Add target highlighting test:

```tsx
it("reveals and highlights a target event", async () => {
  render(() => <SessionsPage selectedSessionId="session-1" targetEventId="evt-decision" />);

  const target = await screen.findByText("Use the calmer session layout");
  const row = target.closest(".event-flow-row");
  expect(row).toHaveClass("event-flow-row--target");
});
```

- [ ] **Step 2: Run SessionsPage tests and verify failure**

Run:

```bash
cd frontend && npm run test -- src/pages/__tests__/SessionsPage.test.tsx
```

Expected: fails because SessionsPage still renders project groups and has no target highlighting.

- [ ] **Step 3: Update SessionsPage props and session loading**

Add props:

```ts
import { getProjectSessions, getSessionEvents, type AgentEvent, type AgentSession, type ProjectSummary } from "../lib/api";

type SessionsPageProps = {
  selectedSessionId?: string;
  targetEventId?: string;
  project?: ProjectSummary | null;
  defaultToFirst?: boolean;
  onSelectSession?: (id: string) => void;
  params?: unknown;
  location?: unknown;
  data?: unknown;
  children?: unknown;
};
```

Replace the single sessions resource with all/project resources:

```ts
const [allSessions] = createSessionsResource(2_000);
const [projectSessions] = createResource(
  () => props.project?.projectKey,
  async (projectKey) => (projectKey ? getProjectSessions(projectKey) : []),
  { initialValue: [] as AgentSession[] },
);
const sessions = createMemo(() => (props.project ? projectSessions() : allSessions()));
```

Use `sessions()` everywhere the file currently uses `sessions()`.

- [ ] **Step 4: Render a flat rail**

Remove `collapsedProjectKeys`, `combinedProject`, `projectGroups`, `toggleProject`, `openCombinedLog`,
and `CombinedLogView` rendering from the main return path. Replace the grouped rail body with:

```tsx
<Show when={filteredSessions().length} fallback={<p class="empty-state session-list-empty">No sessions match the active filters.</p>}>
  <div class="session-rows">
    <For each={filteredSessions()}>
      {(session) => (
        <button
          type="button"
          classList={{ "session-row": true, "session-row--active": session.id === selectedId() }}
          onClick={() => selectSession(session.id)}
        >
          <SourceDot source={session.source} />
          <span class="session-row-main">
            <strong>{session.title || session.clientSessionId}</strong>
            <small>
              {session.eventCount.toLocaleString()} · {truncatePath(session.cwd)} · {timeAgo(session.lastSeenAt)}
            </small>
          </span>
        </button>
      )}
    </For>
  </div>
</Show>
```

Keep the existing session detail pane. Remove unused combined-log helper types and functions after
the compiler reports them.

- [ ] **Step 5: Reveal and highlight target events**

Update `visibleEvents` so the target event is visible even if it is a memory/tool event:

```ts
const visibleEvents = createMemo(() =>
  timelineEvents().filter(
    (event) =>
      event.id === props.targetEventId ||
      isPrimaryReaderEvent(event) ||
      (showMemoryEvents() && isMemoryEvent(event)),
  ),
);
```

Add a target highlight signal:

```ts
const [highlightedEventId, setHighlightedEventId] = createSignal<string | null>(null);
```

Add an effect after `events` and `visibleEvents` exist:

```ts
createEffect(() => {
  const targetId = props.targetEventId;
  if (!targetId || events.loading) return;
  const exists = timelineEvents().some((event) => event.id === targetId);
  if (!exists) return;
  queueMicrotask(() => {
    const element = document.getElementById(`event-${targetId}`);
    element?.scrollIntoView({ block: "center" });
    setHighlightedEventId(targetId);
    window.setTimeout(() => setHighlightedEventId((current) => (current === targetId ? null : current)), 2200);
  });
});
```

Change event row class:

```tsx
<div
  id={`event-${event.id}`}
  classList={{
    "event-flow-row": true,
    "event-flow-row--target": highlightedEventId() === event.id || props.targetEventId === event.id,
  }}
>
```

Add CSS:

```css
.event-flow-row--target {
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--yellow) 10%, transparent);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--yellow) 55%, var(--border));
  animation: target-flash 1.2s ease-out 1;
}

@keyframes target-flash {
  0% { background: color-mix(in srgb, var(--yellow) 28%, transparent); }
  100% { background: color-mix(in srgb, var(--yellow) 10%, transparent); }
}
```

- [ ] **Step 6: Pass event ids from Search results**

In `SearchPage`, change prop signature:

```ts
onSelectSession?: (id: string, eventId?: string) => void;
```

In `ResultRow.select`:

```ts
props.onSelectSession(props.event.sessionId, props.event.id);
```

The existing `ActivityPage.openSearchResult` from Task 4 already accepts `eventId`.

- [ ] **Step 7: Update Activity result-click test**

In `ActivityPage.test.tsx`, after clicking the search result, assert:

```ts
expect(params.session).toBe("session-1");
expect(params.event).toBe("event-frontend-build");
```

Update the mocked `getSessionEvents` to include an event with id `event-frontend-build` when the test
expects the target highlight:

```ts
getSessionEvents: vi.fn(async () => [
  {
    id: "event-frontend-build",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "UserPromptSubmit",
    role: "user",
    text: "Open the focused session from search.",
    observedAt: "2026-06-22T20:04:00Z",
  },
]),
```

- [ ] **Step 8: Run browse/orientation tests**

Run:

```bash
cd frontend && npm run test -- src/pages/__tests__/SessionsPage.test.tsx src/pages/__tests__/ActivityPage.test.tsx
```

Expected: pass.

- [ ] **Step 9: Commit Browse and target orientation**

```bash
git add frontend/src/pages/SessionsPage.tsx frontend/src/pages/SearchPage.tsx frontend/src/pages/ActivityPage.tsx \
  frontend/src/pages/__tests__/SessionsPage.test.tsx frontend/src/pages/__tests__/ActivityPage.test.tsx \
  frontend/src/theme.css
git commit -m "Simplify Browse Around Activity Project Context"
```

## Task 6: Integrated Verification And Live Proof

**Files:**
- Modify: `docs/superpowers/plans/2026-07-09-activity-project-context.md` only if execution notes need to be checked off by the implementer.
- No production file changes are expected in this task unless verification exposes a defect.

- [ ] **Step 1: Run backend suite**

Run:

```bash
mvn test
```

Expected: pass.

- [ ] **Step 2: Run frontend unit tests**

Run:

```bash
cd frontend && npm run test
```

Expected: pass.

- [ ] **Step 3: Run frontend build**

Run:

```bash
cd frontend && npm run build
```

Expected: pass.

- [ ] **Step 4: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Verify live local behavior**

Start or reuse the local app. If using the launchd-owned app, deploy first through the repo's local
deploy path so the jar matches the current branch:

```bash
./scripts/deploy-local.sh
curl -fsS http://localhost:8766/api/status | jq
```

Then verify in the browser at `http://localhost:8766/`:

- Activity opens on Stream.
- Project picker can find `sba-agentic` by typing `sba`.
- Selecting `sba-agentic` updates the URL with a value shaped like `project=sba-key`.
- Stream rows narrow to the selected project.
- Browse shows a flat session rail, not collapsible project groups.
- Find with `NOT kind:PostToolUse` excludes `PostToolUse` rows.
- Clicking a Find result opens Browse, selects the owning session, and highlights the matched event.
- Ask mode shows that selected project context is not applied to Ask yet.

- [ ] **Step 6: Capture Black Box handoff**

After verification, capture a compact handoff with:

- branch name;
- final commit SHA;
- files changed;
- tests run;
- live service effects;
- what was not pushed;
- next action.

Create the JSON with environment-backed session identity, then post it:

```bash
FINAL_SHA="$(git rev-parse HEAD)"
jq -n \
  --arg source "codex" \
  --arg clientSessionId "${CODEX_THREAD_ID:-manual-codex-session}" \
  --arg repo "/Users/nathan/Developer/proj/sba-agentic" \
  --arg toAgent "future-codex" \
  --arg contextSummary "Activity project context implementation completed at ${FINAL_SHA}. Verification run: mvn test; cd frontend && npm run test; cd frontend && npm run build; git diff --check; live browser proof against localhost:8766 after deploy-local. Nothing pushed by this handoff step." \
  --arg nextAction "Review the verified implementation in the browser and decide whether to push or open a PR." \
  '{
    source: $source,
    clientSessionId: $clientSessionId,
    repo: $repo,
    toAgent: $toAgent,
    contextSummary: $contextSummary,
    openLoops: [],
    nextAction: $nextAction
  }' > /tmp/activity-project-context-handoff.json

curl -fsS -X POST http://localhost:8766/api/handoffs \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/activity-project-context-handoff.json
```

- [ ] **Step 7: Final status**

Run:

```bash
git status --short
git log -5 --oneline
```

Expected: only intentional uncommitted files remain, or a clean worktree if all implementation commits
were created.
