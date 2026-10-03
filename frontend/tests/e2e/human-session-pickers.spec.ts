import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`human-only session pickers preserve scope and exact evidence at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const repo = `/tmp/human-pickers-${id}`;
    const aside = `Quick tangent ${id}: retain this human aside among noisy tool results.`;
    const capture = async (client: string, eventType: string, text: string, offset: number) => {
      const response = await request.post("/api/events", {
        data: {
          source: "codex",
          clientSessionId: `${id}-${client}`,
          cwd: repo,
          eventType,
          text,
          toolName: eventType === "PostToolUse" ? "Read" : undefined,
          observedAt: new Date(Date.now() + offset).toISOString(),
          metadata: {
            title: client.startsWith("machine")
              ? "Machine-only picker fixture"
              : "Human picker fixture",
          },
        },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { eventId: string; sessionId: string };
    };
    const human = await capture("human", "UserPromptSubmit", aside, 1000);
    await capture("human", "PostToolUse", "Noisy result", 1100);
    let machine = human;
    // Keep the shared fixture small; limit=1 below proves server filtering precedes LIMIT.
    for (let i = 0; i < 3; i++)
      machine = await capture(
        `machine-${i}`,
        "PostToolUse",
        "Exact nonhuman picker evidence",
        10000 + i,
      );
    const projects = await (await request.get("/api/projects")).json();
    const key = projects.find((p: { canonicalKey: string }) => p.canonicalKey === repo).projectKey;
    const scoped = await (
      await request.get(`/api/projects/${key}/sessions?limit=1&humanOnly=true`)
    ).json();
    expect(scoped.map((s: { id: string }) => s.id)).toEqual([human.sessionId]);
    expect(scoped[0].firstHumanTurn).toBe(aside);
    const globalHuman = await (await request.get("/api/sessions?limit=1&humanOnly=true")).json();
    expect(globalHuman.map((s: { id: string }) => s.id)).toEqual([human.sessionId]);

    await page.goto("/?project=&q=source%3Acodex");
    const toggle = page.getByRole("button", { name: "My turns", exact: true });
    const query = page.getByRole("combobox", { name: "Stream query" });
    await query.focus();
    await page.keyboard.press("h");
    await expect(toggle).toHaveAttribute("aria-pressed", "false");
    await query.fill("source:codex");
    await page.keyboard.press("Enter");
    await toggle.focus();
    await page.keyboard.press("h");
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".stream-row").filter({ hasText: aside })).toBeVisible();
    await page.reload();
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("link", { name: "Browse", exact: true }).click();
    await expect(page.locator(".timeline-pane")).toContainText(aside);
    await expect(
      page.locator(".session-row").filter({ hasText: "Machine-only picker fixture" }),
    ).toHaveCount(0);
    const showChooser = async () => {
      if (width < 880) await page.getByRole("button", { name: /^Sessions / }).click();
    };
    await toggle.click();
    await showChooser();
    await page
      .locator(".session-row")
      .filter({ hasText: "Machine-only picker fixture" })
      .first()
      .click();
    await expect(page.locator(".detail-header h1")).toHaveText("Machine-only picker fixture");
    await toggle.click();
    await expect(page.locator(".timeline-pane")).toContainText(aside);
    await expect(page).toHaveURL((url) => url.searchParams.get("session") === human.sessionId);
    await page.reload();
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".timeline-pane")).toContainText(aside);

    await page.getByRole("button", { name: "Open command palette" }).click();
    const palette = page.getByRole("dialog", { name: "Command palette" });
    await expect(palette.getByRole("option").filter({ hasText: aside })).toBeVisible();
    await palette.getByRole("textbox").fill(id);
    await expect(
      palette.getByRole("option").filter({ hasText: "Machine-only picker fixture" }),
    ).toHaveCount(0);
    await palette.getByRole("textbox").press("h");
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Escape");

    await page.goto(`/?view=browse&project=${key}`);
    await expect(page.locator(".timeline-pane")).toContainText(aside);
    await expect(page.locator(".session-row")).toHaveCount(1);
    await showChooser();
    await expect(page.locator(".session-row").filter({ hasText: aside })).toBeVisible();
    if (width < 880) await page.keyboard.press("Escape");
    await toggle.click();
    await expect(page.locator(".session-row")).toHaveCount(4);
    await toggle.click();
    await expect(page.locator(".session-row")).toHaveCount(1);

    await page.goto(
      `/?view=browse&session=${machine.sessionId}&event=${machine.eventId}&project=${key}`,
    );
    const target = page.locator(`#event-${machine.eventId}`);
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(target).toContainText("Exact nonhuman picker evidence");
    await expect(target).toBeInViewport();
    await page.reload();
    await expect(target).toBeVisible();
    await page.getByRole("button", { name: "Filter sources" }).click();
    await page
      .getByRole("group", { name: "Filter by source" })
      .getByRole("button", { name: "Claude", exact: true })
      .click();
    await expect(target).toHaveCount(0);
    await page
      .getByRole("group", { name: "Filter by source" })
      .getByRole("button", { name: "All", exact: true })
      .click();
    await expect(target).toBeVisible();
    await page.getByRole("button", { name: "Filter sources" }).click();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await page.screenshot({ path: test.info().outputPath(`human-picker-${width}.png`) });

    const otherRepo = `/tmp/human-pickers-other-${id}`;
    const otherCapture = await request.post("/api/events", {
      data: {
        source: "claude",
        clientSessionId: `other-${id}`,
        cwd: otherRepo,
        eventType: "UserPromptSubmit",
        text: "Human turn in a different project",
      },
    });
    expect(otherCapture.ok()).toBeTruthy();
    const updatedProjects = await (await request.get("/api/projects")).json();
    const otherKey = updatedProjects.find(
      (p: { canonicalKey: string }) => p.canonicalKey === otherRepo,
    ).projectKey;
    await page.goto(`/?view=browse&session=${machine.sessionId}&project=${otherKey}`);
    await expect(page.locator(".timeline-pane")).toContainText("Human turn in a different project");
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("session") === machine.sessionId &&
        url.searchParams.get("project") === otherKey,
    );

    await page.goto(`/sessions/missing-${id}`);
    if (width < 880) {
      await expect(page.getByRole("complementary", { name: "Session chooser" })).toBeVisible();
      await expect(page.locator(".session-row--active")).toHaveCount(0);
    } else await expect(page.getByRole("heading", { name: "Select a session" })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`missing-${id}$`));
    await page.goto(
      `/?view=browse&session=${machine.sessionId}&event=${machine.eventId}&project=${key}`,
    );
    await expect(target).toBeVisible();

    if (width === 390) {
      for (const [w, h] of [
        [320, 700],
        [844, 390],
      ]) {
        await page.setViewportSize({ width: w, height: h });
        const metrics = await page.locator(".timeline-pane").evaluate((el) => {
          const box = el.getBoundingClientRect();
          return {
            width: innerWidth,
            height: innerHeight,
            readerHeight: box.height,
            visibleReader: Math.max(0, Math.min(innerHeight, box.bottom) - Math.max(0, box.top)),
            documentHeight: document.documentElement.scrollHeight,
            documentWidth: document.documentElement.scrollWidth,
          };
        });
        console.log("SMALL_READER_MEASUREMENT", JSON.stringify(metrics));
        await page.screenshot({ path: test.info().outputPath(`reader-measurement-${w}x${h}.png`) });
      }
    }
  });
}
