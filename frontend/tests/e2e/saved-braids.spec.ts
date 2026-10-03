import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";
import type { ProjectSavedMeld } from "../../src/lib/api";

for (const [width, height] of [
  [1440, 900],
  [390, 844],
  [844, 390],
]) {
  test(`saved braids preserve synthesis and ordered sources at ${width}x${height}`, async ({
    page,
    request,
  }, info) => {
    assertSafeSeedBaseUrl(info.project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height });
    const marker = `braid-${randomUUID()}`;
    const sources: { sessionId: string; eventId: string; text: string; repo: string }[] = [];
    for (const project of ["alpha", "beta"]) {
      const text = `${marker} ${project}: preserve this original source.`;
      const repo = `/tmp/${marker}/${project}`;
      const response = await request.post("/api/events", {
        data: {
          source: project === "alpha" ? "claude" : "codex",
          clientSessionId: `${marker}-${project}`,
          cwd: repo,
          eventType: project === "alpha" ? "UserPromptSubmit" : "Stop",
          role: project === "alpha" ? "user" : "assistant",
          text,
          metadata: { title: `${project} source` },
        },
      });
      expect(response.ok()).toBeTruthy();
      sources.push({ ...(await response.json()), text, repo });
    }
    const body = `A saved synthesis for ${marker}.\n\n  Indented detail stays readable.\n<script>plain caller text</script>\nFinal evidence line.`;
    const saved: ProjectSavedMeld[] = [];
    for (let index = 0; index < 21; index++) {
      const response = await request.post("/api/melds", {
        headers: { "Content-Type": "application/json" },
        // Send a valid Java Long as raw JSON, without first rounding it in JavaScript.
        data: JSON.stringify({
          title: `${marker} synthesis ${index}`,
          body,
          provider: "fixture-provider",
          model: "fixture-model",
          promptVersion: "fixture-v1",
          executionMode: "export_bundle",
          savedFromPreview: false,
          sessionIds: [sources[1].sessionId, sources[0].sessionId],
          metadata: {
            kind: "braid",
            callerReference: "unverified caller claim",
            largeInteger: "RAW_LONG_FIXTURE",
          },
        }).replace('"RAW_LONG_FIXTURE"', "9007199254740993"),
      });
      expect(response.ok()).toBeTruthy();
      saved.push(await response.json());
    }
    const oldest = saved[0];
    expect(oldest.projectKey).toBeNull();
    expect(oldest.canonicalKey).toBeNull();
    const firstPage = await (
      await request.get("/api/melds?kind=braid&scope=unassigned&limit=20")
    ).json();
    expect(firstPage.count).toBe(20);
    expect(firstPage.nextBefore).toBeTruthy();
    expect(firstPage.items.some((item: ProjectSavedMeld) => item.id === oldest.id)).toBe(false);

    const listUrls: URL[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.pathname === "/api/melds") listUrls.push(url);
    });
    let failNextPage = true;
    await page.route(/\/api\/melds\?/, (route) => {
      if (new URL(route.request().url()).searchParams.has("before") && failNextPage) {
        failNextPage = false;
        return route.fulfill({ status: 503, json: { message: "Fixture next page unavailable" } });
      }
      return route.continue();
    });
    await page.goto("/projects");
    await page.getByRole("link", { name: "Saved braids", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Saved braids", exact: true })).toBeVisible();
    await expect(page.getByText("20 loaded", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Load more saved braids" }).click();
    await expect(page.getByText("Fixture next page unavailable")).toBeVisible();
    await expect(page.locator("#saved-braids-list nav a")).toHaveCount(20);
    await page.getByRole("button", { name: "Retry saved braid list" }).click();
    const oldestLink = page.getByRole("link").filter({ hasText: oldest.title });
    await oldestLink.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("meld") === oldest.id && url.searchParams.get("view") === "braids",
    );
    const detail = page.locator(".saved-braid-detail");
    await expect(detail.getByRole("heading", { name: oldest.title })).toBeVisible();
    await expect(detail.getByRole("heading", { name: oldest.title })).toBeInViewport();
    await expect(detail.locator(".saved-braid-body")).toBeInViewport();
    await expect(page.locator('.projects-workspace-nav a[aria-current="page"]')).toHaveText(
      "Saved braids",
    );
    if (width > 760) {
      await expect(page.locator('.saved-braids-list a[aria-current="page"]')).toHaveCount(1);
      await expect(page.locator('.saved-braids-list a[aria-current="page"]')).toContainText(
        oldest.title,
      );
    }
    expect(await detail.locator(".saved-braid-body").textContent()).toBe(oldest.body);
    await expect(detail.locator("script")).toHaveCount(0);
    await expect(detail).toContainText("Caller-declared provenance");
    await expect(detail).toContainText(oldest.createdAt);
    await expect(detail.locator(".saved-braid-metadata")).toHaveCount(0);
    expect(listUrls.length).toBeGreaterThanOrEqual(2);
    for (const url of listUrls) {
      expect([...url.searchParams.keys()].sort()).toEqual(
        url.searchParams.has("before")
          ? ["before", "kind", "limit", "scope"]
          : ["kind", "limit", "scope"],
      );
      expect(url.searchParams.get("kind")).toBe("braid");
      expect(url.searchParams.get("scope")).toBe("unassigned");
    }
    if (width < 761) {
      const chooser = page.getByRole("button", { name: "Choose a saved braid" });
      await chooser.click();
      await page.locator('#saved-braids-list a[aria-current="page"]').focus();
      await page.keyboard.press("Enter");
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
      await expect(detail).toBeFocused();
    }
    await page.reload();
    await expect(detail.getByRole("heading", { name: oldest.title })).toBeVisible();
    // Selection survives even though this old artifact is outside the newly fetched first page.
    await expect(page.locator('.saved-braids-list a[aria-current="page"]')).toHaveCount(0);
    if (width < 761) {
      const chooser = page.getByRole("button", { name: "Choose a saved braid" });
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
      await chooser.focus();
      await page.keyboard.press("Enter");
      await expect(chooser).toHaveAttribute("aria-expanded", "true");
      await page.locator("#saved-braids-list a").first().focus();
      await page.keyboard.press("Escape");
      await expect(chooser).toBeFocused();
      await expect(chooser).toHaveAttribute("aria-expanded", "false");
    }
    const sourceLinks = detail.locator(".saved-braid-sources a");
    await expect(sourceLinks).toHaveCount(2);
    await expect(sourceLinks.nth(0)).toHaveAttribute(
      "href",
      `/sessions/${sources[1].sessionId}?reveal=session`,
    );
    await expect(sourceLinks.nth(1)).toHaveAttribute(
      "href",
      `/sessions/${sources[0].sessionId}?reveal=session`,
    );
    const humanToggle = page.getByRole("button", { name: "My turns", exact: true });
    await humanToggle.click();
    await page.getByRole("button", { name: "Filter sources" }).click();
    const claudeFilter = page
      .locator("#source-filter-panel")
      .getByRole("button", { name: /Claude/ });
    await claudeFilter.click();
    await page.keyboard.press("Escape");
    await sourceLinks.first().focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(
      (url) =>
        url.pathname === `/sessions/${sources[1].sessionId}` &&
        url.searchParams.get("reveal") === "session",
    );
    await expect(page.locator(".timeline-pane")).toContainText(sources[1].text);
    await expect(
      page.getByText("Showing this linked session in full. Saved Browse filters are unchanged."),
    ).toBeVisible();
    await expect(humanToggle).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".session-row--active .session-linked-badge")).toHaveText("Linked");
    await page.getByRole("button", { name: "Filter sources" }).click();
    await expect(claudeFilter).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Escape");
    if (width < 881) await page.getByRole("button", { name: /^Sessions / }).click();
    await page.locator(".session-row").filter({ hasText: sources[0].text }).click();
    await expect(page).toHaveURL(
      (url) => url.pathname === `/sessions/${sources[0].sessionId}` && !url.search,
    );
    await expect(
      page.getByText("Showing this linked session in full. Saved Browse filters are unchanged."),
    ).toHaveCount(0);
    await page.goBack();
    await expect(page.locator(".timeline-pane")).toContainText(sources[1].text);
    await page.reload();
    await expect(humanToggle).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".timeline-pane")).toContainText(sources[1].text);
    await page.goBack();
    await expect(detail.getByRole("heading", { name: oldest.title })).toBeVisible();
    await page.getByText("Caller metadata (unverified)", { exact: true }).click();
    await expect(detail.locator(".saved-braid-metadata")).toContainText("unverified caller claim");
    const downloading = page.waitForEvent("download");
    await page.getByRole("button", { name: "Download saved artifact JSON" }).click();
    const download = await downloading;
    expect(download.suggestedFilename()).toBe("saved-artifact.json");
    const chunks: Buffer[] = [];
    for await (const chunk of (await download.createReadStream())!) chunks.push(Buffer.from(chunk));
    const downloaded = Buffer.concat(chunks).toString("utf8");
    const canonicalJson = await (await request.get(`/api/melds/${oldest.id}`)).text();
    expect(downloaded).toBe(canonicalJson);
    expect(downloaded).toMatch(/"largeInteger"\s*:\s*9007199254740993/);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: info.outputPath(`saved-braid-${width}x${height}.png`),
      fullPage: true,
    });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    );
    const original = await (await request.get(`/api/events/${sources[1].eventId}`)).json();
    expect(original.text).toBe(sources[1].text);
    expect(await (await request.get(`/api/melds/${oldest.id}`)).json()).toEqual(oldest);
  });
}

for (const width of [1440, 390]) {
  test(`saved braid failures and empty catalogs are honest at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    let catalogFails = false;
    let listFails = false;
    let detailMissing = true;
    await page.route(/\/api\/projects$/, (route) =>
      route.fulfill({
        status: catalogFails ? 503 : 200,
        json: catalogFails ? { message: "Fixture catalog offline" } : [],
      }),
    );
    await page.route(/\/api\/melds\?/, (route) =>
      route.fulfill({
        status: listFails ? 503 : 200,
        json: listFails
          ? { message: "Fixture braid list offline" }
          : { items: [], count: 0, nextBefore: null },
      }),
    );
    await page.route(/\/api\/melds\/unknown$/, (route) =>
      route.fulfill({
        status: detailMissing ? 404 : 200,
        json: detailMissing
          ? { message: "Not found" }
          : {
              id: "unknown",
              projectKey: "ordinary",
              canonicalKey: "/fixture/ordinary",
              title: "Ordinary saved meld",
              metadata: { kind: "braid" },
            },
      }),
    );
    await page.goto("/projects");
    await expect(page.getByText("No observed projects", { exact: true })).toBeVisible();
    catalogFails = true;
    await page.reload();
    await expect(page.getByText("Project catalog unavailable", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Saved braids", exact: true }).click();
    await expect(page.getByText("No saved unassigned braids yet.", { exact: true })).toBeVisible();
    listFails = true;
    await page.reload();
    await expect(page.getByText("Fixture braid list offline")).toBeVisible();
    await expect(page.getByText("No saved unassigned braids yet.", { exact: true })).toHaveCount(0);
    listFails = false;
    await page.getByRole("button", { name: "Retry saved braid list" }).click();
    await expect(page.getByText("No saved unassigned braids yet.", { exact: true })).toBeVisible();
    await page.goto("/projects?view=braids&meld=unknown");
    await expect(
      page.getByText("This saved artifact could not be loaded (not found)."),
    ).toBeVisible();
    detailMissing = false;
    await page.getByRole("button", { name: "Retry saved artifact" }).click();
    await expect(
      page.getByText("This saved meld belongs to a project; it is not an unassigned braid."),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: "Open owning project" })).toHaveAttribute(
      "href",
      "/projects/ordinary",
    );
    // An absent source ID is a real 404, never an invitation to select a different session.
    await page.goto("/sessions/missing-braid-source?reveal=session");
    await expect(page.getByRole("heading", { name: "Linked session unavailable" })).toBeVisible();
    await page.getByRole("button", { name: "Retry linked session" }).click();
    await expect(page.getByRole("heading", { name: "Linked session unavailable" })).toBeVisible();
    await expect(page.locator(".timeline-pane")).toHaveCount(0);
    await expect(page).toHaveURL(/\/sessions\/missing-braid-source\?reveal=session$/);
  });
}
