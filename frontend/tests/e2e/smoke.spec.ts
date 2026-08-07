import { test, expect } from "@playwright/test";

const SHOT_DIR = "test-results/shots";

test("activity workspace is stream-first with browse one tab away", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Stream", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Browse", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Projects", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Recall", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Activity" })).toBeVisible();

  const modes = page.getByRole("tablist", { name: "Activity mode" });
  await expect(modes.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");
  await expect(modes.getByRole("tab", { name: "Find" })).toHaveCount(0);
  await expect(modes.getByRole("tab", { name: "Ask" })).toBeVisible();

  await page.goto("/?view=browse");
  await expect(modes.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("Find sessions")).toBeVisible();
  await expect(page.getByText("UI rewrite kickoff")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/activity.png`, fullPage: true });
});

test("Activity project picker scopes the current session rail and reader", async ({ page }) => {
  await page.goto("/?view=browse");

  await page.locator(".project-picker-button").click();
  const projectOption = page.getByRole("listbox", { name: "Project results" })
    .getByRole("option")
    .filter({ hasText: "/tmp/black-box-e2e" });
  await expect(projectOption).toBeVisible();
  await projectOption.click();

  await expect(page).toHaveURL(/project=/);
  await expect(page.getByLabel("Find sessions")).toBeVisible();
  await expect(page.getByRole("button", { name: /UI rewrite kickoff/ })).toBeVisible();
  await page.getByRole("button", { name: /UI rewrite kickoff/ }).click();
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
  await expect(page.getByText("No summary captured yet.")).toBeVisible();
});

test("legacy Search URLs preserve their query in the Stream", async ({ page }) => {
  await page.goto("/search?q=source%3Acodex");
  await expect(page).toHaveURL(/\/?q=source%3Acodex/);
  await expect(page.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toBeVisible();
  await expect(page.getByText("Rewrite the UI to match agent-observatory")).toHaveCount(0);
  await page.screenshot({ path: `${SHOT_DIR}/stream-legacy-search.png`, fullPage: true });
});

test("legacy Activity Find URLs normalize to the Stream", async ({ page }) => {
  await page.goto("/?view=find&q=source%3Acodex");
  await expect(page).toHaveURL(/\/?q=source%3Acodex/);
  await expect(page.getByRole("tab", { name: "Find" })).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toBeVisible();
});

test("stream facet suggestions close after selection and dismissal", async ({ page }) => {
  await page.goto("/");
  const input = page.getByLabel("Stream query");

  await input.fill("kind:Dec");
  await expect(page.getByRole("listbox")).toBeVisible();
  await page.getByRole("listbox").getByRole("button", { name: "Decision" }).click();
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await expect(input).toHaveValue("kind:Decision ");

  await page.getByRole("button", { name: "Filter", exact: true }).click();
  const activeFacet = page.locator(".facet-chip--active");
  await expect(activeFacet).toContainText("Decision");
  await expect(page.getByRole("listbox")).toHaveCount(0);

  await activeFacet.click();
  await expect(page.locator(".facet-chip--active")).toHaveCount(0);

  await input.fill("kind:Han");
  await expect(page.getByRole("listbox")).toBeVisible();
  await input.press("Escape");
  await expect(page.getByRole("listbox")).toHaveCount(0);

  await input.fill("kind:Obs");
  await expect(page.getByRole("listbox")).toBeVisible();
  await page.mouse.click(12, 12);
  await expect(page.getByRole("listbox")).toHaveCount(0);
});

test("session detail renders a structured decision card", async ({ page }) => {
  await page.goto("/?q=source%3Acodex");
  const row = page.locator(".stream-row").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" }).first();
  await row.click();
  await page.getByRole("link", { name: "View session" }).click();
  await expect(page).toHaveURL(/view=browse/);
  await expect(page).toHaveURL(/event=/);
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
  await expect(page.locator(".event-flow-row--target")).toBeVisible();
  await expect(page.getByText("Matches agent-observatory; stays self-contained in the jar at runtime")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/session-detail.png`, fullPage: true });
});

test("command palette jumps to a session", async ({ page }) => {
  await page.goto("/");
  await page.keyboard.press("Meta+k");
  const input = page.getByPlaceholder("Jump to session or filter Stream...");
  await expect(input).toBeVisible();
  await input.fill("Frontend build");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/session=/);
  await expect(page.getByRole("heading", { name: "Frontend build" })).toBeVisible();
});

test("live feed receives a newly ingested event over SSE", async ({ page, request }) => {
  await page.goto("/");
  // Wait for the SSE connection to come up.
  await expect(page.locator(".live-pill--live")).toBeVisible({ timeout: 10_000 });
  const marker = "LIVE-SSE-CHECK-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Observation",
      role: "assistant",
      text: marker,
      cwd: "/tmp/live",
      metadata: { title: marker },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.getByText(marker)).toBeVisible({ timeout: 10_000 });
});

test("graph shows the seeded recall constellation", async ({ page, request }) => {
  const marker = "GRAPH-RECALL-CHECK-" + Date.now();
  const seeded = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: marker,
      eventType: "Decision",
      role: "assistant",
      text: marker,
      cwd: "/tmp/black-box-e2e",
      metadata: {
        title: marker,
        kind: "decision",
        decision: marker,
        rationale: "Keep the graph smoke test isolated from earlier runner events.",
      },
    },
  });
  expect(seeded.ok()).toBeTruthy();
  await page.route("**/api/recall?**", async (route) => {
    const url = new URL(route.request().url());
    url.searchParams.set("scope", marker);
    await route.continue({ url: url.toString() });
  });

  await page.goto("/graph");
  await expect(page.getByRole("heading", { name: "Map recalled intent by project" })).toBeVisible();
  await expect(page.locator(".graph-node--leaf").first()).toBeVisible();
  await expect(page.locator(".graph-label--leaf").filter({ hasText: marker })).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/graph.png`, fullPage: true });
});

test("projects opens the catalog-backed project workspace", async ({ page }) => {
  const browserErrors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") browserErrors.push(message.text());
  });
  page.on("pageerror", (error) => browserErrors.push(error.message));
  await page.goto("/projects");
  await expect(page.getByRole("heading", { name: "Projects", exact: true })).toBeVisible();
  await expect(page.getByText("Project catalog", { exact: true })).toBeVisible();
  const fixtureProject = page.locator(".project-catalog-row").filter({ hasText: "/tmp/black-box-e2e" });
  await expect(fixtureProject).toBeVisible();
  await fixtureProject.click();
  await expect(page.getByRole("heading", { name: "black-box-e2e", exact: true })).toBeVisible();
  const storylineToggle = page.getByRole("group", { name: "Project storyline view" });
  await expect(storylineToggle.getByRole("button", { name: "Trajectory" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator('.project-trajectory [data-node-kind="head"]')).toBeVisible();
  await expect(page.locator([
    '.project-trajectory [data-node-kind="future-next"]',
    '.project-trajectory [data-node-kind="future-loop"]',
    '.project-trajectory [data-node-kind="future-task"]',
  ].join(", ")).first()).toBeVisible();
  await expect(page.locator('.project-trajectory [data-node-kind="future-ghost"]').first()).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/projects.png`, fullPage: true });
  await storylineToggle.getByRole("button", { name: "Timeline" }).click();
  await expect(page.getByText("Hybrid storyline", { exact: true })).toBeVisible();
  await expect(page.getByText("Recent sessions", { exact: true })).toBeVisible();
  await expect(page.getByText("UI rewrite kickoff", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Automatic · nested worktree", { exact: true })).toBeVisible();
  await expect(page.getByText("Release workspace synthesis", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Release worktree handoff", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: "Activity Browse", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /Preview meld/i })).toHaveCount(0);
  await expect(page.getByText("Projects are parked")).toHaveCount(0);
  await expect(page.getByText("Project storylines and melds are disabled")).toHaveCount(0);
  expect(browserErrors).toEqual([]);
});

test("recall query links the owning session and exact event", async ({ page, request }) => {
  await page.goto("/recall");
  await expect(page.getByRole("heading", { name: "Ask what agents already decided" })).toBeVisible();

  await page.getByPlaceholder(/a topic/).fill("UI rewrite");
  await page.getByRole("button", { name: "Run recall" }).click();
  const decisionCard = page.getByRole("article").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" });
  await expect(decisionCard).toBeVisible();
  await expect(decisionCard.getByText("Matches agent-observatory; stays self-contained in the jar at runtime")).toBeVisible();
  await expect(decisionCard.getByText("open loops")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/recall.png`, fullPage: true });

  const recalled = await request.get("/api/recall", {
    params: {
      scope: "UI rewrite",
      withinHours: "168",
      kinds: "decision,handoff",
    },
  });
  expect(recalled.ok()).toBeTruthy();
  const result = await recalled.json() as {
    items: Array<{ eventId: string; sessionId: string; headline?: string | null }>;
  };
  const decision = result.items.find((item) => item.headline === "Use SolidJS + Vite for the UI rewrite");
  expect(decision).toBeDefined();

  await decisionCard
    .getByRole("link", { name: "Open Use SolidJS + Vite for the UI rewrite in Browse" })
    .click();
  await expect(page).toHaveURL((url) => (
    url.searchParams.get("view") === "browse"
    && url.searchParams.get("session") === decision!.sessionId
    && url.searchParams.get("event") === decision!.eventId
  ));
  await expect(page.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
  await expect(page.locator(".event-flow-row--target")).toBeVisible();
});
