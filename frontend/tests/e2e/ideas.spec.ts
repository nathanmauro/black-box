import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl, E2E_SEED_IDEAS, seedE2eIdeas } from "../../src/e2e/seedData";

// This spec seeds its own ideas (one agent-proposed untouched idea with a quote and legs 7, and one
// human-aside tracked idea) through POST /api/ideas, so a jar without that endpoint fails only here
// instead of aborting global setup for every spec.
const [AGENT_IDEA, HUMAN_IDEA] = E2E_SEED_IDEAS;

test.beforeAll(async () => {
  const baseURL =
    test.info().project.use.baseURL || process.env.PLAYWRIGHT_BASE_URL || "http://127.0.0.1:8799";
  await seedE2eIdeas(baseURL);
});

test("ideas view pins unanswered agent ideas and keeps origin filters in the URL", async ({
  page,
}) => {
  await page.goto("/ideas");
  await expect(page.getByRole("heading", { name: "Ideas", exact: true })).toBeVisible();

  const pinned = page.getByRole("region", { name: "Nobody answered these" });
  const agentRow = pinned.getByRole("article", { name: AGENT_IDEA.title });
  await expect(agentRow).toBeVisible();
  await expect(agentRow.getByText(AGENT_IDEA.oneLiner)).toBeVisible();
  await expect(agentRow.getByRole("meter", { name: "legs 7/10" })).toBeVisible();
  await expect(agentRow.getByText(AGENT_IDEA.quote ?? "")).toBeVisible();
  await expect(agentRow.getByRole("link", { name: /Capturing session/ })).toHaveAttribute(
    "href",
    /^\/\?view=browse&session=[^&]+&event=[^&]+&project=$/,
  );
  await expect(pinned.getByRole("article", { name: HUMAN_IDEA.title })).toHaveCount(0);
  await expect(
    page
      .getByRole("region", { name: "Everything else" })
      .getByRole("article", { name: HUMAN_IDEA.title }),
  ).toBeVisible();

  await page
    .getByRole("group", { name: "Origin" })
    .getByRole("button", { name: "human aside" })
    .click();
  await expect(page).toHaveURL(/origin=human-aside/);
  await expect(page.getByRole("article", { name: HUMAN_IDEA.title })).toBeVisible();
  await expect(page.getByRole("article", { name: AGENT_IDEA.title })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Nobody answered these" })).toHaveCount(0);

  await page.reload();
  await expect(
    page.getByRole("group", { name: "Origin" }).getByRole("button", { name: "human aside" }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("article", { name: HUMAN_IDEA.title })).toBeVisible();
  await expect(page.getByRole("article", { name: AGENT_IDEA.title })).toHaveCount(0);
});

test("the header nav opens the ideas view", async ({ page }) => {
  await page.goto("/");
  await page
    .getByRole("navigation", { name: "Utility" })
    .getByRole("link", { name: "Ideas" })
    .click();
  await expect(page).toHaveURL(/\/ideas$/);
  await expect(page.getByRole("article", { name: AGENT_IDEA.title })).toBeVisible();
});

for (const width of [1440, 390]) {
  test(`Idea source links retain exact evidence with My turns at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const title = `Source proposal ${id}`;
    const capture = await request.post("/api/ideas", {
      data: {
        source: "manual",
        clientSessionId: `idea-source-${id}`,
        repo: `/tmp/idea-source-${id}`,
        title,
        oneLiner: "Preserve the proposal's recorded source.",
        origin: "agent-proposed",
        status: "untouched",
        ideaKey: id,
        notes: "A proposal is evidence, not a human conversation turn.",
      },
    });
    expect(capture.ok()).toBeTruthy();
    const captured = (await capture.json()) as { eventId: string; sessionId: string };
    const canonicalResponse = await request.get(`/api/events/${captured.eventId}`);
    expect(canonicalResponse.ok()).toBeTruthy();
    const canonical = await canonicalResponse.json();
    const owner = await (await request.get(`/api/sessions/${captured.sessionId}`)).json();
    expect(owner.firstHumanTurn).toBeNull();
    const human = await request.post("/api/events", {
      data: {
        source: "manual",
        clientSessionId: `idea-source-human-${id}`,
        cwd: `/tmp/idea-source-other-${id}`,
        eventType: "UserPromptSubmit",
        role: "user",
        text: `Unrelated human aside ${id}`,
      },
    });
    expect(human.ok()).toBeTruthy();

    await page.goto(`/ideas?q=${id}`);
    const card = page.getByRole("article", { name: title, exact: true });
    await expect(card).toBeVisible();
    const humanOnly = page.getByRole("button", { name: "My turns", exact: true });
    await expect(humanOnly).toHaveAttribute("aria-pressed", "false");
    await humanOnly.click();
    await expect(humanOnly).toHaveAttribute("aria-pressed", "true");
    await card.getByRole("link", { name: /Capturing session/ }).focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(
      (url) =>
        url.searchParams.get("session") === captured.sessionId &&
        url.searchParams.get("event") === captured.eventId &&
        url.searchParams.get("project") === "",
    );
    const target = page.locator(`#event-${captured.eventId}`);
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(target).toContainText(title);
    await expect(target).toBeInViewport();
    await expect(page.getByRole("checkbox", { name: "Show memory events" })).not.toBeChecked();
    await expect(humanOnly).toHaveAttribute("aria-pressed", "true");
    await page.reload();
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(target).toBeInViewport();
    await expect(humanOnly).toHaveAttribute("aria-pressed", "true");
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    await page.screenshot({ path: test.info().outputPath(`idea-source-${width}.png`) });

    await page.getByRole("button", { name: "Filter sources" }).click();
    const sources = page.getByRole("group", { name: "Filter by source" });
    await sources.getByRole("button", { name: "Codex", exact: true }).click();
    await expect(target).toHaveCount(0);
    await sources.getByRole("button", { name: "All", exact: true }).click();
    await expect(target).toHaveClass(/event-flow-row--target/);
    expect(await (await request.get(`/api/events/${captured.eventId}`)).json()).toEqual(canonical);
  });
}
