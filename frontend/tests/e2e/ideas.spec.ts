import { expect, test } from "@playwright/test";
import { E2E_SEED_IDEAS } from "../../src/e2e/seedData";

// Assumes seedBlackBoxE2e ran (global-setup): one agent-proposed untouched idea with a quote and
// legs 7, and one human-aside tracked idea, both captured through POST /api/ideas.
const [AGENT_IDEA, HUMAN_IDEA] = E2E_SEED_IDEAS;

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
