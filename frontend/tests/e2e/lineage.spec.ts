import { test, expect, type APIRequestContext } from "@playwright/test";
import { E2E_PROJECT_CWD } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`subagent lineage links, rail and keyboard map navigation at ${width}px`, async ({
    page,
    request,
  }) => {
    await page.setViewportSize({ width, height: 1000 });
    const parentClientId = `black-box-e2e-lineage-parent-${Date.now()}`;
    const childClientId = `${parentClientId}-child`;
    const parentId = await ingest(
      request,
      parentClientId,
      "UserPromptSubmit",
      "Lineage coordinator",
    );
    const childId = await ingest(request, childClientId, "SubagentStart", "Lineage reviewer", {
      parentClientSessionId: parentClientId,
      agentType: "reviewer",
    });
    await ingest(request, childClientId, "SubagentStop", "Lineage reviewer", {
      parentClientSessionId: parentClientId,
      agentType: "reviewer",
    });

    const links = await request.get(`/api/sessions/${parentId}/links`);
    expect(links.ok()).toBeTruthy();
    expect((await links.json()).children).toEqual([
      expect.objectContaining({ childSessionId: childId, linkType: "spawned" }),
    ]);
    const counts = await request.get(`/api/session-links/child-counts?ids=${parentId},${childId}`);
    expect(counts.ok()).toBeTruthy();
    expect(await counts.json()).toMatchObject({ [parentId]: 1 });
    const dag = await request.get(`/api/dag?sessionId=${childId}`);
    expect(dag.ok()).toBeTruthy();
    expect(await dag.json()).toMatchObject({
      nodes: expect.arrayContaining([
        expect.objectContaining({ type: "session", ref: parentId }),
        expect.objectContaining({ type: "session", ref: childId }),
      ]),
      edges: [expect.objectContaining({ type: "spawned" })],
    });

    // Old task deep links must no longer suppress the session lineage rail.
    await page.goto(`/sessions/${parentId}?task=retired-board-task`);
    await expect(
      page.getByRole("navigation", { name: "Utility" }).getByRole("link", { name: "Board" }),
    ).toHaveCount(0);
    const rail = page.getByRole("navigation", { name: "Agent lineage" });
    await expect(rail).toBeVisible();
    await expect(
      rail.getByRole("button", { name: "Current agent: Lineage coordinator" }),
    ).toBeVisible();
    await expect(rail.getByRole("button", { name: /Subagent:.*Lineage reviewer/ })).toBeVisible();
    if (width <= 880) {
      await page.getByRole("button", { name: /^Sessions / }).click();
      await expect(page.getByLabel("Find sessions", { exact: true })).toBeFocused();
    }
    await page
      .locator(".session-row-block")
      .filter({ has: page.locator(".session-row--active") })
      .getByRole("button", { name: "Toggle 1 subagent sessions" })
      .click();
    await expect(page.getByRole("group", { name: "Subagent sessions" })).toContainText(
      "Lineage reviewer",
    );
    if (width <= 880) await page.keyboard.press("Escape");
    await rail.getByRole("button", { name: /Subagent:.*Lineage reviewer/ }).click();
    await expect(page).toHaveURL(new RegExp(`/sessions/${childId}$`));
    await expect(
      rail.getByRole("button", { name: /Current subagent:.*Lineage reviewer/ }),
    ).toBeVisible();

    const toggle = page.getByRole("button", { name: "Expand lineage map" });
    await toggle.click();
    const map = page.getByRole("region", { name: "Agent lineage map" });
    await expect(map).toBeVisible();
    await expect(map).toHaveCSS("opacity", "1");
    await expect(map.locator(".dag-edges path")).toHaveCount(1);
    await page.screenshot({
      path: `test-results/shots/session-lineage-${width}.png`,
      animations: "disabled",
    });
    await page.keyboard.press("Escape");
    await expect(toggle).toBeFocused();
    await toggle.click();
    const parentNode = map.getByRole("link", { name: "Open session: Lineage coordinator" });
    await parentNode.focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(new RegExp(`/sessions/${parentId}$`));
  });
}

async function ingest(
  request: APIRequestContext,
  clientSessionId: string,
  eventType: string,
  title: string,
  metadata: Record<string, string> = {},
): Promise<string> {
  const response = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId,
      eventType,
      role: "user",
      text: title,
      cwd: E2E_PROJECT_CWD,
      metadata: { title, ...metadata },
    },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()).sessionId;
}
