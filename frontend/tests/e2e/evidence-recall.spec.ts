import { randomUUID } from "node:crypto";
import { expect, test, type APIResponse } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`Evidence capture, Idea join, recall and exact Browse source at ${width}px`, async ({
    page,
    request,
    context,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const key = randomUUID(),
      repo = `/tmp/evidence-recall-${key}`;
    const headers: Record<string, string> = { Accept: "application/json, text/event-stream" };
    const initialized = await request.post("/mcp", {
      headers,
      data: {
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2024-11-05",
          capabilities: {},
          clientInfo: { name: "evidence-e2e", version: "1" },
        },
      },
    });
    expect(initialized.ok()).toBeTruthy();
    const session = initialized.headers()["mcp-session-id"];
    if (session) headers["Mcp-Session-Id"] = session;
    let id = 1;
    async function tool(name: string, args: Record<string, unknown>) {
      const response = await request.post("/mcp", {
        headers,
        data: { jsonrpc: "2.0", id: ++id, method: "tools/call", params: { name, arguments: args } },
      });
      expect(response.ok()).toBeTruthy();
      const payload = await rpcPayload(response);
      expect(payload.error).toBeUndefined();
      expect(payload.result.isError).not.toBe(true);
      return JSON.parse(payload.result.content[0].text);
    }
    await tool("captureIdea", {
      source: "manual",
      clientSessionId: `idea-${key}`,
      repo,
      title: "Verify the proposed direction",
      oneLiner: "Check evidence before acting",
      origin: "joint",
      ideaKey: key,
    });
    expect(
      (
        await request.post("/api/events", {
          data: {
            source: "manual",
            clientSessionId: key,
            cwd: repo,
            eventType: "UserPromptSubmit",
            role: "user",
            text: "Preserve the measured evidence.",
          },
        })
      ).ok(),
    ).toBeTruthy();
    const claim = `Verified fact ${key}`,
      excerpt = "    measured output\n";
    const captured = await tool("captureEvidence", {
      source: "manual",
      clientSessionId: key,
      repo,
      claim,
      excerpt,
      sourceRef: "fixture.txt:12",
      observedAt: "2001-01-01T00:00:00.123456789Z",
      supports: [`idea:${key}`],
      notes: "Verify before reuse",
    });
    const detail = await tool("recallIdea", { ideaKey: key });
    expect(detail.supports).toHaveLength(1);
    expect(detail.supports[0]).toMatchObject({
      eventId: captured.eventId,
      excerpt,
      sourceRef: "fixture.txt:12",
    });
    const canonical = await (await request.get(`/api/events/${captured.eventId}`)).json();
    expect(canonical.metadata.observedAt).toBe("2001-01-01T00:00:00.123456789Z");
    expect(canonical.text).toContain(excerpt);
    await page.goto(`/recall?project=${encodeURIComponent(repo)}`);
    await expect(page.getByRole("checkbox", { name: "Evidence", exact: true })).not.toBeChecked();
    await expect(page.getByRole("checkbox", { name: "Decision", exact: true })).toBeChecked();
    await page.goto(`/recall?project=${encodeURIComponent(repo)}&kinds=evidence&run=1`);
    const card = page.getByRole("article", { name: claim, exact: true });
    await expect(card).toBeVisible();
    await expect(card).toContainText("fixture.txt:12");
    await expect(card).toContainText("Verify before reuse");
    await page.getByRole("button", { name: "Copy context" }).click();
    await expect(page.getByText("Context copied with source links.")).toBeVisible();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain(canonical.text);
    expect(copied).toContain(`event=${captured.eventId}`);
    await card.getByRole("link", { name: `Open ${claim} in Browse` }).click();
    const evidence = page.locator(`#event-${captured.eventId}`);
    await expect(evidence).toHaveClass(/event-flow-row--target/);
    await expect(evidence.getByText("Evidence", { exact: true })).toBeVisible();
    await expect(evidence.getByText("agent response", { exact: true })).toHaveCount(0);
    await expect(evidence).toContainText("fixture.txt:12");
    await page.goto(`/?view=browse&session=${captured.sessionId}&project=`);
    const toggle = page.getByRole("checkbox", { name: "Show memory events" });
    await expect(toggle).not.toBeChecked();
    await expect(evidence).toHaveCount(0);
    await toggle.check();
    await expect(evidence).toContainText(claim);
    await evidence.scrollIntoViewIfNeeded();
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({ path: test.info().outputPath(`evidence-memory-${width}.png`) });
    expect(await (await request.get(`/api/events/${captured.eventId}`)).json()).toEqual(canonical);
  });
}
async function rpcPayload(response: APIResponse) {
  const text = await response.text();
  if (response.headers()["content-type"]?.includes("text/event-stream")) {
    const message = text.split("\n").find((line) => line.startsWith("data:"));
    expect(message).toBeDefined();
    return JSON.parse(message!.slice(5));
  }
  return JSON.parse(text);
}
