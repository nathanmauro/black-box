import { test, expect, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, realpathSync } from "node:fs";
import path from "node:path";
import { assertSafeSeedBaseUrl, E2E_PROJECT_CWD } from "../../src/e2e/seedData";
import { E2E_INJECTION_FILE } from "./project-fixture";
import type { IngestResponse } from "../../src/lib/api";

const SHOT_DIR = "test-results/shots";

async function captureStreamFixture(request: APIRequestContext) {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const repo = `/tmp/stream-source-${id}`;
  const identity = { source: "codex", clientSessionId: `stream-source-${id}` };
  const decision = `Choose local storage for stream source ${id}`;
  const rationale = `Keep canonical recording independent of optional indexes (${id}).`;
  const observation = `Verified stream source ${id}.`;
  const prompt = `Inspect stream source ${id}`;
  expect(
    (
      await request.post("/api/events", {
        data: { ...identity, cwd: repo, eventType: "UserPromptSubmit", role: "user", text: prompt },
      })
    ).ok(),
  ).toBeTruthy();
  const response = await request.post("/api/decisions", {
    data: { ...identity, repo, decision, rationale },
  });
  expect(response.ok()).toBeTruthy();
  const captured = (await response.json()) as IngestResponse;
  expect(
    (
      await request.post("/api/events", {
        data: {
          ...identity,
          cwd: repo,
          eventType: "Observation",
          role: "assistant",
          text: observation,
        },
      })
    ).ok(),
  ).toBeTruthy();
  return { repo, decision, rationale, observation, prompt, captured };
}

test("stream is the default landing view and shows meaningful events newest-first", async ({
  page,
  request,
}) => {
  // The shared seed can move beyond page one as other journeys append records. Keep the default
  // unfiltered landing path, but own fresh records whose presence and ordering this test proves.
  const fixture = await captureStreamFixture(request);
  await page.goto("/");

  const modes = page.getByRole("tablist", { name: "Activity mode" });
  await expect(modes.getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");

  // Decision and observation are meaningful; the later observation renders first.
  const rows = page.locator(".stream-row");
  await expect(rows.first()).toBeVisible();
  const ownedRows = rows.filter({
    hasText: new RegExp(`${fixture.decision}|${fixture.observation}`),
  });
  await expect(ownedRows).toHaveCount(2);
  await expect(ownedRows.nth(0)).toBeVisible();
  await expect(ownedRows.nth(1)).toBeVisible();
  await expect(ownedRows.nth(0)).toContainText(fixture.observation);
  await expect(ownedRows.nth(1)).toContainText(fixture.decision);

  // The user prompt is not meaningful, so the default stream hides that event row.
  await expect(rows.filter({ hasText: fixture.prompt })).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/stream.png`, fullPage: true });
});

test("meaningful toggle widens the stream and a source facet narrows it", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const repo = `/tmp/stream-visibility-${id}`;
  const scope = `project_exact:${repo}`;
  const decision = `Choose local storage for scoped filter fixture ${id}`;
  const prompt = `Show the human prompt for scoped filter fixture ${id}`;
  // Other journeys append hundreds of events. Own this slice instead of assuming a global seed
  // is still within the first page when meaningful-only is turned off.
  const capturedDecision = await request.post("/api/decisions", {
    data: {
      source: "codex",
      clientSessionId: `stream-decision-${id}`,
      repo,
      decision,
      rationale: "Keep the fixture's source and meaningful filters independently visible.",
    },
  });
  expect(capturedDecision.ok()).toBeTruthy();
  const capturedPrompt = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: `stream-prompt-${id}`,
      eventType: "UserPromptSubmit",
      role: "user",
      text: prompt,
      cwd: repo,
    },
  });
  expect(capturedPrompt.ok()).toBeTruthy();
  await page.goto(`/?q=${encodeURIComponent(scope)}`);
  await expect(page.locator(".stream-row")).toHaveCount(1);
  await expect(page.getByText(decision).first()).toBeVisible();
  await expect(page.getByText(prompt)).toHaveCount(0);

  await page.getByRole("button", { name: "Options", exact: true }).click();
  await page.getByLabel(/meaningful events only/i).uncheck();
  await expect(page.locator(".stream-row")).toHaveCount(2);
  await expect(page.getByText(prompt).first()).toBeVisible();

  // Preserve the owned scope while narrowing to the other source.
  const query = page.getByLabel("Stream query");
  const narrowed = `${scope} source:claude is:all`;
  await query.fill(narrowed);
  await page.getByRole("button", { name: "Filter", exact: true }).click();
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === narrowed);
  await expect(page.locator(".stream-row")).toHaveCount(1);
  await expect(page.getByText(prompt).first()).toBeVisible();
  await expect(page.getByText(decision)).toHaveCount(0);

  await page.screenshot({ path: `${SHOT_DIR}/stream-filtered.png`, fullPage: true });
});

test("clicking a stream row expands it inline with the full event card", async ({
  page,
  request,
}) => {
  const fixture = await captureStreamFixture(request);
  await page.goto(`/?q=${encodeURIComponent(`project_exact:${fixture.repo}`)}`);
  const decisionRow = page.locator(".stream-row").filter({ hasText: fixture.decision });
  await expect(decisionRow).toBeVisible();
  await expect(decisionRow).toHaveAttribute("type", "button");

  await decisionRow.click();
  await expect(decisionRow).toHaveAttribute("aria-expanded", "true");
  const expanded = page.locator(".stream-row-expanded");
  await expect(expanded).toBeVisible();
  const sourceLink = expanded.getByRole("link", { name: "Open at this event" });
  await expect(sourceLink).toHaveAttribute("href", /view=browse/);
  const source = new URL((await sourceLink.getAttribute("href"))!, page.url());
  expect(source.searchParams.get("session")).toBe(fixture.captured.sessionId);
  expect(source.searchParams.get("event")).toBe(fixture.captured.eventId);
  await expect(expanded.getByText(fixture.rationale)).toBeVisible();

  await decisionRow.click();
  await expect(page.locator(".stream-row-expanded")).toHaveCount(0);
});

test("the explicit Stream action opens the exact event in Browse", async ({ page, request }) => {
  const fixture = await captureStreamFixture(request);
  await page.goto(`/?q=${encodeURIComponent(`project_exact:${fixture.repo}`)}`);
  const decisionRow = page.locator(".stream-row").filter({ hasText: fixture.decision });
  await decisionRow.click();
  await page
    .locator(".stream-row-expanded")
    .getByRole("link", { name: "Open at this event" })
    .click();

  await expect(page).toHaveURL(/view=browse/);
  await expect(page).toHaveURL(
    (url) =>
      url.searchParams.get("session") === fixture.captured.sessionId &&
      url.searchParams.get("event") === fixture.captured.eventId,
  );
  await expect(page.getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
  const target = page.locator(`#event-${fixture.captured.eventId}`);
  await expect(target).toBeVisible();
  await expect(target).toHaveClass(/event-flow-row--target/);
  await expect(target).toContainText(fixture.decision);
});

test("a newly ingested event flows into the stream live", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.locator(".live-pill--live")).toBeVisible({ timeout: 10_000 });

  const marker = "STREAM-LIVE-CHECK-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Observation",
      role: "assistant",
      text: marker,
      cwd: "/tmp/black-box-e2e",
      metadata: { title: marker },
    },
  });
  expect(res.ok()).toBeTruthy();

  // At top of feed the row prepends directly; otherwise the "N new" pill surfaces it.
  const row = page.getByText(marker).first();
  const pill = page.locator(".stream-new-pill");
  await expect(row.or(pill)).toBeVisible({ timeout: 10_000 });
  if (await pill.isVisible().catch(() => false)) {
    await pill.click();
  }
  await expect(page.getByText(marker).first()).toBeVisible();
});

test("browse mode still reaches the session rail and reader", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "Browse" }).click();
  await expect(page).toHaveURL(/view=browse/);
  await expect(page.getByLabel("Find sessions")).toBeVisible();
  await expect(page.getByText("UI rewrite kickoff").first()).toBeVisible();
});

test("density toggle expands every row and survives a reload", async ({ page }) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator(".stream-row").first()).toBeVisible();

  await page.getByRole("button", { name: "Options", exact: true }).click();
  await page
    .getByRole("group", { name: "Stream density" })
    .getByRole("button", { name: "Expanded" })
    .click();
  const rows = page.locator(".stream-row");
  const rowCount = await rows.count();
  for (let index = 0; index < rowCount; index += 1) {
    await expect(rows.nth(index)).toHaveAttribute("aria-expanded", "true");
  }

  await page.reload({ waitUntil: "domcontentloaded" });
  await expect(page.locator(".stream-row").first()).toHaveAttribute("aria-expanded", "true");
});

test("an edit event renders a readable diff behind a lazy details block", async ({
  page,
  request,
}) => {
  const seeded = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: "black-box-e2e-edit-diff",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "Edit",
      toolInput: {
        file_path: "/tmp/black-box-e2e/app.ts",
        old_string: "const total = 1;\nconst kept = 2;",
        new_string: "const total = 9;\nconst kept = 2;",
      },
      cwd: "/tmp/black-box-e2e",
      metadata: { title: "Edit diff seed" },
    },
  });
  expect(seeded.ok()).toBeTruthy();

  await page.goto("/", { waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "Options", exact: true }).click();
  await page.getByLabel(/meaningful events only/i).uncheck(); // PostToolUse is filtered by default
  const row = page.locator(".stream-row").filter({ hasText: "app.ts" }).first();
  await expect(row).toBeVisible();
  await row.click();

  await page.locator(".detail-block--diff > summary").first().click();
  await expect(page.locator(".diff-line--del").first()).toContainText("const total = 1;");
  await expect(page.locator(".diff-line--add").first()).toContainText("const total = 9;");
});

test("a file link opens through the verified project catalog without shell or desktop leakage", async ({
  page,
  request,
}) => {
  const filePath = `${E2E_PROJECT_CWD}/app.ts`;
  const seeded = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: "black-box-e2e-file-link",
      eventType: "PreToolUse",
      role: "assistant",
      toolName: "Read",
      toolInput: { file_path: filePath, offset: 2, limit: 1 },
      cwd: E2E_PROJECT_CWD,
      metadata: { title: "File link seed" },
    },
  });
  expect(seeded.ok()).toBeTruthy();

  await page.goto("/?q=tool%3ARead&meaningful=false", { waitUntil: "domcontentloaded" });
  const row = page.locator(".stream-row").filter({ hasText: "app.ts" }).first();
  await expect(row).toBeVisible();
  await row.click();
  await page.getByRole("button", { name: `Open ${filePath} in editor` }).click();
  await expect(page.getByText("Opened in editor.").first()).toBeVisible();

  const editorLog = path.join(requiredE2eTempDir(), "editor-argv.bin");
  await expect
    .poll(() => editorCalls(editorLog))
    .toEqual([[realpathSync(E2E_PROJECT_CWD)], ["-g", `${realpathSync(filePath)}:2`]]);

  const injectionPath = `${E2E_PROJECT_CWD}/${E2E_INJECTION_FILE}`;
  const injectionSeed = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: "black-box-e2e-injection-file-link",
      eventType: "PreToolUse",
      role: "assistant",
      toolName: "Read",
      toolInput: { file_path: injectionPath, offset: 1, limit: 1 },
      cwd: E2E_PROJECT_CWD,
      metadata: { title: "Injection-shaped file link seed" },
    },
  });
  expect(injectionSeed.ok()).toBeTruthy();
  await page.reload({ waitUntil: "domcontentloaded" });
  const injectionRow = page.locator(".stream-row").filter({ hasText: E2E_INJECTION_FILE }).first();
  await expect(injectionRow).toBeVisible();
  await injectionRow.click();
  await page.getByRole("button", { name: `Open ${injectionPath} in editor` }).click();
  await expect(page.getByText("Opened in editor.").first()).toBeVisible();
  await expect
    .poll(() => editorCalls(editorLog))
    .toEqual([
      [realpathSync(E2E_PROJECT_CWD)],
      ["-g", `${realpathSync(filePath)}:2`],
      [realpathSync(E2E_PROJECT_CWD)],
      ["-g", `${realpathSync(injectionPath)}:1`],
    ]);
  expect(existsSync(path.join(requiredE2eTempDir(), "injection-sentinel"))).toBe(false);

  const scopesResponse = await request.get("/api/projects/code-scopes");
  expect(scopesResponse.ok()).toBeTruthy();
  const scopes = (await scopesResponse.json()) as Array<{ projectKey: string; root: string }>;
  const scope = scopes.find((candidate) => candidate.root === E2E_PROJECT_CWD);
  expect(scope).toBeTruthy();
  const callsBeforeBlockedRequest = editorCalls(editorLog);
  const blocked = await request.post("/api/open-in-editor", {
    data: { projectKey: scope!.projectKey, relativePath: "../outside.txt" },
  });
  expect(blocked.status()).toBe(403);
  expect(await blocked.json()).toMatchObject({
    error: { status: 403, type: "outside_project_root" },
  });
  expect(editorCalls(editorLog)).toEqual(callsBeforeBlockedRequest);

  const outsidePath = "/etc/passwd";
  const outsideSeed = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: "black-box-e2e-outside-file-link",
      eventType: "PreToolUse",
      role: "assistant",
      toolName: "Read",
      toolInput: { file_path: outsidePath, offset: 1, limit: 1 },
      cwd: E2E_PROJECT_CWD,
      metadata: { title: "Outside file link seed" },
    },
  });
  expect(outsideSeed.ok()).toBeTruthy();
  await page.reload({ waitUntil: "domcontentloaded" });
  const outsideRow = page.locator(".stream-row").filter({ hasText: outsidePath }).first();
  await expect(outsideRow).toBeVisible();
  await outsideRow.click();
  await expect(page.getByRole("button", { name: `Open ${outsidePath} in editor` })).toHaveCount(0);
  await expect(
    page.getByText("This path is outside the eligible project roots.").first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: `Reveal in Finder ${outsidePath}` }),
  ).toHaveAttribute("aria-disabled", "true");
  await page.getByRole("button", { name: `Copy path ${outsidePath}` }).click();
  await expect(page.getByText("Could not copy path.").first()).toBeVisible();
  expect(editorCalls(editorLog)).toEqual(callsBeforeBlockedRequest);

  const missingSeed = await request.post("/api/events", {
    data: {
      source: "codex",
      clientSessionId: "black-box-e2e-missing-file-link",
      eventType: "PreToolUse",
      role: "assistant",
      toolName: "Read",
      toolInput: { file_path: `${E2E_PROJECT_CWD}/missing.ts`, offset: 1, limit: 1 },
      cwd: E2E_PROJECT_CWD,
      metadata: { title: "Missing file link seed" },
    },
  });
  expect(missingSeed.ok()).toBeTruthy();
  await page.reload({ waitUntil: "domcontentloaded" });
  const missingRow = page.locator(".stream-row").filter({ hasText: "missing.ts" }).first();
  await expect(missingRow).toBeVisible();
  await missingRow.click();
  await page.getByRole("button", { name: `Open ${E2E_PROJECT_CWD}/missing.ts in editor` }).click();
  await expect(page.getByText("File no longer exists.").first()).toBeVisible();
  expect(editorCalls(editorLog)).toEqual(callsBeforeBlockedRequest);
});

function requiredE2eTempDir(): string {
  const tempDir = process.env.SBA_E2E_TEMP_DIR;
  if (!tempDir) throw new Error("SBA_E2E_TEMP_DIR is required for editor verification");
  return tempDir;
}

function editorCalls(logPath: string): string[][] {
  if (!existsSync(logPath)) return [];
  return readFileSync(logPath)
    .toString("utf8")
    .split("\0\0")
    .map((record) => record.split("\0").filter(Boolean))
    .filter((record) => record[0] === "call")
    .map((record) => record.slice(1));
}
