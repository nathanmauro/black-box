import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const SHOT_DIR = "test-results/shots";
const PREFIX = "black-box-full-auto-e2e";
const REPO_ROOT = path.resolve(import.meta.dirname, "..", "..", "..");

type TaskRecord = {
  id: string;
  title: string;
  lane: string;
  status: string;
  resultHandoffId?: string | null;
};
type TaskSnapshot = { task: TaskRecord };

test("full-auto runner promotes, executes, links, and hands off a real story", async ({ page, request, baseURL }) => {
  test.setTimeout(240_000);
  if (!baseURL) throw new Error("Playwright baseURL is required for the full-auto E2E");

  const runId = randomUUID();
  const title = `${PREFIX} prove the loop ${runId.slice(0, 8)}`;
  let scratchDir = "";
  let scratchRepoPath = "";
  let runnerConfigPath = "";
  let runnerDaemon: ChildProcess | undefined;
  let autoTaskId = "";
  let rolloutFilePath = "";

  try {
    scratchDir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "black-box-full-auto-e2e-")));
    scratchRepoPath = scratchDir;
    execFileSync("git", ["init", "-b", "main"], { cwd: scratchRepoPath });
    execFileSync("git", ["config", "user.email", "e2e@example.com"], { cwd: scratchRepoPath });
    execFileSync("git", ["config", "user.name", "Black Box E2E"], { cwd: scratchRepoPath });
    fs.writeFileSync(path.join(scratchRepoPath, "README.md"), "# Black Box full-auto E2E\n", "utf8");
    execFileSync("git", ["add", "README.md"], { cwd: scratchRepoPath });
    execFileSync("git", ["commit", "-m", "seed"], { cwd: scratchRepoPath });

    runnerConfigPath = path.join(os.tmpdir(), `${PREFIX}-runner-${runId}.json`);
    fs.writeFileSync(runnerConfigPath, `${JSON.stringify({
      concurrency: 1,
      engines: [
        { id: "fake", model: "fake", effort: "n/a", enabled: true },
      ],
      repos: [
        { path: scratchRepoPath, push: true, auto_merge: false, verify: "", danger: "" },
      ],
    }, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });

    await page.goto(`${baseURL}/board`);
    await expect(page.getByRole("heading", { name: "Coordination board" })).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "New story" }).click();
    await page.getByLabel("Title").fill(title);
    await page.getByLabel("Repo path").fill(scratchRepoPath);
    await page.getByLabel("Goal").fill("Prove the deterministic full-auto runner loop through the real UI.");
    await page.getByLabel("Acceptance criteria").fill("The fake worker completes and records a linked Handoff.");
    await page.getByLabel("Verify command").fill("true");
    await page.getByRole("button", { name: "Create story" }).click();
    await expect(page.getByRole("heading", { name: "New story" })).toBeHidden({ timeout: 15_000 });

    await page.goto(`${baseURL}/board?lane=gate`);
    await test.step("Open: the gate-lane story starts on the Board", async () => {
      await expect(taskInColumn(page, "Open", title, "Open")).toBeVisible({ timeout: 15_000 });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-open.png`, fullPage: true });
    });

    // The runner boots the full Spring context, whose datasource defaults to
    // jdbc:sqlite:sba-agentic.db relative to cwd — without an override it would
    // open the real production database at the repo root.
    const runnerDbPath = path.join(scratchDir, "runner-e2e.db");
    runnerDaemon = spawn("java", ["-jar", "target/sba-agentic-0.1.0.jar", "runner"], {
      cwd: REPO_ROOT,
      env: {
        ...process.env,
        SBA_RUNNER_CONFIG: runnerConfigPath,
        SBA_BASE_URL: baseURL,
        SBA_DATASOURCE_URL: `jdbc:sqlite:${runnerDbPath}`,
      },
    });
    pipeRunnerOutput(runnerDaemon);

    await test.step("Gate promotes: the gate card completes with its live annotation", async () => {
      const gateDone = taskInColumn(page, "Done", title, "Done");
      await expect(gateDone).toBeVisible({ timeout: 30_000 });
      await gateDone.click();
      const detail = page.getByLabel("Task detail");
      await expect(detail.locator(".board-annotation-list").getByText(/^Gate passed/)).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-gate-promotes.png`, fullPage: true });
    });

    await test.step("Auto card reaches In Progress live", async () => {
      await page.goto(`${baseURL}/board?lane=auto`);
      const inProgress = taskInColumn(page, "In Progress", title, "In progress");
      await expect(inProgress).toBeVisible({ timeout: 60_000 });
      await inProgress.click();
      await expect(page.getByRole("complementary", { name: "Task detail" })).toBeVisible({ timeout: 15_000 });

      const autoTask = await taskByTitle(request, "auto", title);
      autoTaskId = autoTask.task.id;
      rolloutFilePath = path.join(
        os.homedir(),
        ".codex",
        "sessions",
        "blackbox-e2e",
        `${autoTaskId}.jsonl`,
      );
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-in-progress.png`, fullPage: true });
    });

    await test.step("Annotations stream live from worktree creation through engine launch", async () => {
      const annotations = page.getByLabel("Task detail").locator(".board-annotation-list");
      await expect(annotations.getByText(/Worktree created/)).toBeVisible({ timeout: 60_000 });
      await expect(annotations.getByText(/Engine 'fake' launched/)).toBeVisible({ timeout: 60_000 });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-annotations.png`, fullPage: true });
    });

    await test.step("Tendril opens the ingested worker session", async () => {
      const tendril = page.getByLabel("Task detail").getByRole("link", { name: "Open worker session →" });
      await expect(tendril).toBeVisible({ timeout: 90_000 });
      await tendril.click();
      await expect(page.getByRole("heading", { name: "Codex worker session", exact: true })).toBeVisible({
        timeout: 30_000,
      });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-tendril.png`, fullPage: true });
    });

    await test.step("DAG renders the task and linked worker session", async () => {
      await page.goto(`${baseURL}/board?lane=auto`);
      const autoCard = page.getByRole("button", {
        name: new RegExp(`^${escapeRegExp(title)}, (In progress|Done)$`),
      });
      await expect(autoCard).toBeVisible({ timeout: 30_000 });
      await autoCard.click();
      const detail = page.getByLabel("Task detail");
      const dagToggle = detail.getByRole("button", { name: "View agent DAG" });
      await expect(dagToggle).toBeVisible({ timeout: 15_000 });
      await dagToggle.click();
      const dag = detail.locator('svg[aria-label="Task DAG"]');
      await expect(dag).toBeVisible({ timeout: 30_000 });
      await expect(dag.locator('[data-node-type="task"]').first()).toBeAttached({ timeout: 30_000 });
      await expect(dag.locator('[data-node-type="session"]').first()).toBeAttached({ timeout: 30_000 });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-dag.png`, fullPage: true });
    });

    await test.step("Card lands Done with its linked Handoff", async () => {
      await expect(taskInColumn(page, "Done", title, "Done")).toBeVisible({ timeout: 90_000 });
      const completedTask = await taskByTitle(request, "auto", title);
      const handoffId = completedTask.task.resultHandoffId || "";
      expect(handoffId).not.toBe("");
      await expect(page.locator(`.board-handoff#handoff-${handoffId}`)).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `${SHOT_DIR}/full-auto-done-handoff.png`, fullPage: true });
    });
  } finally {
    await cleanupStep("runner daemon", async () => {
      if (runnerDaemon) await terminateRunner(runnerDaemon);
    });
    await cleanupStep("auto task lookup", async () => {
      if (autoTaskId) return;
      const autoTask = await findTaskByTitle(request, "auto", title);
      if (!autoTask) return;
      autoTaskId = autoTask.task.id;
      rolloutFilePath = path.join(
        os.homedir(),
        ".codex",
        "sessions",
        "blackbox-e2e",
        `${autoTaskId}.jsonl`,
      );
    });
    await cleanupStep("runner tmux session", () => {
      if (!autoTaskId) return;
      execFileSync("tmux", ["kill-session", "-t", `bb-run-${autoTaskId.slice(0, 8)}`], {
        stdio: "ignore",
      });
    });
    await cleanupStep("scratch repository", () => {
      if (scratchDir) fs.rmSync(scratchDir, { recursive: true, force: true });
    });
    await cleanupStep("runner config", () => {
      if (runnerConfigPath) fs.rmSync(runnerConfigPath, { force: true });
    });
    await cleanupStep("synthetic Codex rollout", () => {
      if (rolloutFilePath) fs.rmSync(rolloutFilePath, { force: true });
    });
    await cleanupStep("synthetic Codex rollout directory", () => {
      if (rolloutFilePath) fs.rmdirSync(path.dirname(rolloutFilePath));
    });
  }
});

function taskInColumn(page: Page, column: string, title: string, status: string) {
  return page.locator(`section[aria-label="${column} tasks"]`).getByRole("button", {
    name: `${title}, ${status}`,
    exact: true,
  });
}

async function taskByTitle(
  request: APIRequestContext,
  lane: string,
  title: string,
): Promise<TaskSnapshot> {
  const task = await findTaskByTitle(request, lane, title);
  if (!task) throw new Error(`No ${lane} task found for ${title}`);
  return task;
}

async function findTaskByTitle(
  request: APIRequestContext,
  lane: string,
  title: string,
): Promise<TaskSnapshot | undefined> {
  const tasks = await getJson<TaskSnapshot[]>(request, `/api/tasks?lane=${encodeURIComponent(lane)}&limit=100`);
  return tasks.find((snapshot) => snapshot.task.title === title);
}

async function getJson<T>(request: APIRequestContext, path: string): Promise<T> {
  const response = await request.get(path);
  if (!response.ok()) {
    expect(response.ok(), `GET ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  }
  return await response.json() as T;
}

function pipeRunnerOutput(child: ChildProcess) {
  child.stdout?.on("data", (chunk) => console.log(`[runner-daemon] ${String(chunk).trimEnd()}`));
  child.stderr?.on("data", (chunk) => console.error(`[runner-daemon] ${String(chunk).trimEnd()}`));
  child.on("error", (error) => console.error(`[runner-daemon] ${error.message}`));
}

async function terminateRunner(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  await waitForExit(child, 2_000);
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
    console.warn(`[full-auto-e2e] cleanup failed for ${label}: ${errorMessage(error)}`);
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
