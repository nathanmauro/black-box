import { test, expect } from "@playwright/test";
import { E2E_PROJECT_CWD } from "../../src/e2e/seedData";

const SHOT_DIR = "test-results/shots";

test("companion discloses mini, projects and items with Black Box links, and receives live handoffs", async ({ page, request }) => {
  await page.goto("/companion");
  await expect(page.locator(".app-utility-bar")).toHaveCount(0);
  await expect(page.locator(".app-shell")).toHaveCSS("display", "block");
  const chip = page.getByRole("button", { name: /Black Box companion/ });
  await expect(chip).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-mini.png` });

  await chip.click();
  const projectRow = page.getByRole("button", { name: /^black-box-e2e:/ });
  await expect(projectRow).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-compact.png` });

  await projectRow.click();
  const decision = page.locator(".companion-item").filter({ hasText: "Use SolidJS + Vite for the UI rewrite" });
  await expect(decision).toBeVisible();
  await expect(decision).toHaveAttribute("href", /\/\?view=browse&session=[^&]+&event=[^&]+/);
  await expect(decision).toHaveAttribute("target", "_blank");
  await page.screenshot({ path: `${SHOT_DIR}/companion-expanded.png` });

  const marker = "COMPANION-HANDOFF-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Handoff",
      role: "assistant",
      text: `Handoff to next-session: ${marker}`,
      cwd: E2E_PROJECT_CWD,
      metadata: { title: marker, kind: "handoff", contextSummary: marker, nextAction: "Verify the companion shows this", openLoops: ["none"] },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("Next: Verify the companion shows this")).toBeVisible();

  await page.getByRole("button", { name: "River" }).click();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: /^black-box-e2e:/ })).toBeVisible();
});

test("embedded companion paints no page background so the shell's clear panel shows through", async ({ page }) => {
  await page.goto("/companion?embedded=1");
  await expect(page.getByRole("button", { name: /Black Box companion/ })).toBeVisible();
  await expect(page.locator("body")).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
  await expect(page.locator(".app-shell")).toHaveCSS("background-image", "none");
  await expect(page.locator(".app-shell")).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
});
