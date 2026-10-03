import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`observation recall preserves body, bounded copy and exact evidence at ${width}px`, async ({
    page,
    request,
    context,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const id = randomUUID();
    const repo = `/tmp/observation-recall-${id}`;
    const headline = `Observation checkpoint ${id}`;
    const body = `${headline}\n${"Supporting evidence. ".repeat(80)}\nVerification failed: preserve the old database.`;
    const capture = async (text: string) => {
      const response = await request.post("/api/events", {
        data: {
          source: "manual",
          clientSessionId: id,
          cwd: repo,
          eventType: "Observation",
          role: "assistant",
          text,
          metadata: { kind: "observation", repo },
        },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { eventId: string; sessionId: string };
    };
    const captured = await capture(body);
    const recallUrl = `/recall?project=${encodeURIComponent(repo)}&kinds=observation&run=1`;
    await page.goto(recallUrl);
    const card = page.getByRole("article", { name: headline, exact: true });
    await expect(card).toBeVisible();
    await expect(card).not.toContainText("Verification failed");
    await expect(card.locator(".reader-text")).toHaveCSS("white-space", "pre-wrap");
    const toggle = card.getByRole("button", { name: /^(Show full|Collapse) message$/ });
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(card).toContainText("Verification failed: preserve the old database.");
    await page.keyboard.press("Space");
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain(body);
    expect(copied).toContain(`event=${captured.eventId}`);
    expect(copied).toContain(`session=${captured.sessionId}`);
    expect(copied).toContain("Export limits: 0 captures truncated; 0 captures omitted.");
    await card.getByRole("link", { name: `Open ${headline} in Browse` }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("event") === captured.eventId &&
        url.searchParams.get("session") === captured.sessionId,
    );
    await expect(page.locator(".event-flow-row--target")).toContainText(
      "Verification failed: preserve the old database.",
    );
    const oversized = await capture(`Large observation ${id}\n${"Evidence detail. ".repeat(800)}`);
    await page.goto(recallUrl);
    await expect(
      page.getByRole("article", { name: `Large observation ${id}`, exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const bounded = await page.evaluate(() => navigator.clipboard.readText());
    expect(bounded.length).toBeLessThanOrEqual(24_000);
    expect(bounded).toContain("Export limits: 1 captures truncated; 0 captures omitted.");
    expect(bounded).toContain("[Capture truncated; open the evidence link for full text.]");
    expect(bounded).toContain(`event=${oversized.eventId}`);
    expect(bounded).toContain(body);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: test.info().outputPath(`observation-recall-${width}.png`),
      fullPage: true,
    });
  });
}
