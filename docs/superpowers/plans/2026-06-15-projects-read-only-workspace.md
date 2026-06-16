# Projects Read-Only Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Phase 1 of Projects and Durable Melds: a read-only Projects workspace that groups sessions by derived project key and shows a project-level Hybrid Storyline.

**Architecture:** Add a small `project` package that derives project summaries from `agent_sessions.cwd`, encodes project keys with URL-safe Base64, and reads project sessions and timeline blocks from SQLite. Expose the read model through `/api/projects`, `/api/projects/{projectKey}/sessions`, `/api/projects/{projectKey}/timeline`, and `/api/projects/{projectKey}/melds`; wire a static Projects tab to those endpoints.

**Tech Stack:** Spring Boot 3.5, Java 21 records, JdbcTemplate, SQLite, MockMvc tests, static HTML/CSS/JavaScript.

---

## Files

- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectKeyCodec.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectSummary.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectTimelineBlock.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectTimelineResponse.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectRepository.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectService.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/web/AgenticController.java`
- Modify: `src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java`
- Modify: `src/test/java/dev/nathan/sbaagentic/web/StaticUiContractTest.java`
- Modify: `src/main/resources/static/index.html`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/styles.css`

## Task 1: Projects API Read Model

- [ ] **Step 1: Write failing controller tests**

Add tests to `AgenticControllerTest` that seed events with two `cwd` values, then assert:

```java
mockMvc.perform(get("/api/projects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].projectKey").isNotEmpty())
        .andExpect(jsonPath("$[0].sessionCount").value(2))
        .andExpect(jsonPath("$[0].eventCount").value(4))
        .andExpect(jsonPath("$[0].savedMeldCount").value(0));
```

Parse the returned `projectKey` and assert:

```java
mockMvc.perform(get("/api/projects/{projectKey}/sessions", projectKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
```

Assert the timeline returns only storyline-worthy blocks:

```java
mockMvc.perform(get("/api/projects/{projectKey}/timeline", projectKey).param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.blockType == 'decision')].headline").value(hasItem("Use SQLite source of truth")))
        .andExpect(jsonPath("$.items[?(@.blockType == 'assistant')].headline").value(hasItem("Implemented the reader")));
```

- [ ] **Step 2: Run tests to verify failure**

Run: `mvn -Dtest=AgenticControllerTest#projectsReadModelGroupsSessionsAndBuildsTimeline test`

Expected: fail with missing `/api/projects` endpoint.

- [ ] **Step 3: Implement minimal read model**

Add the `project` package records, repository, and service. Keep v1 derived from normalized `cwd`; no alias table and no meld persistence in this task. Return an empty list from `/api/projects/{projectKey}/melds`.

- [ ] **Step 4: Wire controller endpoints**

Inject `ProjectService` into `AgenticController` and expose the four read-only endpoints. Clamp project session and timeline limits to existing safe bounds; clamp offset to zero or greater.

- [ ] **Step 5: Run targeted tests to verify pass**

Run: `mvn -Dtest=AgenticControllerTest#projectsReadModelGroupsSessionsAndBuildsTimeline test`

Expected: pass.

## Task 2: Static Projects Workspace

- [ ] **Step 1: Write failing static UI contract test**

Extend `StaticUiContractTest` to assert:

```java
assertThat(html)
        .contains("data-tab=\"projects\"")
        .contains("id=\"projectList\"")
        .contains("id=\"projectTimeline\"");
assertThat(script)
        .contains("/api/projects")
        .contains("loadProjects")
        .contains("renderProjectTimeline");
assertThat(css)
        .contains(".project-workspace")
        .contains(".project-timeline");
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -Dtest=StaticUiContractTest test`

Expected: fail because the Projects tab does not exist yet.

- [ ] **Step 3: Implement static UI**

Add a Projects tab after Sessions. Add a panel with project list, project session list, and Hybrid Storyline. Fetch project summaries on boot and refresh, select the newest project by default, and render blocks with source, session, observed time, headline, body text, and raw details.

- [ ] **Step 4: Run static contract test to verify pass**

Run: `mvn -Dtest=StaticUiContractTest test`

Expected: pass.

## Task 3: Verification And Docs

- [ ] **Step 1: Run targeted API and static tests**

Run:

```bash
mvn -Dtest=AgenticControllerTest#projectsReadModelGroupsSessionsAndBuildsTimeline,StaticUiContractTest test
```

- [ ] **Step 2: Run full suite**

Run: `mvn test`

- [ ] **Step 3: Run whitespace check**

Run: `git diff --check`

- [ ] **Step 4: Verify live surface if the app can run**

Run the app on an available local port and request:

```bash
curl -fsS 'http://localhost:8766/api/projects' | jq '.[0]'
```

Then open the UI and verify the Projects tab renders with real project rows and timeline blocks.

- [ ] **Step 5: Update closeout state**

If future meld work remains, leave a compact Black Box handoff with the slice completed, verification run, and the next useful action.
