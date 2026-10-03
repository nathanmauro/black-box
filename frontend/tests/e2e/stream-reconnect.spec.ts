import { randomUUID } from "node:crypto";
import http, { type ServerResponse } from "node:http";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const mode of [
  "browse",
  "stream",
  "stream-reset",
  "stream-connected",
  "stream-empty",
  "stream-buffer",
] as const) {
  test(`${mode} recovers canonical captures`, async ({ page, request }) => {
    const base = test.info().project.use.baseURL || "http://127.0.0.1:8799";
    assertSafeSeedBaseUrl(base);
    // A loopback streaming proxy drops a real TCP response. Browser offline emulation does not
    // reliably close an already-open EventSource socket; the native browser still owns reconnect.
    const streams = new Set<ServerResponse>();
    const resumed: string[] = [];
    let paused = false;
    let forceReset = mode === "stream-reset";
    const disconnect = mode === "browse" || mode === "stream" || mode === "stream-reset";
    const proxy = http.createServer((incoming, outgoing) => {
      outgoing.setHeader("Access-Control-Allow-Origin", base);
      if (paused) {
        outgoing.writeHead(503).end();
        return;
      }
      const cursor = incoming.headers["last-event-id"];
      if (typeof cursor === "string") resumed.push(cursor);
      // Ask the real backend to reset once; the browser must recover from its canonical feed,
      // because the reset checkpoint deliberately skips historical replay.
      const forwardedCursor = typeof cursor === "string" && forceReset ? "legacy-fixture" : cursor;
      if (typeof cursor === "string") forceReset = false;
      const upstream = http.get(
        new URL("/api/stream", base),
        {
          headers: typeof forwardedCursor === "string" ? { "Last-Event-ID": forwardedCursor } : {},
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
              this.addEventListener("replay.reset", () => {
                document.documentElement.dataset.streamReset = "received";
              });
              this.addEventListener("event.appended", (message) => {
                const id = (JSON.parse((message as MessageEvent<string>).data) as { id: string })
                  .id;
                document.documentElement.dataset.lastStreamEvent = id;
                const count = Number(document.documentElement.dataset.streamEventCount || "0");
                document.documentElement.dataset.streamEventCount = String(count + 1);
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
          eventType:
            mode === "browse" || mode === "stream-empty" ? "UserPromptSubmit" : "Observation",
          role: mode === "browse" || mode === "stream-empty" ? "user" : "assistant",
          text: `Reconnect fixture ${id}`,
          cwd: `/tmp/reconnect-${id}`,
          observedAt: "2026-10-02T12:00:00Z",
        },
      });
      expect(initial.ok()).toBeTruthy();
      const { sessionId } = (await initial.json()) as { sessionId: string };
      await page.goto(
        mode === "browse"
          ? `/sessions/${sessionId}`
          : `/?${new URLSearchParams({ q: `session:${sessionId}`, project: "" })}`,
      );
      await expect(page.getByLabel("Connection status live")).toBeVisible();
      if (mode === "stream-empty") {
        await expect(page.getByText("No stream events match the current filters.")).toBeVisible();
      } else await expect(page.getByText(`Reconnect fixture ${id}`).first()).toBeVisible();
      if (disconnect) {
        paused = true;
        for (const stream of streams) stream.destroy();
        await expect(page.getByLabel("Connection status down")).toBeVisible();
      }
      if (mode === "stream-buffer") {
        for (let index = 1; index <= 51; index++) {
          const capture = await request.post("/api/events", {
            data: {
              source: "codex",
              clientSessionId: `reconnect-${id}`,
              eventType: "Observation",
              role: "assistant",
              text: `Buffer capture ${index} ${id}`,
              cwd: `/tmp/reconnect-${id}`,
              observedAt: new Date(Date.parse("2026-10-02T12:00:00Z") + index * 1000).toISOString(),
            },
          });
          expect(capture.ok()).toBeTruthy();
        }
        await expect(page.locator("html")).toHaveAttribute("data-stream-event-count", "51");
        await expect(page.getByText(`Buffer capture 51 ${id}`, { exact: true })).toBeVisible();
      }
      const text = `Recovery capture ${id}`;
      const missed = await request.post("/api/events", {
        data: {
          source: "codex",
          clientSessionId: `reconnect-${id}`,
          eventType: mode === "browse" ? "Stop" : "Observation",
          role: "assistant",
          text,
          cwd: `/tmp/reconnect-${id}`,
          observedAt: mode === "stream-buffer" ? "2026-10-02T12:02:00Z" : "2000-01-01T00:00:00Z",
        },
      });
      expect(missed.ok()).toBeTruthy();
      const { eventId } = (await missed.json()) as { eventId: string };
      if (disconnect) await expect(page.getByText(text, { exact: true })).toHaveCount(0);
      paused = false;
      await expect(page.getByLabel("Connection status live")).toBeVisible();
      if (mode === "stream-reset")
        await expect(page.locator("html")).toHaveAttribute("data-stream-reset", "received");
      else await expect(page.locator("html")).toHaveAttribute("data-last-stream-event", eventId);
      if (disconnect) expect(resumed.some((cursor) => cursor.startsWith("v2|"))).toBeTruthy();
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
}
