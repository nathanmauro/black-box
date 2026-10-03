import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const [width, height] of [
  [1440, 900],
  [390, 900],
  [390, 700],
]) {
  test(`Browse reading and disclosures at ${width}x${height}`, async ({ page, request }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height });
    const id = randomUUID();
    const repo = `/tmp/mobile-reader-${id}`;
    const capture = async (
      client: string,
      text: string,
      role: "user" | "assistant",
      observedAt: string,
    ) => {
      const response = await request.post("/api/events", {
        data: {
          source: "manual",
          clientSessionId: `${id}-${client}`,
          cwd: repo,
          eventType: role === "user" ? "UserPromptSubmit" : "AssistantMessage",
          role,
          text,
          observedAt,
        },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { eventId: string; sessionId: string };
    };
    const now = Date.now();
    const primaryTitle = "Primary reading session. Review the evidence before continuing.";
    const prompt = await capture(
      "primary",
      primaryTitle,
      "user",
      new Date(now - 120_000).toISOString(),
    );
    const replies = [];
    for (let i = 0; i < 52; i++)
      replies.push(
        await capture(
          "primary",
          i === 0
            ? "Exact source answer before the first page."
            : i === 40
              ? "Unique transcript needle for search."
              : `Recorded response ${i}.`,
          "assistant",
          new Date(now - 60_000 + i * 1000).toISOString(),
        ),
      );
    const projectionResponse = await request.post("/api/projections", {
      data: {
        source: "manual",
        clientSessionId: `${id}-primary`,
        repo,
        basis: "A recorded possibility, not a selected outcome.",
        paths: [
          {
            title: "Continue locally",
            description: "Verify the evidence before choosing a direction.",
            confidence: 0.8,
          },
        ],
      },
    });
    expect(projectionResponse.ok()).toBeTruthy();
    const projection = (await projectionResponse.json()) as { eventId: string };
    const secondary = await capture(
      "secondary",
      "Secondary reading session",
      "user",
      new Date(now + 1000).toISOString(),
    );
    await page.goto("/?view=browse&project=");
    await expect(page.getByRole("heading", { name: "Secondary reading session" })).toBeVisible();
    const chooser = page.getByRole("button", { name: /^Sessions / });
    const details = page.getByRole("button", { name: "Session details" });
    const mobile = width < 880;
    if (mobile) {
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
      await expect(page.getByLabel("Find sessions", { exact: true })).toBeHidden();
      await chooser.focus();
      await page.keyboard.press("Enter");
      await expect(page.getByLabel("Find sessions", { exact: true })).toBeFocused();
      await page.getByLabel("Find sessions", { exact: true }).fill("No matching fixture session");
      await expect(page.getByText("No sessions match the active filters.")).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(chooser).toBeFocused();
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
      await page.keyboard.press("Space");
    }
    await page.getByLabel("Find sessions", { exact: true }).fill("Primary reading session");
    await page
      .locator(".session-row")
      .filter({ hasText: "Primary reading session" })
      .filter({ hasText: repo })
      .focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL((url) => url.searchParams.get("session") === prompt.sessionId);
    const heading = page.getByRole("heading", { name: primaryTitle });
    await expect(heading).toBeVisible();
    if (mobile) {
      await expect(heading).toBeFocused();
      await expect(page.getByRole("complementary", { name: "Session chooser" })).toBeHidden();
      await expect(page.locator(".session-title-extra")).toBeHidden();
      await details.focus();
      await page.keyboard.press("Enter");
      await expect(details).toHaveAttribute("aria-expanded", "true");
      await expect(page.locator(".session-title-extra")).toBeVisible();
      await expect(page.locator(".session-summary-content")).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(details).toBeFocused();
      await expect(details).toHaveAttribute("aria-expanded", "false");
    } else {
      await expect(chooser).toBeHidden();
      await expect(page.getByRole("complementary", { name: "Session chooser" })).toBeVisible();
      await expect(page.locator(".session-title-extra")).toBeVisible();
    }
    const memory = page.getByRole("checkbox", { name: "Show memory events" });
    await memory.focus();
    await page.keyboard.press("Space");
    await expect(page.locator(`#event-${projection.eventId}`)).toHaveCount(1);
    await page.keyboard.press("Space");
    await expect(page.locator(`#event-${projection.eventId}`)).toHaveCount(0);
    const search = page.getByLabel("Find in session", { exact: true });
    await search.fill("Unique transcript needle");
    await expect(page.locator(".timeline-pane")).toContainText(
      "Unique transcript needle for search.",
    );
    await expect(page.getByRole("button", { name: "Next transcript match" })).toBeEnabled();
    await page.keyboard.press("Enter");
    await expect(page.locator(".prompt-turn--search-active")).toContainText(
      "Unique transcript needle for search.",
    );
    await page.keyboard.press("Escape");
    await expect(search).toHaveValue("");
    const loadOlder = page.getByRole("button", { name: "Load older events" });
    await loadOlder.focus();
    await page.keyboard.press("Space");
    await expect(page.locator(`#event-${prompt.eventId}`)).toHaveCount(1);
    await expect(loadOlder).toHaveCount(0);

    // A fresh exact-source link must reveal the answer without a test-side scrolling workaround.
    await page.goto(
      `/?view=browse&session=${prompt.sessionId}&event=${replies[0].eventId}&project=`,
    );
    const target = page.locator(`#event-${replies[0].eventId}`);
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(target).toBeInViewport({ ratio: 0.95 });
    if (mobile) await expect(target).toBeFocused();
    const metrics = await page.locator(".timeline-pane").evaluate((el) => {
      const box = el.getBoundingClientRect();
      const target = el.querySelector(".event-flow-row--target")!.getBoundingClientRect();
      return {
        height: box.height,
        visibleHeight: Math.max(0, Math.min(innerHeight, box.bottom) - Math.max(0, box.top)),
        targetInside: target.top >= box.top && target.bottom <= box.bottom,
        scrollY,
      };
    });
    expect(metrics.targetInside).toBe(true);
    expect(metrics.visibleHeight).toBeGreaterThan(mobile ? (height === 700 ? 150 : 350) : 400);
    if (mobile) expect(metrics.scrollY).toBe(0);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    console.log(`READER ${width}x${height}`, JSON.stringify(metrics));
    await page.screenshot({ path: test.info().outputPath(`reader-${width}x${height}.png`) });

    if (mobile) {
      await details.click();
      await page.keyboard.press("Escape");
      await expect(details).toBeFocused();
      await chooser.click();
      await page.getByLabel("Find sessions", { exact: true }).fill("No matching fixture session");
      await page.keyboard.press("Escape");
      await expect(chooser).toBeFocused();
      await page.setViewportSize({ width: 1440, height: 900 });
      await expect(chooser).toBeHidden();
      await expect(page.getByRole("complementary", { name: "Session chooser" })).toBeVisible();
      await expect(page.locator(".session-summary-content")).toBeVisible();
      await expect(page.getByLabel("Find sessions", { exact: true })).toHaveValue("");
      await expect(heading).toBeVisible();
      await page.setViewportSize({ width, height });
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
      await expect(page.getByRole("complementary", { name: "Session chooser" })).toBeHidden();
      await expect(details).toHaveAttribute("aria-expanded", "false");
      await expect(target).toBeInViewport({ ratio: 0.95 });
      await chooser.click();
      await page.getByLabel("Find sessions", { exact: true }).fill("Secondary reading session");
      await page
        .locator(".session-row")
        .filter({ hasText: "Secondary reading session" })
        .filter({ hasText: repo })
        .click();
      await expect(page).toHaveURL(
        (url) =>
          url.searchParams.get("session") === secondary.sessionId && !url.searchParams.has("event"),
      );
      await expect(page.getByRole("heading", { name: "Secondary reading session" })).toBeFocused();
      await page.goto(`/sessions/${prompt.sessionId}`);
      await expect(page.getByRole("heading", { name: primaryTitle })).toBeVisible();
      await expect
        .poll(() =>
          page.locator(".timeline-pane").evaluate((el) => el.getBoundingClientRect().height),
        )
        .toBeGreaterThan(height - 420);
      expect(
        await page
          .locator(".detail-header")
          .evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(" ").length),
      ).toBe(1);
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        .toBe(true);
      const directMetrics = await page.locator(".timeline-pane").evaluate((el) => ({
        viewportHeight: innerHeight,
        documentHeight: document.documentElement.scrollHeight,
        paneBottom: el.getBoundingClientRect().bottom,
        scrollY,
      }));
      console.log(`DIRECT READER ${width}x${height}`, JSON.stringify(directMetrics));
      expect(directMetrics.documentHeight).toBeLessThanOrEqual(height + 1);
      expect(directMetrics.paneBottom).toBeLessThanOrEqual(height);
      expect(directMetrics.scrollY).toBe(0);
      await page.screenshot({
        path: test.info().outputPath(`direct-reader-${width}x${height}.png`),
      });
    }
  });
}
