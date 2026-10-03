import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`projection recall retains possibilities and exact evidence at ${width}px`, async ({
    page,
    request,
    context,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const id = randomUUID();
    const repo = `/tmp/projection-recall-${id}`;
    const headline = `Keep the local default ${id}`;
    const alternative = "Investigate a shared server only after a demonstrated need.";
    const response = await request.post("/api/projections", {
      data: {
        source: "manual",
        clientSessionId: id,
        repo,
        basis: "Possibilities recorded before any selection or outcome.",
        paths: [
          {
            title: headline,
            description: "Continue verifying recovery. ".repeat(50),
            confidence: 0.8,
          },
          { title: "Shared server", description: alternative, confidence: 0.2 },
        ],
      },
    });
    expect(response.ok()).toBeTruthy();
    const captured = (await response.json()) as { eventId: string; sessionId: string };
    await page.goto(`/recall?project=${encodeURIComponent(repo)}`);
    await expect(page.getByRole("checkbox", { name: "Projection", exact: true })).not.toBeChecked();
    await page.getByRole("checkbox", { name: "Decision", exact: true }).uncheck();
    await page.getByRole("checkbox", { name: "Handoff", exact: true }).uncheck();
    await page.getByRole("checkbox", { name: "Projection", exact: true }).check();
    await page.getByRole("button", { name: "Run recall" }).click();
    const card = page.getByRole("article", { name: headline, exact: true });
    await expect(card).toBeVisible();
    await expect(card).toContainText("Recorded possibilities; no selected outcome is implied.");
    await expect(card.getByRole("meter")).toHaveCount(0);
    const toggle = card.getByRole("button", { name: /^(Show full|Collapse) message$/ });
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(card).toContainText(alternative);
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain(alternative);
    expect(copied).toContain("Recorded possibilities; no selected outcome is implied.");
    expect(copied).toContain(`event=${captured.eventId}`);
    expect(copied).toContain(`session=${captured.sessionId}`);
    await page.reload();
    await expect(page.getByRole("checkbox", { name: "Projection", exact: true })).toBeChecked();
    await page.getByRole("button", { name: "Run recall" }).click();
    await expect(card).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({
      path: test.info().outputPath(`projection-recall-${width}.png`),
      fullPage: true,
    });
    await card.getByRole("link", { name: `Open ${headline} in Browse` }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("event") === captured.eventId &&
        url.searchParams.get("session") === captured.sessionId,
    );
    const source = page.locator(".event-flow-row--target");
    await source.getByRole("button", { name: "Show full message" }).click();
    await expect(source).toContainText(alternative);
    await page.goto(`/recall?project=${encodeURIComponent(repo)}&kinds=projection&run=1`);
    await expect(card).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "Projection", exact: true })).toBeChecked();
  });
}
