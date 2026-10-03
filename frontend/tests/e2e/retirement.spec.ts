import { test, expect, type APIResponse } from "@playwright/test";

test("the packaged server exposes thirteen memory tools and no task API", async ({ request }) => {
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
        clientInfo: { name: "black-box-e2e", version: "1" },
      },
    },
  });
  expect(initialized.ok(), await initialized.text()).toBeTruthy();
  const sessionId = initialized.headers()["mcp-session-id"];
  if (sessionId) headers["Mcp-Session-Id"] = sessionId;
  await request.post("/mcp", {
    headers,
    data: { jsonrpc: "2.0", method: "notifications/initialized" },
  });
  const response = await request.post("/mcp", {
    headers,
    data: { jsonrpc: "2.0", id: 2, method: "tools/list", params: {} },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  const payload = await rpcPayload(response);
  expect(payload.error).toBeUndefined();
  expect(payload.result.tools.map((tool: { name: string }) => tool.name).sort()).toEqual(
    [
      "captureDecision",
      "captureEvidence",
      "captureHandoff",
      "captureIdea",
      "captureObservation",
      "captureProjection",
      "findBraids",
      "localModelStatus",
      "recallContext",
      "recallIdea",
      "recentSessions",
      "searchContext",
      "searchSessions",
    ].sort(),
  );
  for (const path of ["/api/tasks", "/api/specs/retired", "/api/tasks/retired/dag"]) {
    expect((await request.get(path)).status()).toBe(404);
  }
});

async function rpcPayload(response: APIResponse) {
  const text = await response.text();
  if (response.headers()["content-type"]?.includes("text/event-stream")) {
    const message = text.split("\n").find((line) => line.startsWith("data:"));
    expect(message).toBeDefined();
    return JSON.parse(message!.slice(5));
  }
  return JSON.parse(text);
}
