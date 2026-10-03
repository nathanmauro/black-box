import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`Projection stays in Browse memory layer and exact sources remain reachable at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const repo = `/tmp/projection-browse-${id}`;
    const clientSessionId = `projection-browse-${id}`;
    const prompt = await request.post("/api/events", {
      data: {
        source: "manual",
        clientSessionId,
        cwd: repo,
        eventType: "UserPromptSubmit",
        role: "user",
        text: "Record the possible next directions.",
      },
    });
    expect(prompt.ok()).toBeTruthy();
    const headline = "Continue verifying the local default";
    const alternative = "Evaluate a shared server only after a demonstrated need.";
    const basis = "Recorded possibilities before selecting any outcome.";
    const response = await request.post("/api/projections", {
      data: {
        source: "manual",
        clientSessionId,
        repo,
        basis,
        paths: [
          { title: headline, description: "Verify recovery using fixtures.", confidence: 0.8 },
          { title: "Shared server", description: alternative, confidence: 0.2 },
        ],
      },
    });
    expect(response.ok()).toBeTruthy();
    const captured = (await response.json()) as { eventId: string; sessionId: string };
    expect((await prompt.json()).sessionId).toBe(captured.sessionId);
    const canonicalResponse = await request.get(`/api/events/${captured.eventId}`);
    expect(canonicalResponse.ok()).toBeTruthy();
    const canonical = await canonicalResponse.json();
    expect(canonical.eventType).toBe("Projection");

    await page.goto(`/?view=browse&session=${captured.sessionId}&project=`);
    const toggle = page.getByRole("checkbox", { name: "Show memory events" });
    const projection = page.locator(`#event-${captured.eventId}`);
    await expect(toggle).not.toBeChecked();
    await expect(projection).toHaveCount(0);
    await expect(
      page.getByText("Agent response not captured for this turn.", { exact: true }),
    ).toBeVisible();
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(toggle).toBeChecked();
    await expect(projection.getByText("Projection", { exact: true })).toBeVisible();
    await expect(projection).toContainText(headline);
    await expect(projection).toContainText(alternative);
    await expect(projection).toContainText(basis);
    await expect(projection.getByText("agent response", { exact: true })).toHaveCount(0);
    await expect(
      page.getByText("Agent response not captured for this turn.", { exact: true }),
    ).toBeVisible();
    await projection.scrollIntoViewIfNeeded();
    await expect(projection).toBeInViewport();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({ path: test.info().outputPath(`projection-memory-${width}.png`) });
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(projection).toHaveCount(0);

    await page.goto(`/recall?project=${encodeURIComponent(repo)}&kinds=projection&run=1`);
    const card = page.getByRole("article", { name: headline, exact: true });
    await expect(card).toBeVisible();
    const source = card.getByRole("link", { name: `Open ${headline} in Browse` });
    await source.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("event") === captured.eventId &&
        url.searchParams.get("session") === captured.sessionId,
    );
    await expect(toggle).not.toBeChecked();
    await expect(projection).toHaveClass(/event-flow-row--target/);
    await expect(projection.getByText("Projection", { exact: true })).toBeVisible();
    await expect(projection).toContainText(alternative);
    await expect(projection.getByText("agent response", { exact: true })).toHaveCount(0);
    await projection.scrollIntoViewIfNeeded();
    await expect(projection).toBeInViewport();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({ path: test.info().outputPath(`projection-source-${width}.png`) });
    const after = await request.get(`/api/events/${captured.eventId}`);
    expect(after.ok()).toBeTruthy();
    expect(await after.json()).toEqual(canonical);
  });
}
