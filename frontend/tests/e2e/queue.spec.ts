import { test, expect, type APIRequestContext, type Page } from "@playwright/test";

const SHOT_DIR = "test-results/shots";
const PREFIX = "black-box-saga-e2e";
const PROJECT = `${PREFIX}-project`;
const BLOCK_LANE = `${PREFIX}-review`;
const DONE_LANE = `${PREFIX}-implementation`;
const BLOCK_TITLE = `${PREFIX} unblock deterministic fixture`;
const DONE_TITLE = `${PREFIX} complete deterministic fixture`;
const HANDOFF_HEADLINE = `${PREFIX} deterministic completion handoff`;

type SpecRecord = { id: string };
type TaskRecord = {
  id: string;
  title: string;
  lane: string;
  status: string;
  resultHandoffId?: string | null;
};
type TaskChange = { snapshot: { task: TaskRecord } };

test("agent coordination loop stays live from Open through Blocked, reset, Done, Handoff, and Recall", async ({ page, request }) => {
  const spec = await postJson<SpecRecord>(request, "/api/specs", {
    projectKey: PROJECT,
    title: `${PREFIX} frozen queue specification`,
    body: `${PREFIX} exact frozen body for the deterministic coordination loop.`,
    specRef: { fixture: PREFIX, version: 1 },
    actor: `${PREFIX}-planner`,
  });
  const blockedTask = await enqueue(request, spec.id, BLOCK_TITLE, BLOCK_LANE, 20);
  const completedTask = await enqueue(request, spec.id, DONE_TITLE, DONE_LANE, 10);

  await page.goto(`/board?project=${encodeURIComponent(PROJECT)}`);
  await expect(page.getByRole("heading", { name: "Coordination board" })).toBeVisible();
  await expect(page.getByLabel("Board status").getByText("live", { exact: true })).toBeVisible();

  await test.step("Open: both lane fixtures are visible in the Board snapshot", async () => {
    await expect(taskInColumn(page, "Open", BLOCK_TITLE, "Open")).toBeVisible();
    await expect(taskInColumn(page, "Open", DONE_TITLE, "Open")).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-open.png`, fullPage: true });
  });

  await test.step("Claim: the requested lane wins and moves live to In Progress", async () => {
    const claimed = await postJson<TaskChange>(request, "/api/tasks/claim", {
      lane: BLOCK_LANE,
      agent: `${PREFIX}-reviewer`,
    });
    expect(claimed.snapshot.task.id).toBe(blockedTask.id);
    expect(claimed.snapshot.task.lane).toBe(BLOCK_LANE);
    await expect(taskInColumn(page, "In Progress", BLOCK_TITLE, "In progress")).toBeVisible();
    await expect(taskInColumn(page, "Open", DONE_TITLE, "Open")).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-claimed.png`, fullPage: true });
  });

  await test.step("Blocked: the lifecycle frame moves the task without a page reload", async () => {
    await patchJson<TaskChange>(request, `/api/tasks/${blockedTask.id}`, {
      actor: `${PREFIX}-reviewer`,
      status: "blocked",
      blockedReason: `${PREFIX} deterministic dependency`,
    });
    await expect(taskInColumn(page, "Blocked", BLOCK_TITLE, "Blocked")).toBeVisible();
    await expect(page.getByText(`${PREFIX} deterministic dependency`)).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-blocked.png`, fullPage: true });
  });

  await test.step("Reset: the Board releases ownership and returns the task to Open", async () => {
    await taskInColumn(page, "Blocked", BLOCK_TITLE, "Blocked").click();
    const detail = page.getByLabel("Task detail");
    await expect(detail.getByText(`${PREFIX} deterministic dependency`)).toBeVisible();
    await detail.getByRole("button", { name: "Reset task to open" }).click();
    await expect(taskInColumn(page, "Open", BLOCK_TITLE, "Open")).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-reset-open.png`, fullPage: true });
  });

  let handoffId = "";
  await test.step("Done: another lane completes and appears live without a page reload", async () => {
    const claimed = await postJson<TaskChange>(request, "/api/tasks/claim", {
      lane: DONE_LANE,
      agent: `${PREFIX}-implementer`,
    });
    expect(claimed.snapshot.task.id).toBe(completedTask.id);
    const completed = await postJson<TaskChange>(request, `/api/tasks/${completedTask.id}/complete`, {
      actor: `${PREFIX}-implementer`,
      source: "codex",
      clientSessionId: `${PREFIX}-completion-session`,
      summary: HANDOFF_HEADLINE,
      openLoops: [`${PREFIX} docs remain a separate release task`],
      nextAction: `${PREFIX} verify the release gates`,
    });
    handoffId = completed.snapshot.task.resultHandoffId || "";
    expect(handoffId).not.toBe("");
    await expect(taskInColumn(page, "Done", DONE_TITLE, "Done")).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-done.png`, fullPage: true });
  });

  await test.step("Handoff: completed task detail resolves the exact linked artifact", async () => {
    await taskInColumn(page, "Done", DONE_TITLE, "Done").click();
    const handoff = page.locator(`#handoff-${handoffId}`);
    await expect(handoff).toBeVisible();
    await expect(handoff.locator("code")).toHaveText(handoffId);
    await expect(handoff.getByText(HANDOFF_HEADLINE, { exact: true })).toBeVisible();
    await expect(handoff.locator(".board-handoff-body p").filter({ hasText: "Next" }))
      .toContainText(`${PREFIX} verify the release gates`);
    await page.screenshot({ path: `${SHOT_DIR}/queue-handoff.png`, fullPage: true });
  });

  await test.step("Recall: the completion Handoff headline is independently retrievable", async () => {
    await page.goto("/recall");
    await page.getByPlaceholder(/a topic/).fill(HANDOFF_HEADLINE);
    await page.getByRole("button", { name: "Run recall" }).click();
    await expect(page.getByRole("article").filter({ hasText: HANDOFF_HEADLINE })).toBeVisible();
    await page.screenshot({ path: `${SHOT_DIR}/queue-recall.png`, fullPage: true });
  });

  await test.step("Direct Board navigation restores project and lane filters from the URL", async () => {
    const freshPage = await page.context().newPage();
    await freshPage.goto(`/board?project=${encodeURIComponent(PROJECT)}&lane=${encodeURIComponent(DONE_LANE)}`);
    await expect(freshPage.getByLabel("Project")).toHaveValue(PROJECT);
    await expect(freshPage.getByLabel("Lane")).toHaveValue(DONE_LANE);
    await expect(taskInColumn(freshPage, "Done", DONE_TITLE, "Done")).toBeVisible();
    await expect(freshPage.getByText(BLOCK_TITLE)).toHaveCount(0);
    await freshPage.screenshot({ path: `${SHOT_DIR}/queue-direct-filter.png`, fullPage: true });
    await freshPage.close();
  });
});

async function enqueue(
  request: APIRequestContext,
  specId: string,
  title: string,
  lane: string,
  priority: number,
): Promise<TaskRecord> {
  const change = await postJson<TaskChange>(request, "/api/tasks", {
    specId,
    title,
    lane,
    priority,
    actor: `${PREFIX}-planner`,
  });
  return change.snapshot.task;
}

function taskInColumn(page: Page, column: string, title: string, status: string) {
  return page.locator(`section[aria-label="${column} tasks"]`).getByRole("button", {
    name: `${title}, ${status}`,
    exact: true,
  });
}

async function postJson<T>(request: APIRequestContext, path: string, data: unknown): Promise<T> {
  const response = await request.post(path, { data });
  if (!response.ok()) {
    expect(response.ok(), `POST ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
  return await response.json() as T;
}

async function patchJson<T>(request: APIRequestContext, path: string, data: unknown): Promise<T> {
  const response = await request.patch(path, { data });
  if (!response.ok()) {
    expect(response.ok(), `PATCH ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
  return await response.json() as T;
}
