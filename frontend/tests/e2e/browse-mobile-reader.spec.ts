import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const [width, height] of [
  [1440, 900],
  [390, 900],
  [390, 700],
  [320, 700],
  [844, 390],
  [667, 375],
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
          height < 500 && i > 0 && i % 4 === 0 && i !== 40 ? "user" : "assistant",
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
    // Projection is timestamped by the server after the capture loop. Derive the next session
    // from that persisted endpoint, not the clock sampled before dozens of HTTP requests.
    const primaryResponse = await request.get(`/api/sessions/${prompt.sessionId}`);
    expect(primaryResponse.ok()).toBeTruthy();
    const primarySession = (await primaryResponse.json()) as { lastSeenAt: string };
    const primaryLastSeen = Date.parse(primarySession.lastSeenAt);
    expect(Number.isFinite(primaryLastSeen)).toBe(true);
    const secondary = await capture(
      "secondary",
      "Secondary reading session",
      "user",
      new Date(primaryLastSeen + 1000).toISOString(),
    );
    const newestResponse = await request.get("/api/sessions?limit=1");
    expect(newestResponse.ok()).toBeTruthy();
    const newestSessions = (await newestResponse.json()) as { id: string }[];
    expect(newestSessions.map((session) => session.id)).toEqual([secondary.sessionId]);
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
    if (height < 500) {
      const initialMetrics = await page.locator(".timeline-pane").evaluate((el) => {
        const box = el.getBoundingClientRect();
        return {
          height: box.height,
          visibleHeight: Math.max(0, Math.min(innerHeight, box.bottom) - Math.max(0, box.top)),
          documentHeight: document.documentElement.scrollHeight,
        };
      });
      console.log(`COMPACT READER ${width}x${height}`, JSON.stringify(initialMetrics));
      await page.screenshot({
        path: test.info().outputPath(`compact-reader-${width}x${height}.png`),
      });
      expect(initialMetrics.visibleHeight).toBeGreaterThan(140);
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
    if (height < 500) {
      const rail = page.locator(".conversation-navigator-list");
      expect(await rail.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true);
      const lastTurn = page.locator(".conversation-navigator-link").last();
      await lastTurn.focus();
      await expect(lastTurn).toBeInViewport({ ratio: 0.95 });
      expect(await rail.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
      await page.keyboard.press("Enter");
      await expect(page.locator(`#event-${replies[48].eventId}`)).toBeInViewport({ ratio: 0.95 });
    }
    const firstTurn = page.getByRole("link", { name: /^Turn 1: Primary reading session/ });
    await firstTurn.focus();
    await page.keyboard.press("Enter");
    await expect(page.locator(`#event-${prompt.eventId}`)).toBeInViewport({ ratio: 0.95 });

    // A fresh exact-source link must reveal the answer without a test-side scrolling workaround.
    await page.goto(
      `/?view=browse&session=${prompt.sessionId}&event=${replies[0].eventId}&project=`,
    );
    const target = page.locator(`#event-${replies[0].eventId}`);
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(target).toBeInViewport({ ratio: 0.95 });
    if (mobile) await expect(target).toBeFocused();
    // The reader scrolls smoothly; wait for its own reveal to settle before measuring full bounds.
    await expect
      .poll(() =>
        page.locator(".timeline-pane").evaluate((el) => {
          const box = el.getBoundingClientRect();
          const target = el.querySelector(".event-flow-row--target")!.getBoundingClientRect();
          return target.top >= box.top && target.bottom <= box.bottom;
        }),
      )
      .toBe(true);
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
    expect(metrics.visibleHeight).toBeGreaterThan(
      mobile ? (height < 500 ? 140 : height === 700 ? 150 : 350) : 400,
    );
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
      if (height < 500) {
        await page.setViewportSize({ width: 390, height: 700 });
        await expect(target).toBeInViewport({ ratio: 0.95 });
        await page.setViewportSize({ width, height });
        await expect(target).toBeInViewport({ ratio: 0.95 });
      }
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
        .toBeGreaterThan(Math.max(140, height - 420));
      expect(
        await page
          .locator(".detail-header")
          .evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(" ").length),
      ).toBe(height < 500 ? 2 : 1);
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
      if (height < 500) {
        // A long scoped project label must not take back the recovered reading space.
        await page.goto(
          `/?view=browse&session=${prompt.sessionId}&event=${replies[0].eventId}&project=${encodeURIComponent(repo)}`,
        );
        await expect(page.locator(".project-picker-button")).toContainText(repo);
        await expect(target).toBeInViewport({ ratio: 0.95 });
        await expect(target).toBeFocused();
        const scopedMetrics = await page.locator(".timeline-pane").evaluate((el) => {
          const box = el.getBoundingClientRect();
          return { height: box.height, width: box.width, bottom: box.bottom };
        });
        expect(scopedMetrics.height).toBeGreaterThan(140);
        expect(scopedMetrics.width).toBeGreaterThan(width - 200);
        expect(scopedMetrics.bottom).toBeLessThanOrEqual(height);
        await page.screenshot({
          path: test.info().outputPath(`scoped-reader-${width}x${height}.png`),
        });
        await page.locator(".project-picker-button").focus();
        await page.keyboard.press("Enter");
        await expect(page.getByRole("combobox", { name: "Search projects" })).toBeFocused();
        await page.getByRole("combobox", { name: "Search projects" }).fill(repo);
        await page.keyboard.press("Enter");
        await expect(
          page.getByRole("heading", { name: "Secondary reading session" }),
        ).toBeVisible();
        await expect(page.locator(".project-picker-button")).toBeFocused();
        const header = page.getByRole("banner", { name: "Black Box utility bar" });
        const controls = await header.locator("a, button").evaluateAll((nodes) =>
          nodes
            .map((node) => {
              const box = node.getBoundingClientRect();
              return { x: box.x, y: box.y, right: box.right, bottom: box.bottom };
            })
            .filter((box) => box.right > box.x && box.bottom > box.y),
        );
        for (const [index, box] of controls.entries()) {
          expect(box.x).toBeGreaterThanOrEqual(0);
          expect(box.right).toBeLessThanOrEqual(width);
          expect(box.y).toBeGreaterThanOrEqual(0);
          expect(box.bottom).toBeLessThanOrEqual(44);
          for (const other of controls.slice(index + 1)) {
            expect(
              Math.min(box.right, other.right) <= Math.max(box.x, other.x) ||
                Math.min(box.bottom, other.bottom) <= Math.max(box.y, other.y),
            ).toBe(true);
          }
        }
        const sources = header.getByRole("button", { name: "Filter sources" });
        await sources.focus();
        await page.keyboard.press("Enter");
        await expect(header.getByRole("group", { name: "Filter by source" })).toBeInViewport({
          ratio: 1,
        });
        await page.keyboard.press("Enter");
        await page.keyboard.press("Tab");
        await expect(header.getByRole("button", { name: "My turns", exact: true })).toBeFocused();
        await page.keyboard.press("Tab");
        await expect(header.getByRole("button", { name: "Open command palette" })).toBeFocused();
        await page.keyboard.press("Enter");
        await expect(page.getByRole("dialog", { name: "Command palette" })).toBeVisible();
        await page.keyboard.press("Escape");
      }
    }
  });
}
