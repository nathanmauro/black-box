import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

type Capture = { eventId: string; sessionId: string };
for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`project continuity preserves evidence and explicit decision history at ${viewport.width}px`, async ({
    page,
    request,
    context,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize(viewport);
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const id = randomUUID();
    const alpha = `/tmp/continuity-alpha-${id}`;
    const beta = `/tmp/continuity-beta-${id}`;
    const originalTitle = `Use SQLite storage for alpha ${id}`;
    const otherTitle = `Use SQLite storage for beta ${id}`;
    const originalResponse = await request.post("/api/decisions", {
      data: {
        source: "codex",
        clientSessionId: `continuity-original-${id}`,
        repo: alpha,
        decision: originalTitle,
        rationale: "Keep local development self-contained.",
        openLoops: ["Verify recovery before shared access"],
      },
    });
    expect(originalResponse.ok()).toBeTruthy();
    const original = (await originalResponse.json()) as Capture;
    expect(
      (
        await request.post("/api/decisions", {
          data: {
            source: "claude",
            clientSessionId: `continuity-other-${id}`,
            repo: beta,
            decision: otherTitle,
            rationale: "A separate project with the same topic.",
          },
        })
      ).ok(),
    ).toBeTruthy();
    expect(
      (
        await request.post("/api/handoffs", {
          data: {
            source: "codex",
            clientSessionId: `continuity-handoff-${id}`,
            repo: alpha,
            contextSummary: "Storage experiment completed; recovery still needs verification.",
            openLoops: ["Verify recovery before shared access"],
            nextAction: "Reproduce the recovery path",
          },
        })
      ).ok(),
    ).toBeTruthy();
    const projects = (await (await request.get("/api/projects")).json()) as Array<{
      projectKey: string;
      canonicalKey: string;
    }>;
    const selected = projects.find((project) => project.canonicalKey === alpha)!;
    expect(selected).toBeDefined();

    await page.goto("/recall");
    await page.getByRole("button", { name: /^Project / }).click();
    await page.getByLabel("Search projects").fill(`continuity-alpha-${id}`);
    await page.getByRole("option", { name: new RegExp(`continuity-alpha-${id}`) }).click();
    const question = page.getByLabel("Question", { exact: true });
    await question.fill("SQLite storage");
    const suggestion = page.getByRole("option", { name: new RegExp(originalTitle) });
    await expect(suggestion).toBeVisible();
    await expect(page.getByRole("option", { name: new RegExp(otherTitle) })).toHaveCount(0);
    await question.press("ArrowDown");
    await question.press("Enter");
    const evidence = page.getByRole("region", { name: "Selected evidence" });
    await expect(evidence.getByRole("article", { name: originalTitle })).toBeVisible();
    await evidence.getByRole("link", { name: `Open ${originalTitle} in Browse` }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("session") === original.sessionId &&
        url.searchParams.get("event") === original.eventId,
    );
    await expect(page.locator(".event-flow-row--target")).toBeVisible();

    await page.goto(`/projects/${encodeURIComponent(selected.projectKey)}`);
    await page.getByRole("link", { name: "Resume this project" }).click();
    await expect(page).toHaveURL(
      (url) =>
        url.pathname === "/recall" &&
        url.searchParams.get("project") === alpha &&
        url.searchParams.get("withinHours") === "8760" &&
        !url.searchParams.has("run"),
    );
    await expect(page.getByRole("heading", { name: "Latest recorded context" })).toBeVisible();
    await expect(page.getByRole("article", { name: originalTitle })).toBeVisible();
    await expect(page.getByRole("article", { name: otherTitle })).toHaveCount(0);
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied.length).toBeLessThanOrEqual(24_000);
    expect(copied).toContain(original.eventId);
    expect(copied).toContain(original.sessionId);
    expect(copied).toContain("Recorded open questions: Verify recovery");
    expect(copied).not.toContain(otherTitle);

    const originalCard = page.getByRole("article", { name: originalTitle });
    await originalCard.getByRole("button", { name: "Replace decision" }).click();
    const nextTitle = `Use PostgreSQL for shared storage ${id}`;
    await page.getByLabel("New decision", { exact: true }).fill(nextTitle);
    await expect(page.getByRole("button", { name: "Record replacement" })).toBeDisabled();
    await page
      .getByLabel("Why this replaces the earlier decision")
      .fill("The shared deployment now requires remote access.");
    await page.getByRole("button", { name: "Record replacement" }).click();
    await expect(page.getByRole("article", { name: nextTitle })).toBeVisible();
    await expect(page.getByRole("article", { name: originalTitle })).toHaveCount(0);
    await page.getByLabel("Include replaced decisions").check();
    await page.getByRole("button", { name: "Run recall" }).click();
    await expect(page.getByRole("article", { name: originalTitle })).toBeVisible();
    await expect(
      page
        .getByRole("article", { name: originalTitle })
        .getByRole("button", { name: "Replace decision" }),
    ).toHaveCount(0);
    const historyUrl = page.url();
    const replacementSource = page
      .getByRole("article", { name: nextTitle })
      .getByRole("link", { name: `Open ${nextTitle} in Browse` });
    const replacementUrl = new URL((await replacementSource.getAttribute("href"))!, historyUrl);
    expect(replacementUrl.searchParams.get("session")).not.toBe(original.sessionId);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: test.info().outputPath(`continuity-${viewport.width}.png`),
      fullPage: true,
    });

    // Relation sources live in different sessions: both links must resolve their owning session.
    await page
      .getByRole("article", { name: nextTitle })
      .getByRole("link", { name: "earlier decision" })
      .click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("session") === original.sessionId &&
        url.searchParams.get("event") === original.eventId,
    );
    await expect(page.locator(".event-flow-row--target")).toBeVisible();
    await page.goto(`${historyUrl}&run=1`);
    await page
      .getByRole("article", { name: originalTitle })
      .getByRole("link", { name: "replacement decision" })
      .click();
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("session") === replacementUrl.searchParams.get("session") &&
        url.searchParams.get("event") === replacementUrl.searchParams.get("event"),
    );
    await expect(page.locator(".event-flow-row--target")).toBeVisible();
  });
}

test("empty legacy launcher scope still recalls after URL normalization", async ({ page }) => {
  await page.goto("/recall?scope=&run=1");
  await expect(page.getByRole("heading", { name: "Latest recorded context" })).toBeVisible();
  await expect(page.getByLabel("Question", { exact: true })).toHaveValue("");
});
