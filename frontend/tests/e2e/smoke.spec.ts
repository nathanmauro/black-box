import { test, expect } from "@playwright/test";

const SHOT_DIR = "test-results/shots";

test("activity workspace is stream-first with browse one tab away", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Stream", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Browse", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Recall", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Activity" })).toBeVisible();

  const modes = page.getByRole("tablist", { name: "Activity mode" });
  await expect(modes.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");
  await expect(modes.getByRole("tab", { name: "Find" })).toBeVisible();
  await expect(modes.getByRole("tab", { name: "Ask" })).toBeVisible();

  await page.goto("/?view=browse");
  await expect(modes.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByLabel("Find sessions")).toBeVisible();
  await expect(page.getByText("UI rewrite kickoff")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/activity.png`, fullPage: true });
});

test("project combined log opens from the session group header", async ({ page }) => {
  await page.goto("/?view=browse");

  const openCombinedLog = page.getByRole("button", { name: /Open combined log/i }).first();
  await expect(openCombinedLog).toBeVisible();
  await openCombinedLog.click();

  const combinedLog = page.getByRole("region", { name: /Project combined log/i });
  await expect(combinedLog).toBeVisible();
  await expect(combinedLog.getByRole("heading", { name: /Combined log/i })).toBeVisible();
  await expect(combinedLog.locator(".combined-log-entry, .combined-log-empty").first()).toBeVisible();

  const visibleTags = await combinedLog.locator(".combined-log-tag").allTextContents();
  if (visibleTags.length === 0) {
    await expect(combinedLog.locator(".combined-log-empty")).toBeVisible();
  } else {
    for (const tag of visibleTags) {
      expect(["Decision", "Handoff", "Observation"]).toContain(tag.trim());
    }
  }
});

test("faceted search filters to one source", async ({ page }) => {
  await page.goto("/search?q=source%3Acodex");
  // The codex decision headline shows; the claude-only prompt should not appear here.
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toBeVisible();
  await expect(page.getByText("Decisions & handoffs")).toBeVisible();
  await expect(page.getByText("Rewrite the UI to match agent-observatory")).toHaveCount(0);
  await page.screenshot({ path: `${SHOT_DIR}/search.png`, fullPage: true });
});

test("Activity Find result opens the session reader in place", async ({ page }) => {
  await page.goto("/?view=find&q=source%3Acodex");
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toBeVisible();
  await page.getByText("Use SolidJS + Vite for the UI rewrite").first().click();

  await expect(page).toHaveURL(/session=/);
  await expect(page.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
});

test("search facet suggestions close after selection and dismissal", async ({ page }) => {
  await page.goto("/search");
  const input = page.getByLabel("Search query");

  await input.fill("kind:Dec");
  await expect(page.getByRole("listbox")).toBeVisible();
  await page.getByRole("listbox").getByRole("button", { name: "Decision" }).click();
  await expect(page.getByRole("listbox")).toHaveCount(0);
  await expect(input).toHaveValue("kind:Decision ");

  await page.getByRole("button", { name: "Search" }).click();
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
  await page.goto("/search?q=source%3Acodex");
  await page.getByText("Use SolidJS + Vite for the UI rewrite").first().click();
  await expect(page).toHaveURL(/\/sessions\//);
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
  await page.getByLabel("Show memory events").check();
  // Decision card surfaces rationale + open loops when memory events are explicitly enabled.
  await expect(page.getByText("Matches agent-observatory; stays self-contained in the jar at runtime")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/session-detail.png`, fullPage: true });
});

test("command palette jumps to a session", async ({ page }) => {
  await page.goto("/");
  await page.keyboard.press("Meta+k");
  const input = page.getByPlaceholder("Jump to session or search...");
  await expect(input).toBeVisible();
  await input.fill("Frontend build");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/session=/);
  await expect(page.getByRole("heading", { name: "Frontend build" })).toBeVisible();
});

test("live feed receives a newly ingested event over SSE", async ({ page, request }) => {
  await page.goto("/overview");
  // Wait for the SSE connection to come up.
  await expect(page.locator(".live-inline--live")).toBeVisible({ timeout: 10_000 });
  const marker = "LIVE-SSE-CHECK-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Observation",
      text: marker,
      cwd: "/tmp/live",
      metadata: { title: marker },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.getByText(marker)).toBeVisible({ timeout: 10_000 });
});

test("stats shows headline totals and activity breakdowns", async ({ page }) => {
  await page.goto("/stats");
  await expect(page.getByRole("heading", { name: "Activity shape across Black Box" })).toBeVisible();

  const totals = page.getByLabel("Headline totals");
  await expect(totals).toBeVisible();
  await expect(totals.getByText("Total sessions")).toBeVisible();
  await expect(totals.getByText("Total events")).toBeVisible();
  await expect(page.getByText("events by source")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/stats.png`, fullPage: true });
});

test("graph shows the seeded recall constellation", async ({ page }) => {
  await page.goto("/graph");
  await expect(page.getByRole("heading", { name: "Map recalled intent by project" })).toBeVisible();
  await expect(page.locator(".graph-node--leaf").first()).toBeVisible();
  await expect(page.locator(".graph-label--leaf").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" })).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/graph.png`, fullPage: true });
});

test("projects shows a project row with a storyline timeline", async ({ page }) => {
  await page.goto("/projects");
  await expect(page.getByRole("heading", { name: "Projects are parked" })).toBeVisible();
  await expect(page.getByText("Project storylines and melds are disabled")).toBeVisible();
  await expect(page.getByRole("link", { name: "Open Sessions" })).toHaveAttribute("href", "/sessions");
  await expect(page.getByRole("link", { name: "Open Search" })).toHaveAttribute("href", "/search");
  await page.screenshot({ path: `${SHOT_DIR}/projects.png`, fullPage: true });
});

test("recall query returns grouped structured results", async ({ page }) => {
  await page.goto("/recall");
  await expect(page.getByRole("heading", { name: "Ask what agents already decided" })).toBeVisible();

  await page.getByPlaceholder(/a topic/).fill("UI rewrite");
  await page.getByRole("button", { name: "Run recall" }).click();
  const decisionCard = page.getByRole("article").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" });
  await expect(decisionCard).toBeVisible();
  await expect(decisionCard.getByText("Matches agent-observatory; stays self-contained in the jar at runtime")).toBeVisible();
  await expect(decisionCard.getByText("open loops")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/recall.png`, fullPage: true });
});
