import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { privateTmuxEnv } from "./runtime-safety";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const SHOT_DIR = "test-results/shots";
const PREFIX = "black-box-sdlc-e2e";
const REPO_ROOT = path.resolve(import.meta.dirname, "..", "..", "..");
const STORY_LANES = ["gate", "sdlc:plan", "auto", "sdlc:review"] as const;

type TaskRecord = {
  id: string;
  specId: string;
  title: string;
  lane: string;
  status: string;
  resultHandoffId?: string | null;
};
type TaskSpec = { id: string; body: string };
type TaskSnapshot = { task: TaskRecord; spec: TaskSpec };
type TaskEvent = {
  id: string;
  actor: string;
  type: string;
  detail?: {
    kind?: string;
    text?: string;
    dataJson?: Record<string, unknown>;
  } | null;
};
type StoryHarness = {
  title: string;
  scratchDir: string;
  runnerConfigPath: string;
  runner?: ChildProcess;
};

test("SDLC story waits at both approvals, builds, reviews, and ships locally", async ({
  page,
  request,
  baseURL,
}) => {
  test.setTimeout(300_000);
  if (!baseURL) throw new Error("Playwright baseURL is required for the SDLC E2E");

  const harness = createHarness("happy");
  let specId = "";

  try {
    const gate = await createSdlcStory(page, request, baseURL, harness);
    specId = gate.spec.id;
    expect(gate.spec.body).toContain("mode: sdlc");

    await page.goto(boardLaneUrl(baseURL, "gate"));
    await test.step("Gate starts open and promotes the story to the plan lane", async () => {
      await expect(taskInColumn(page, "Open", harness.title, "Open")).toBeVisible({ timeout: 15_000 });
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-gate-open.png`, fullPage: true });

      harness.runner = startRunner(harness, baseURL);
      const completedGate = await waitForTaskStatus(request, "gate", harness.title, "done");
      expect(completedGate.task.resultHandoffId).toBeTruthy();
    });

    await test.step("Plan annotation lands and the completed card waits fail-closed", async () => {
      await waitForTask(request, "sdlc:plan", harness.title);
      await page.goto(boardLaneUrl(baseURL, "sdlc:plan"));
      await expect(taskWithAnyStatus(page, harness.title)).toBeVisible({ timeout: 30_000 });
      await taskWithAnyStatus(page, harness.title).click();

      const plan = await waitForTaskStatus(request, "sdlc:plan", harness.title, "done");
      expect(plan.task.resultHandoffId).toBeTruthy();
      const detail = page.getByLabel("Task detail");
      await expect(detail.locator(".board-annotation-row--plan .board-annotation-document"))
        .toContainText("## Approach", { timeout: 30_000 });
      await expect(detail.getByText("Awaiting approval", { exact: true }).first()).toBeVisible();
      await expect(detail.getByLabel("Plan approval")).toBeVisible();
      expect(await findTaskBySpec(request, "auto", specId)).toBeUndefined();
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-plan-awaiting-approval.png`, fullPage: true });
    });

    await test.step("Plan approval enqueues the build exactly at the human gate", async () => {
      const approval = page.getByLabel("Task detail").getByLabel("Plan approval");
      await approval.getByRole("button", { name: "Approve", exact: true }).click();
      await expect(approval.getByText(/Approved by nathan at/)).toBeVisible({ timeout: 15_000 });
      await waitForTask(request, "auto", harness.title);
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-plan-approved.png`, fullPage: true });
    });

    let buildWorktree = "";
    await test.step("Build commits, defers shipping, and preserves its worktree for review", async () => {
      const build = await waitForTaskStatus(request, "auto", harness.title, "done");
      expect(build.task.resultHandoffId).toBeTruthy();
      const buildState = await waitForEvent(request, build.task.id, (event) => (
        event.detail?.kind === "progress"
        && typeof event.detail.dataJson?.branch === "string"
        && typeof event.detail.dataJson?.worktree === "string"
      ));
      buildWorktree = String(buildState.detail?.dataJson?.worktree || "");
      expect(buildWorktree).not.toBe("");
      expect(fs.existsSync(path.join(buildWorktree, ".blackbox-fake-worker.log"))).toBe(true);

      await page.goto(boardLaneUrl(baseURL, "auto"));
      await expect(taskInColumn(page, "Done", harness.title, "Done")).toBeVisible({ timeout: 30_000 });
      await taskInColumn(page, "Done", harness.title, "Done").click();
      await expect(page.getByLabel("Task detail").locator(".board-annotation-list")
        .getByText(/shipping is deferred until review approval/i)).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-build-done.png`, fullPage: true });
    });

    await test.step("Review lands on the preserved build and waits for approval", async () => {
      const review = await waitForTaskStatus(request, "sdlc:review", harness.title, "done");
      expect(review.task.resultHandoffId).toBeTruthy();
      expect(fs.existsSync(buildWorktree)).toBe(true);

      await page.goto(boardLaneUrl(baseURL, "sdlc:review"));
      await expect(taskInColumn(page, "Done", harness.title, "Done")).toBeVisible({ timeout: 30_000 });
      await taskInColumn(page, "Done", harness.title, "Done").click();
      const detail = page.getByLabel("Task detail");
      await expect(detail.locator(".board-annotation-row--review .board-annotation-document"))
        .toContainText("## Review findings", { timeout: 30_000 });
      await expect(detail.getByText("Awaiting approval", { exact: true }).first()).toBeVisible();
      await expect(detail.getByLabel("Review approval")).toBeVisible();
      expect(hasSdlcMarker(await taskEvents(request, review.task.id), "shipped")).toBe(false);
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-review-awaiting-approval.png`, fullPage: true });
    });

    await test.step("Review approval ships through the existing gates and leaves the chain Done", async () => {
      const detail = page.getByLabel("Task detail");
      const approval = detail.getByLabel("Review approval");
      await approval.getByRole("button", { name: "Approve", exact: true }).click();
      await expect(approval.getByText(/Approved by nathan at/)).toBeVisible({ timeout: 15_000 });

      const review = await taskBySpec(request, "sdlc:review", specId);
      const shipEvent = await waitForEvent(request, review.task.id, (event) => (
        event.detail?.kind === "progress" && event.detail.dataJson?.sdlc === "shipped"
      ));
      expect(shipEvent.detail?.dataJson).toMatchObject({
        status: "local-only",
        reason: "no origin remote configured",
      });
      await expect(detail.locator(".board-annotation-list")
        .getByText(/Ship result: status=local-only, reason=no origin remote configured/))
        .toBeVisible({ timeout: 30_000 });

      const chain = await tasksForSpec(request, specId);
      expect(chain.map((snapshot) => snapshot.task.lane).sort()).toEqual([...STORY_LANES].sort());
      for (const snapshot of chain) {
        expect(snapshot.task.status, `${snapshot.task.lane} should be done`).toBe("done");
        expect(snapshot.task.resultHandoffId, `${snapshot.task.lane} should link a Handoff`).toBeTruthy();
      }
      expect(fs.existsSync(buildWorktree)).toBe(true);
      await expect(detail.locator(`.board-handoff#handoff-${review.task.resultHandoffId}`))
        .toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${SHOT_DIR}/sdlc-shipped-local-only.png`, fullPage: true });
    });
  } finally {
    await cleanupHarness(request, harness);
  }
});

test("rejecting an SDLC plan records feedback and enqueues nothing else", async ({
  page,
  request,
  baseURL,
}) => {
  test.setTimeout(240_000);
  if (!baseURL) throw new Error("Playwright baseURL is required for the SDLC E2E");

  const harness = createHarness("reject");
  let specId = "";

  try {
    const gate = await createSdlcStory(page, request, baseURL, harness);
    specId = gate.spec.id;
    harness.runner = startRunner(harness, baseURL);

    const plan = await waitForTaskStatus(request, "sdlc:plan", harness.title, "done");
    expect(plan.task.resultHandoffId).toBeTruthy();
    expect(await findTaskBySpec(request, "auto", specId)).toBeUndefined();

    await page.goto(boardLaneUrl(baseURL, "sdlc:plan"));
    await expect(taskInColumn(page, "Done", harness.title, "Done")).toBeVisible({ timeout: 30_000 });
    await taskInColumn(page, "Done", harness.title, "Done").click();
    const detail = page.getByLabel("Task detail");
    const approval = detail.getByLabel("Plan approval");
    const feedback = "Plan omits the rollback and verification details.";

    await approval.getByLabel("Rejection feedback").fill(feedback);
    await approval.getByRole("button", { name: "Reject", exact: true }).click();
    await expect(approval.getByText(/Rejected by nathan at/)).toBeVisible({ timeout: 15_000 });
    await expect(approval.getByText(feedback, { exact: true })).toBeVisible();

    const approvalEvent = await waitForEvent(request, plan.task.id, (event) => (
      event.detail?.kind === "approval"
      && event.detail.dataJson?.decision === "reject"
      && event.detail.dataJson?.stage === "plan"
    ));
    const rejection = await waitForEvent(request, plan.task.id, (event) => (
      event.detail?.kind === "progress"
      && event.detail.dataJson?.sdlc === "rejection_recorded"
    ));
    expect(rejection.detail?.dataJson).toMatchObject({
      approvalId: approvalEvent.id,
      decision: "reject",
      stage: "plan",
      feedback,
    });
    await expect(detail.locator(".board-annotation-list")
      .getByText(`SDLC plan rejected: ${feedback}`, { exact: true }))
      .toBeVisible({ timeout: 30_000 });

    await page.waitForTimeout(2_000);
    const chain = await tasksForSpec(request, specId);
    expect(chain.map((snapshot) => snapshot.task.lane).sort()).toEqual(["gate", "sdlc:plan"]);
    expect(chain.every((snapshot) => snapshot.task.status === "done")).toBe(true);
    expect(chain.every((snapshot) => Boolean(snapshot.task.resultHandoffId))).toBe(true);
    expect(await findTaskBySpec(request, "auto", specId)).toBeUndefined();
    expect(await findTaskBySpec(request, "sdlc:review", specId)).toBeUndefined();
    await page.screenshot({ path: `${SHOT_DIR}/sdlc-plan-rejected.png`, fullPage: true });
  } finally {
    await cleanupHarness(request, harness);
  }
});

function createHarness(scenario: string): StoryHarness {
  const runId = randomUUID();
  const title = `${PREFIX} ${scenario} ${runId.slice(0, 8)}`;
  const scratchDir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), `${PREFIX}-`)));
  execFileSync("git", ["init", "-b", "main"], { cwd: scratchDir });
  execFileSync("git", ["config", "user.email", "e2e@example.com"], { cwd: scratchDir });
  execFileSync("git", ["config", "user.name", "Black Box E2E"], { cwd: scratchDir });
  fs.writeFileSync(path.join(scratchDir, "README.md"), "# Black Box SDLC E2E\n", "utf8");
  execFileSync("git", ["add", "README.md"], { cwd: scratchDir });
  execFileSync("git", ["commit", "-m", "seed"], { cwd: scratchDir });

  const runnerConfigPath = path.join(os.tmpdir(), `${PREFIX}-runner-${runId}.json`);
  fs.writeFileSync(runnerConfigPath, `${JSON.stringify({
    concurrency: 1,
    engines: [{ id: "fake", model: "fake", effort: "n/a", enabled: true }],
    repos: [{ path: scratchDir, push: true, auto_merge: false, verify: "", danger: "" }],
  }, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
  return { title, scratchDir, runnerConfigPath };
}

async function createSdlcStory(
  page: Page,
  request: APIRequestContext,
  baseURL: string,
  harness: StoryHarness,
): Promise<TaskSnapshot> {
  await page.goto(`${baseURL}/board`);
  await expect(page.getByRole("heading", { name: "Coordination board" })).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "New story" }).click();
  await page.getByLabel("Title").fill(harness.title);
  await page.getByLabel("Repo path").fill(harness.scratchDir);
  await page.getByLabel("Goal").fill("Prove the gated SDLC runner flow through the real Board UI.");
  await page.getByLabel("Acceptance criteria")
    .fill("Plan, build, review, and local-only ship all complete with linked Handoffs.");
  await page.getByLabel("Verify command").fill("true");
  await page.getByRole("radio", { name: "SDLC", exact: true }).check();
  await page.getByRole("button", { name: "Create story" }).click();
  await expect(page.getByRole("heading", { name: "New story" })).toBeHidden({ timeout: 15_000 });
  return await waitForTask(request, "gate", harness.title);
}

function startRunner(harness: StoryHarness, baseURL: string): ChildProcess {
  const child = spawn("java", ["-jar", "target/sba-agentic-0.1.0.jar", "runner"], {
    cwd: REPO_ROOT,
    env: privateTmuxEnv({
      SBA_RUNNER_CONFIG: harness.runnerConfigPath,
      SBA_BASE_URL: baseURL,
      SBA_DATASOURCE_URL: `jdbc:sqlite:${path.join(harness.scratchDir, "runner-e2e.db")}`,
    }),
  });
  pipeRunnerOutput(child);
  return child;
}

function boardLaneUrl(baseURL: string, lane: string): string {
  return `${baseURL}/board?lane=${encodeURIComponent(lane)}`;
}

function taskInColumn(page: Page, column: string, title: string, status: string) {
  return page.locator(`section[aria-label="${column} tasks"]`).getByRole("button", {
    name: `${title}, ${status}`,
    exact: true,
  });
}

function taskWithAnyStatus(page: Page, title: string) {
  return page.getByRole("button", {
    name: new RegExp(`^${escapeRegExp(title)}, (Open|In progress|Blocked|Done)$`),
  });
}

async function waitForTask(
  request: APIRequestContext,
  lane: string,
  title: string,
): Promise<TaskSnapshot> {
  let found: TaskSnapshot | undefined;
  await expect.poll(async () => {
    found = await findTaskByTitle(request, lane, title);
    return found?.task.id || "";
  }, { timeout: 90_000 }).not.toBe("");
  return found!;
}

async function waitForTaskStatus(
  request: APIRequestContext,
  lane: string,
  title: string,
  status: string,
): Promise<TaskSnapshot> {
  let found: TaskSnapshot | undefined;
  await expect.poll(async () => {
    found = await findTaskByTitle(request, lane, title);
    return found?.task.status || "missing";
  }, { timeout: 90_000 }).toBe(status);
  return found!;
}

async function taskBySpec(
  request: APIRequestContext,
  lane: string,
  specId: string,
): Promise<TaskSnapshot> {
  const task = await findTaskBySpec(request, lane, specId);
  if (!task) throw new Error(`No ${lane} task found for spec ${specId}`);
  return task;
}

async function findTaskByTitle(
  request: APIRequestContext,
  lane: string,
  title: string,
): Promise<TaskSnapshot | undefined> {
  const tasks = await getJson<TaskSnapshot[]>(request, `/api/tasks?lane=${encodeURIComponent(lane)}&limit=250`);
  return tasks.find((snapshot) => snapshot.task.title === title);
}

async function findTaskBySpec(
  request: APIRequestContext,
  lane: string,
  specId: string,
): Promise<TaskSnapshot | undefined> {
  const tasks = await getJson<TaskSnapshot[]>(request, `/api/tasks?lane=${encodeURIComponent(lane)}&limit=250`);
  return tasks.find((snapshot) => snapshot.task.specId === specId);
}

async function tasksForSpec(request: APIRequestContext, specId: string): Promise<TaskSnapshot[]> {
  const tasks = await getJson<TaskSnapshot[]>(request, "/api/tasks?limit=250");
  return tasks.filter((snapshot) => snapshot.task.specId === specId);
}

async function taskEvents(request: APIRequestContext, taskId: string): Promise<TaskEvent[]> {
  return await getJson<TaskEvent[]>(request, `/api/tasks/${encodeURIComponent(taskId)}/events`);
}

async function waitForEvent(
  request: APIRequestContext,
  taskId: string,
  predicate: (event: TaskEvent) => boolean,
): Promise<TaskEvent> {
  let found: TaskEvent | undefined;
  await expect.poll(async () => {
    found = (await taskEvents(request, taskId)).find(predicate);
    return found?.id || "";
  }, { timeout: 90_000 }).not.toBe("");
  return found!;
}

function hasSdlcMarker(events: TaskEvent[], marker: string): boolean {
  return events.some((event) => event.detail?.dataJson?.sdlc === marker);
}

async function getJson<T>(request: APIRequestContext, endpoint: string): Promise<T> {
  const response = await request.get(endpoint);
  if (!response.ok()) {
    expect(response.ok(), `GET ${endpoint}: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
  return await response.json() as T;
}

async function cleanupHarness(request: APIRequestContext, harness: StoryHarness): Promise<void> {
  await cleanupStep("runner daemon", async () => {
    if (harness.runner) await terminateRunner(harness.runner);
  });

  const taskIds = new Set<string>();
  for (const lane of STORY_LANES) {
    await cleanupStep(`${lane} task lookup`, async () => {
      const task = await findTaskByTitle(request, lane, harness.title);
      if (task) taskIds.add(task.task.id);
    });
  }
  for (const taskId of taskIds) {
    await cleanupStep(`tmux session ${taskId}`, () => {
      const sessionName = `bb-run-${taskId.slice(0, 8)}`;
      try {
        execFileSync("tmux", ["has-session", "-t", sessionName], { stdio: "ignore", env: privateTmuxEnv() });
      } catch {
        return;
      }
      execFileSync("tmux", ["kill-session", "-t", sessionName], { stdio: "ignore", env: privateTmuxEnv() });
    });
    await cleanupStep(`synthetic rollout ${taskId}`, () => {
      fs.rmSync(path.join(os.homedir(), ".codex", "sessions", "blackbox-e2e", `${taskId}.jsonl`), {
        force: true,
      });
    });
  }
  await cleanupStep("scratch repository", () => {
    fs.rmSync(harness.scratchDir, { recursive: true, force: true });
  });
  await cleanupStep("runner config", () => {
    fs.rmSync(harness.runnerConfigPath, { force: true });
  });
}

function pipeRunnerOutput(child: ChildProcess) {
  child.stdout?.on("data", (chunk) => console.log(`[sdlc-runner] ${String(chunk).trimEnd()}`));
  child.stderr?.on("data", (chunk) => console.error(`[sdlc-runner] ${String(chunk).trimEnd()}`));
  child.on("error", (error) => console.error(`[sdlc-runner] ${error.message}`));
}

async function terminateRunner(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  await waitForExit(child, 3_000);
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGKILL");
  await waitForExit(child, 2_000);
}

async function waitForExit(child: ChildProcess, timeout: number): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  await new Promise<void>((resolve) => {
    const timer = setTimeout(done, timeout);
    child.once("exit", done);

    function done() {
      clearTimeout(timer);
      child.off("exit", done);
      resolve();
    }
  });
}

async function cleanupStep(label: string, cleanup: () => void | Promise<void>): Promise<void> {
  try {
    await cleanup();
  } catch (error) {
    console.warn(`[sdlc-e2e] cleanup failed for ${label}: ${errorMessage(error)}`);
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
