import { expect, test, type Locator } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

async function contained(locator: Locator, width: number) {
  const boxes = await locator.evaluateAll((elements) =>
    elements.map((element) => {
      const r = element.getBoundingClientRect();
      return { left: r.left, right: r.right, width: r.width };
    }),
  );
  for (const box of boxes) {
    expect.soft(box.left).toBeGreaterThanOrEqual(0);
    expect.soft(box.right).toBeLessThanOrEqual(width + 1);
    expect.soft(box.width).toBeGreaterThan(0);
  }
}

for (const [width, height] of [
  [1440, 900],
  [390, 844],
  [320, 700],
  [844, 390],
  [667, 375],
]) {
  test(`Stream controls remain usable at ${width}x${height}`, async ({ page, request }, info) => {
    assertSafeSeedBaseUrl(info.project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height });
    const marker = `polish${randomUUID().replaceAll("-", "")}`;
    const repo = `/tmp/stream-control-${marker}`;
    const text = `${marker} Keep local evidence readable.\n\n${"A longer captured rationale with readable details. ".repeat(7)}`;
    const result = await request.post("/api/events", {
      data: {
        source: "codex",
        clientSessionId: marker,
        cwd: repo,
        eventType: "Observation",
        role: "assistant",
        text,
        metadata: { title: "Stream controls fixture" },
      },
    });
    expect(result.ok()).toBeTruthy();
    const capture = await result.json();
    await page.goto(`/?q=${encodeURIComponent(`project_exact:${repo}`)}`);
    await expect(page.locator(".stream-row")).toHaveCount(1);
    await page.screenshot({ path: info.outputPath(`stream-${width}.png`), fullPage: true });
    await contained(
      page.locator(".stream-input-line button, .stream-run-head button, .stream-run-head a"),
      width,
    );
    const filter = page.getByRole("button", { name: "Filter", exact: true });
    const styling = await filter.evaluate((button) => {
      const sample = document.createElement("span");
      sample.style.backgroundColor = "var(--accent)";
      document.body.append(sample);
      const expected = getComputedStyle(sample).backgroundColor;
      sample.remove();
      return {
        background: getComputedStyle(button).backgroundColor,
        expected,
        height: button.getBoundingClientRect().height,
      };
    });
    expect.soft(styling.background).toBe(styling.expected);
    expect.soft(styling.height).toBe(46);

    const views = page.getByRole("button", { name: "Views", exact: true });
    const options = page.getByRole("button", { name: "Options", exact: true });
    await views.focus();
    await page.keyboard.press("Enter");
    await expect(views).toHaveAttribute("aria-expanded", "true");
    await contained(
      page.locator("#stream-views-panel, #stream-views-panel button, #stream-views-panel input"),
      width,
    );
    await page.screenshot({ path: info.outputPath(`views-${width}.png`), fullPage: true });
    await options.click();
    await contained(page.locator("#stream-options-panel"), width);
    await expect(views).toHaveAttribute("aria-expanded", "false");
    await page.getByRole("button", { name: "Expanded", exact: true }).focus();
    await page.keyboard.press("Escape");
    await expect(options).toHaveAttribute("aria-expanded", "false");
    await expect(options).toBeFocused();
    await views.click();
    await page.getByLabel("Saved view name").fill("Fixture view");
    await expect(views).toHaveAttribute("aria-expanded", "true");
    await page.getByLabel("Stream query").click();
    await expect(views).toHaveAttribute("aria-expanded", "false");

    const sources = page.getByRole("button", { name: "Filter sources" });
    await sources.click();
    const codexSource = page.locator("#source-filter-panel").getByRole("button", { name: /Codex/ });
    await codexSource.click();
    await expect(sources).toHaveAttribute("aria-expanded", "true");
    await codexSource.click();
    await codexSource.focus();
    await page.keyboard.press("Escape");
    await expect(sources).toHaveAttribute("aria-expanded", "false");
    await expect(sources).toBeFocused();
    await sources.click();
    await page.locator(".stream-row").click();
    await expect(sources).toHaveAttribute("aria-expanded", "false");

    // Submit the retained scope by keyboard and confirm real filtering still works.
    const query = page.getByLabel("Stream query");
    await query.fill(`project_exact:${repo} kind:Observation`);
    await query.press("Enter");
    await expect(page.locator(".stream-row")).toHaveCount(1);
    await expect(page).toHaveURL(
      (url) => url.searchParams.get("q") === `project_exact:${repo} kind:Observation`,
    );
    await page.getByRole("button", { name: "Open command palette" }).click();
    await page.getByPlaceholder("Jump to session or filter Stream...").fill(marker);
    const eventOption = page.getByRole("option").filter({ has: page.locator(".kind-badge") });
    await expect(eventOption).toHaveCount(1);
    await expect.soft(eventOption.locator("strong")).toContainText(marker);
    const label = await eventOption.locator("strong").textContent();
    expect.soft(label?.length).toBeLessThanOrEqual(120);
    expect.soft(label).toMatch(/…$/);
    const eventIndex = await page
      .getByRole("option")
      .evaluateAll((options) => options.findIndex((option) => option.querySelector(".kind-badge")));
    for (let index = 0; index < eventIndex; index++) await page.keyboard.press("ArrowDown");
    await expect(eventOption).toHaveAttribute("aria-selected", "true");
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL((url) => url.searchParams.get("event") === capture.eventId);
    await expect(page.locator(".event-flow-row--target")).toBeVisible();
    await page.goto("/projects");
    await expect(page.locator(".project-catalog-pane > .project-picker")).toBeVisible();
    await contained(page.locator(".project-catalog-pane > .project-picker"), width);
    const sizes = await page
      .locator(".project-catalog-pane > .project-picker")
      .evaluate((picker) => ({
        picker: picker.getBoundingClientRect().right,
        pane: picker.parentElement!.getBoundingClientRect().right,
      }));
    expect.soft(sizes.picker).toBeLessThanOrEqual(sizes.pane);
    expect
      .soft(await page.evaluate(() => document.documentElement.scrollWidth))
      .toBeLessThanOrEqual(width);
  });
}

for (const width of [1440, 390]) {
  test(`query Escape dismisses suggestions before open panels at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");
    const query = page.getByLabel("Stream query");
    for (const name of ["Views", "Options", "Filter sources"]) {
      await query.focus();
      await query.fill("");
      const trigger = page.getByRole("button", { name, exact: true });
      const toTrigger = name === "Filter sources" ? "Shift+Tab" : "Tab";
      for (let step = 0; step < 30; step++) {
        if (await trigger.evaluate((element) => element === document.activeElement)) break;
        await page.keyboard.press(toTrigger);
      }
      await expect(trigger).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(trigger).toHaveAttribute("aria-expanded", "true");
      const toQuery = name === "Filter sources" ? "Tab" : "Shift+Tab";
      for (let step = 0; step < 30; step++) {
        if (await query.evaluate((element) => element === document.activeElement)) break;
        await page.keyboard.press(toQuery);
      }
      await expect(query).toBeFocused();
      await page.keyboard.insertText("kind:");
      await expect(page.getByRole("listbox", { name: "Query suggestions" })).toBeVisible();

      await page.keyboard.press("Escape");
      await expect(query).toHaveAttribute("aria-expanded", "false");
      await expect(query).toBeFocused();
      await expect(trigger).toHaveAttribute("aria-expanded", "true");
      await expect(query).toHaveValue("kind:");

      await page.keyboard.press("Escape");
      await expect(trigger).toHaveAttribute("aria-expanded", "false");
      await expect(trigger).toBeFocused();
    }
  });
}

// Empty server responses are intercepted without deleting the shared synthetic seed; failed
// responses are kept distinct. All populated journeys above use the real capture/query routes.
for (const width of [1440, 390]) {
  test(`empty states reflect filters and preserve errors at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    let failFeed = false;
    await page.route(/\/api\/events(?:\?|$)/, async (route) =>
      route.fulfill({
        status: failFeed ? 503 : 200,
        json: failFeed
          ? { error: { message: "Fixture feed unavailable" } }
          : { items: [], count: 0, nextBefore: null, limit: 50 },
      }),
    );
    await page.route(/\/api\/sessions(?:\?|$)/, (route) => route.fulfill({ json: [] }));
    await page.goto("/");
    await expect(
      page.getByText("No meaningful events recorded yet.", { exact: true }),
    ).toBeVisible();
    const query = page.getByLabel("Stream query");
    await query.fill("is:all");
    await query.press("Enter");
    await expect(page.getByText("No events recorded yet.", { exact: true })).toBeVisible();
    await query.fill("kind:Decision");
    await query.press("Enter");
    await expect(
      page.getByText("No stream events match the current filters.", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "My turns", exact: true }).click();
    await expect(
      page.getByText("No human turns match the current filters.", { exact: true }),
    ).toBeVisible();
    await query.fill("");
    await query.press("Enter");
    await expect(page.getByText("No human turns recorded yet.", { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole("button", { name: "My turns", exact: true })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await expect(page.getByText("No human turns recorded yet.", { exact: true })).toBeVisible();
    await page.getByRole("tab", { name: "Browse", exact: true }).click();
    await expect(
      page.getByText("No sessions with human turns recorded yet.", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "My turns", exact: true }).click();
    await expect(page.getByText("No sessions recorded yet.", { exact: true })).toBeVisible();
    await page.getByLabel("Find sessions").fill("missing");
    await expect(
      page.getByText("No sessions match the active filters.", { exact: true }),
    ).toBeVisible();
    failFeed = true;
    await page.goto("/");
    await expect(page.getByText("Fixture feed unavailable", { exact: true })).toBeVisible();
    await expect(page.getByText(/No (meaningful )?events recorded yet\./)).toHaveCount(0);
  });
}
