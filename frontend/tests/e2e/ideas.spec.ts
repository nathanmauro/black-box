import { expect, test } from "@playwright/test";
import { E2E_SEED_IDEAS, seedE2eIdeas } from "../../src/e2e/seedData";

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
    /^\/sessions\//,
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
