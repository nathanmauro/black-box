import { randomUUID } from "node:crypto";
import http, { type ServerResponse } from "node:http";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

test("open session catches up a backdated capture after a real stream disconnect", async ({
  page,
  request,
}) => {
  const base = test.info().project.use.baseURL || "http://127.0.0.1:8799";
  assertSafeSeedBaseUrl(base);
  // A loopback streaming proxy drops a real TCP response. Browser offline emulation does not
  // reliably close an already-open EventSource socket; the native browser still owns reconnect.
  const streams = new Set<ServerResponse>();
  const resumed: string[] = [];
  let paused = false;
  const proxy = http.createServer((incoming, outgoing) => {
    outgoing.setHeader("Access-Control-Allow-Origin", base);
    if (paused) {
      outgoing.writeHead(503).end();
      return;
    }
    const cursor = incoming.headers["last-event-id"];
    if (typeof cursor === "string") resumed.push(cursor);
    const upstream = http.get(
      new URL("/api/stream", base),
      {
        headers: typeof cursor === "string" ? { "Last-Event-ID": cursor } : {},
      },
      (response) => {
        outgoing.writeHead(response.statusCode || 502, { "Content-Type": "text/event-stream" });
        response.pipe(outgoing);
      },
    );
    upstream.on("error", () => outgoing.destroy());
    streams.add(outgoing);
    outgoing.on("close", () => {
      streams.delete(outgoing);
      upstream.destroy();
    });
  });
  await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
  const address = proxy.address();
  if (!address || typeof address === "string") throw new Error("Missing fixture proxy port");
  try {
    await page.addInitScript(
      ({ streamUrl }) => {
        const NativeEventSource = window.EventSource;
        window.EventSource = class extends NativeEventSource {
          constructor(url: string | URL, options?: EventSourceInit) {
            super(
              new URL(url, location.href).pathname === "/api/stream" ? streamUrl : url,
              options,
            );
            this.addEventListener("event.appended", (message) => {
              const id = (JSON.parse((message as MessageEvent<string>).data) as { id: string }).id;
              document.documentElement.dataset.lastStreamEvent = id;
            });
          }
        };
      },
      { streamUrl: `http://127.0.0.1:${address.port}/api/stream` },
    );
    const id = randomUUID();
    const initial = await request.post("/api/events", {
      data: {
        source: "codex",
        clientSessionId: `reconnect-${id}`,
        eventType: "UserPromptSubmit",
        role: "user",
        text: `Reconnect fixture ${id}`,
        cwd: `/tmp/reconnect-${id}`,
        observedAt: "2026-10-02T12:00:00Z",
      },
    });
    expect(initial.ok()).toBeTruthy();
    const { sessionId } = (await initial.json()) as { sessionId: string };
    await page.goto(`/sessions/${sessionId}`);
    await expect(page.getByLabel("Connection status live")).toBeVisible();
    await expect(page.getByText(`Reconnect fixture ${id}`).first()).toBeVisible();
    paused = true;
    for (const stream of streams) stream.destroy();
    await expect(page.getByLabel("Connection status down")).toBeVisible();
    const text = `Arrived while disconnected ${id}`;
    const missed = await request.post("/api/events", {
      data: {
        source: "codex",
        clientSessionId: `reconnect-${id}`,
        eventType: "Stop",
        role: "assistant",
        text,
        cwd: `/tmp/reconnect-${id}`,
        observedAt: "2000-01-01T00:00:00Z",
      },
    });
    expect(missed.ok()).toBeTruthy();
    const { eventId } = (await missed.json()) as { eventId: string };
    await expect(page.getByText(text, { exact: true })).toHaveCount(0);
    paused = false;
    await expect(page.getByLabel("Connection status live")).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("data-last-stream-event", eventId);
    expect(resumed.some((cursor) => cursor.startsWith("v2|"))).toBeTruthy();
    await expect(page.getByText(text, { exact: true })).toBeVisible();
  } finally {
    await page.close();
    for (const stream of streams) stream.destroy();
    proxy.closeAllConnections();
    await new Promise<void>((resolve, reject) =>
      proxy.close((error) => (error ? reject(error) : resolve())),
    );
  }
});
