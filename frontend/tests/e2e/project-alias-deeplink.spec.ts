import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

test("an alias evidence link preserves selection through redirect, reload and Back", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const canonical = `/tmp/alias-canonical-${id}`;
  const alias = `/tmp/alias-worktree-${id}`;
  const text = `Alias continuity evidence ${id}`;
  const captured = await request.post("/api/handoffs", {
    data: {
      source: "codex",
      clientSessionId: `alias-focus-${id}`,
      repo: alias,
      contextSummary: text,
      nextAction: "Follow this exact evidence",
      openLoops: [],
    },
  });
  expect(captured.ok()).toBeTruthy();
  const { eventId } = (await captured.json()) as { eventId: string };
  const aliased = await request.put("/api/project-aliases", {
    data: { aliasKey: alias, canonicalKey: canonical },
  });
  expect(aliased.ok()).toBeTruthy();
  const projects = (await (await request.get("/api/projects")).json()) as Array<{
    projectKey: string;
    canonicalKey: string;
    scopes: Array<{ projectKey: string; canonicalKey: string }>;
  }>;
  const project = projects.find((candidate) => candidate.canonicalKey === canonical)!;
  expect(project).toBeDefined();
  const scope = project.scopes.find((candidate) => candidate.canonicalKey === alias)!;
  expect(scope).toBeDefined();
  const suffix = `?focus=${encodeURIComponent(`capture:${eventId}`)}&source=shared%20link#evidence`;
  await page.goto("/recall");
  await page.goto(`/projects/${encodeURIComponent(scope.projectKey)}${suffix}`);
  await expect(page).toHaveURL(`/projects/${encodeURIComponent(project.projectKey)}${suffix}`);
  await expect(page.getByRole("region", { name: "Trajectory detail" })).toContainText(text);
  await page.reload();
  await expect(page.getByRole("region", { name: "Trajectory detail" })).toContainText(text);
  await expect(page).toHaveURL(`/projects/${encodeURIComponent(project.projectKey)}${suffix}`);
  await page.goBack();
  await expect(page).toHaveURL("/recall");
});
