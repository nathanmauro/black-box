import { test, expect } from "@playwright/test";

const SHOT_DIR = "test-results/shots";

test("overview is search-first and shows seeded sessions", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "Overview", exact: true })).toBeVisible();
  // Hero search is the center of gravity.
  await expect(page.getByLabel("Search sessions and events")).toBeVisible();
  await expect(page.getByText(/sessions ·/)).toBeVisible();
  // Recent sessions populated from real API.
  await expect(page.getByText("UI rewrite kickoff")).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/overview.png`, fullPage: true });
});

test("faceted search filters to one source", async ({ page }) => {
  await page.goto("/search?q=source%3Acodex");
  // The codex decision headline shows; the claude-only prompt should not appear here.
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toBeVisible();
  await expect(page.getByText("Decisions & handoffs")).toBeVisible();
  await expect(page.getByText("Rewrite the UI to match agent-observatory")).toHaveCount(0);
  await page.screenshot({ path: `${SHOT_DIR}/search.png`, fullPage: true });
});

test("session detail renders a structured decision card", async ({ page }) => {
  await page.goto("/search?q=source%3Acodex");
  await page.getByText("Use SolidJS + Vite for the UI rewrite").first().click();
  await expect(page).toHaveURL(/\/sessions\//);
  // Decision card surfaces rationale + open loops, not raw JSON.
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
  await expect(page).toHaveURL(/\/(sessions|search)/);
});

test("live feed receives a newly ingested event over SSE", async ({ page, request }) => {
  await page.goto("/");
  // Wait for the SSE connection to come up.
  await expect(page.locator(".live-pill--live")).toBeVisible({ timeout: 10_000 });
  const marker = "LIVE-SSE-CHECK-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: "live-check",
      eventType: "Observation",
      text: marker,
      cwd: "/tmp/live",
      metadata: { title: marker },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.getByText(marker)).toBeVisible({ timeout: 10_000 });
});
