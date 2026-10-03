import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { AgentSession, IngestResponse, ProjectSummary, RecallResult } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

test("All projects replacements retain each target project's sessions, counts, and history", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const alpha = `/tmp/replacement-alpha-${id}`;
  const beta = `/tmp/replacement-beta-${id}`;
  const targets = [
    { repo: alpha, title: `${id} Alpha first decision`, clientSessionId: `alpha-original-${id}` },
    { repo: beta, title: `${id} Beta decision`, clientSessionId: `beta-original-${id}` },
    { repo: alpha, title: `${id} Alpha second decision`, clientSessionId: `alpha-original-${id}` },
  ];
  const originals: IngestResponse[] = [];
  for (const target of targets) {
    const response = await request.post("/api/decisions", {
      data: {
        source: "codex",
        clientSessionId: target.clientSessionId,
        repo: target.repo,
        decision: target.title,
        rationale: "Original project evidence",
      },
    });
    expect(response.ok()).toBeTruthy();
    originals.push((await response.json()) as IngestResponse);
  }

  // Keep one mounted Recall page throughout alpha -> beta -> alpha writes.
  await page.goto(`/recall?query=${id}&run=1`);
  await expect(page.getByRole("button", { name: /^Project / })).toContainText("All projects");
  const replacements: IngestResponse[] = [];
  for (const target of targets) {
    const original = page.getByRole("article", { name: target.title, exact: true });
    await original.getByRole("button", { name: "Replace decision" }).click();
    await page.getByLabel("New decision", { exact: true }).fill(`${target.title} updated`);
    await page.getByLabel("Why this replaces the earlier decision").fill("New project evidence");
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        new URL(response.url()).pathname === "/api/decisions",
    );
    await page.getByRole("button", { name: "Record replacement" }).click();
    const response = await saved;
    expect(response.ok()).toBeTruthy();
    replacements.push((await response.json()) as IngestResponse);
    await expect(
      page.getByRole("article", { name: `${target.title} updated`, exact: true }),
    ).toBeVisible();
    await expect(original).toHaveCount(0);
  }
  expect(replacements[0].sessionId).toBe(replacements[2].sessionId);
  expect(replacements[0].sessionId).not.toBe(replacements[1].sessionId);
  expect(replacements[0].clientSessionId).toBe(replacements[2].clientSessionId);
  expect(replacements[0].clientSessionId).not.toBe(replacements[1].clientSessionId);

  const projectsResponse = await request.get("/api/projects");
  expect(projectsResponse.ok()).toBeTruthy();
  const projects = (await projectsResponse.json()) as ProjectSummary[];
  const alphaProject = projects.find((project) => project.canonicalKey === alpha)!;
  const betaProject = projects.find((project) => project.canonicalKey === beta)!;
  expect(alphaProject).toMatchObject({ sessionCount: 2, eventCount: 4 });
  expect(betaProject).toMatchObject({ sessionCount: 2, eventCount: 2 });
  for (const [project, replacement, count, other] of [
    [alphaProject, replacements[0], 2, replacements[1]],
    [betaProject, replacements[1], 1, replacements[0]],
  ] as const) {
    const response = await request.get(
      `/api/projects/${encodeURIComponent(project.projectKey)}/sessions`,
    );
    expect(response.ok()).toBeTruthy();
    const sessions = (await response.json()) as AgentSession[];
    expect(sessions.find((session) => session.id === replacement.sessionId)).toMatchObject({
      cwd: project.canonicalKey,
      eventCount: count,
    });
    expect(sessions.some((session) => session.id === other.sessionId)).toBe(false);
  }
  for (const [index, target] of targets.entries()) {
    const params = new URLSearchParams({
      project: target.repo,
      query: id,
      kinds: "decision",
      includeSuperseded: "true",
      withinHours: "168",
    });
    const response = await request.get(`/api/recall?${params}`);
    expect(response.ok()).toBeTruthy();
    const history = (await response.json()) as RecallResult;
    expect(history.items.find((item) => item.eventId === originals[index].eventId)).toMatchObject({
      supersededByEventId: replacements[index].eventId,
      repo: target.repo,
    });
    expect(
      history.items.find((item) => item.eventId === replacements[index].eventId),
    ).toMatchObject({
      supersedesEventId: originals[index].eventId,
      repo: target.repo,
    });
  }

  await page.goto(`/projects/${encodeURIComponent(alphaProject.projectKey)}`);
  await page.getByRole("link", { name: "Resume this project" }).click();
  for (const target of [targets[0], targets[2]])
    await expect(
      page.getByRole("article", { name: `${target.title} updated`, exact: true }),
    ).toBeVisible();
  await expect(
    page.getByRole("article", { name: `${targets[1].title} updated`, exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Include replaced decisions").check();
  await page.getByRole("button", { name: "Run recall" }).click();
  const oldAlpha = page.getByRole("article", { name: targets[0].title, exact: true });
  await expect(oldAlpha).toBeVisible();
  await expect(oldAlpha.getByRole("button", { name: "Replace decision" })).toHaveCount(0);
  await oldAlpha.getByRole("link", { name: "replacement decision" }).click();
  await expect(page).toHaveURL(
    (url) =>
      url.searchParams.get("session") === replacements[0].sessionId &&
      url.searchParams.get("event") === replacements[0].eventId,
  );
  await expect(page.locator(".event-flow-row--target")).toContainText(
    `${targets[0].title} updated`,
  );
  await expect(page.locator(`#event-${replacements[1].eventId}`)).toHaveCount(0);
});
