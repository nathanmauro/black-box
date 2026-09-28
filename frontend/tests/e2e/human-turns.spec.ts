import { expect, test } from "@playwright/test";
import { E2E_HUMAN_ASIDE } from "../../src/e2e/seedData";

// Assumes seedBlackBoxE2e ran (global-setup): the black-box-e2e-claude-human-turns session holds a
// plain human aside, a <task-notification> machine prompt, and a Read tool event.
test("human-turns toggle keeps only the human aside, persists, and restores the stream", async ({
  page,
}) => {
  await page.goto("/");
  const toggle = page.getByRole("button", { name: /My turns/ });
  await expect(toggle).toHaveAttribute("aria-pressed", "false");
  await expect(page.locator(".stream-row").first()).toBeVisible();

  await page.keyboard.press("h");
  await expect(toggle).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText(E2E_HUMAN_ASIDE).first()).toBeVisible();
  await expect(page.getByText("task-notification")).toHaveCount(0);
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite")).toHaveCount(0);

  await page.reload();
  await expect(page.getByRole("button", { name: /My turns/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(page.getByText(E2E_HUMAN_ASIDE).first()).toBeVisible();

  await page.keyboard.press("h");
  await expect(page.getByRole("button", { name: /My turns/ })).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  await expect(page.getByText("Use SolidJS + Vite for the UI rewrite").first()).toBeVisible();
});
