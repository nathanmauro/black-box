import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

test("Recall restores the completed result after returning from Browse", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const project = `/tmp/recall-return-state-${id}`;
  const headline = `Return to recall ${id}`;
  const captured = await request.post("/api/decisions", {
    data: {
      source: "codex",
      clientSessionId: `recall-return-${id}`,
      repo: project,
      decision: headline,
      rationale: "Keep the completed recall when the reader comes back.",
    },
  });
  expect(captured.ok()).toBeTruthy();
  const { eventId } = (await captured.json()) as { eventId: string };

  const recallRequests: string[] = [];
  page.on("request", (sent) => {
    if (new URL(sent.url()).pathname === "/api/recall") recallRequests.push(sent.url());
  });

  await page.goto(`/recall?project=${encodeURIComponent(project)}&query=${id}`);
  await page.getByRole("button", { name: "Run recall" }).click();
  const card = page.getByRole("article", { name: headline, exact: true });
  await expect(card).toBeVisible();
  expect(recallRequests).toHaveLength(1);
  await expect(page.getByText(/Restored from your last run/)).toHaveCount(0);

  await card.getByRole("link", { name: `Open ${headline} in Browse` }).click();
  await expect(page.locator(`#event-${eventId}`)).toHaveClass(/event-flow-row--target/);

  await page.goBack();
  await expect(page).toHaveURL(/\/recall\?/);
  await expect(card).toBeVisible();
  await expect(
    page.getByText(/Restored from your last run at .+ Run recall to refresh\./),
  ).toBeVisible();
  expect(recallRequests).toHaveLength(1);

  await page.getByRole("button", { name: "Run recall" }).click();
  await expect(page.getByRole("button", { name: "Run recall" })).toBeEnabled();
  await expect(card).toBeVisible();
  await expect(page.getByText(/Restored from your last run/)).toHaveCount(0);
  await expect.poll(() => recallRequests.length).toBe(2);

  // A reload starts with empty memory, even for the exact snapshot that just completed.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Run a recall query" })).toBeVisible();
  await expect(card).toHaveCount(0);
  expect(recallRequests).toHaveLength(2);

  await page.getByRole("button", { name: "Run recall" }).click();
  await expect(card).toBeVisible();
  await expect.poll(() => recallRequests.length).toBe(3);
  await page.getByRole("checkbox", { name: "Observation", exact: true }).check();
  await expect(card).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Run a recall query" })).toBeVisible();
  expect(recallRequests).toHaveLength(3);
});
