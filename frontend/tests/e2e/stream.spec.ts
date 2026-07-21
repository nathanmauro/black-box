import { test, expect } from "@playwright/test";

const SHOT_DIR = "test-results/shots";

test("stream is the default landing view and shows meaningful events newest-first", async ({ page }) => {
  await page.goto("/");

  const modes = page.getByRole("tablist", { name: "Activity mode" });
  await expect(modes.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");

  // Seeded codex decision + observation are meaningful; they render as compact rows.
  const rows = page.locator(".stream-row");
  await expect(rows.first()).toBeVisible();
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite").first()).toBeVisible();
  await expect(page.getByText("Frontend build completed for the self-contained SolidJS jar.").first()).toBeVisible();

  // The seeded user prompt is not meaningful, so the default view hides it.
  await expect(page.getByText("Rewrite the UI to match agent-observatory")).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/stream.png`, fullPage: true });
});

test("meaningful toggle widens the stream and a source facet narrows it", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator(".stream-row").first()).toBeVisible();

  // Full firehose: the claude user prompt appears once meaningful-only is off.
  await page.getByLabel(/meaningful events only/i).uncheck();
  await expect(page.getByText("Rewrite the UI to match agent-observatory").first()).toBeVisible();

  // Elasticsearch-style facet narrows to one agent.
  const query = page.getByLabel("Stream query");
  await query.fill("source:claude");
  await page.getByRole("button", { name: "Filter", exact: true }).click();
  await expect(page).toHaveURL(/q=source%3Aclaude/);
  await expect(page.getByText("Rewrite the UI to match agent-observatory").first()).toBeVisible();
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/stream-filtered.png`, fullPage: true });
});

test("clicking a stream row expands it inline with the full event card", async ({ page }) => {
  await page.goto("/");
  const decisionRow = page.locator(".stream-row").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" }).first();
  await expect(decisionRow).toBeVisible();
  await expect(decisionRow).toHaveAttribute("type", "button");

  await decisionRow.click();
  await expect(decisionRow).toHaveAttribute("aria-expanded", "true");
  const expanded = page.locator(".stream-row-expanded");
  await expect(expanded).toBeVisible();
  await expect(expanded.getByRole("link", { name: "View session" })).toHaveAttribute("href", /view=browse.*session=.*event=/);
  await expect(expanded.getByText("Matches agent-observatory; stays self-contained in the jar at runtime")).toBeVisible();

  await decisionRow.click();
  await expect(page.locator(".stream-row-expanded")).toHaveCount(0);
});

test("the explicit Stream action opens the exact event in Browse", async ({ page }) => {
  await page.goto("/");
  const decisionRow = page.locator(".stream-row").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" }).first();
  await decisionRow.click();
  await page.locator(".stream-row-expanded").getByRole("link", { name: "View session" }).click();

  await expect(page).toHaveURL(/view=browse/);
  await expect(page).toHaveURL(/session=/);
  await expect(page).toHaveURL(/event=/);
  await expect(page.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: "UI rewrite kickoff" })).toBeVisible();
  await expect(page.locator(".event-flow-row--target")).toBeVisible();
});

test("a newly ingested event flows into the stream live", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.locator(".live-pill--live")).toBeVisible({ timeout: 10_000 });

  const marker = "STREAM-LIVE-CHECK-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Observation",
      role: "assistant",
      text: marker,
      cwd: "/tmp/black-box-e2e",
      metadata: { title: marker },
    },
  });
  expect(res.ok()).toBeTruthy();

  // At top of feed the row prepends directly; otherwise the "N new" pill surfaces it.
  const row = page.getByText(marker).first();
  const pill = page.locator(".stream-new-pill");
  await expect(row.or(pill)).toBeVisible({ timeout: 10_000 });
  if (await pill.isVisible().catch(() => false)) {
    await pill.click();
  }
  await expect(page.getByText(marker).first()).toBeVisible();
});

test("browse mode still reaches the session rail and reader", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "Browse" }).click();
  await expect(page).toHaveURL(/view=browse/);
  await expect(page.getByLabel("Find sessions")).toBeVisible();
  await expect(page.getByText("UI rewrite kickoff").first()).toBeVisible();
});
