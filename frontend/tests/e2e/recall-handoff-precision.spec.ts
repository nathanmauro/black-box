import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { RecallResult } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`latest retrieved handoff retains nanosecond precision at ${width}px`, async ({
    page,
    request,
    context,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const id = randomUUID();
    const repo = `/tmp/handoff-precision-${id}`;
    const second = new Date(Date.now() - 60_000).toISOString().split(".")[0];
    const olderTime = `${second}.123456788Z`;
    const newerTime = `${second}.123456789Z`;
    const capture = async (headline: string, observedAt: string) => {
      const response = await request.post("/api/events", {
        data: {
          source: "manual",
          clientSessionId: `handoff-${id}-${headline}`,
          cwd: repo,
          eventType: "Handoff",
          role: "assistant",
          text: headline,
          observedAt,
          metadata: {
            kind: "handoff",
            repo,
            contextSummary: headline,
            nextAction: "Inspect the captured evidence",
          },
        },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { eventId: string; sessionId: string };
    };
    const older = await capture("Older handoff checkpoint", olderTime);
    const newer = await capture("Newer handoff checkpoint", newerTime);
    let reorderedResponses = 0;
    await page.route("**/api/recall?**", async (route) => {
      // Keep real captured evidence and only model legitimate relevance ordering; no provider call.
      const response = await route.fetch();
      expect(response.ok()).toBeTruthy();
      const body = (await response.json()) as RecallResult;
      const first = body.items.find((item) => item.eventId === older.eventId);
      const second = body.items.find((item) => item.eventId === newer.eventId);
      expect(first).toBeDefined();
      expect(second).toBeDefined();
      reorderedResponses += 1;
      await route.fulfill({ response, json: { ...body, items: [first, second] } });
    });
    await page.goto(
      `/recall?project=${encodeURIComponent(repo)}&kinds=handoff&query=checkpoint&run=1`,
    );
    const briefing = page.getByRole("region", { name: "Latest recorded context" });
    const latest = briefing.getByRole("link", { name: "Newer handoff checkpoint", exact: true });
    await expect(latest).toBeVisible();
    await expect(briefing).toContainText(newerTime);
    await expect(
      briefing.getByRole("link", { name: "Older handoff checkpoint", exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole("article", { name: "Older handoff checkpoint" })).toBeVisible();
    await expect(page.getByRole("article", { name: "Newer handoff checkpoint" })).toBeVisible();
    expect(reorderedResponses).toBeGreaterThan(0);
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain(olderTime);
    expect(copied).toContain(newerTime);
    expect(copied).toContain(`event=${newer.eventId}`);
    expect(copied).toContain(`event=${older.eventId}`);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: test.info().outputPath(`handoff-precision-${width}.png`),
      fullPage: true,
    });
    await latest.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("event") === newer.eventId &&
        url.searchParams.get("session") === newer.sessionId,
    );
    await expect(page.locator(".event-flow-row--target")).toContainText("Newer handoff checkpoint");
    for (const [capture, at] of [
      [older, olderTime],
      [newer, newerTime],
    ] as const) {
      const saved = await request.get(`/api/events/${capture.eventId}`);
      expect(saved.ok()).toBeTruthy();
      expect((await saved.json()).observedAt).toBe(at);
    }
  });
}
