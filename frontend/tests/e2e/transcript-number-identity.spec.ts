import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { AgentEvent, SessionTranscriptResponse } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`Browse preserves distinct numeric tool evidence through search and pagination at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const start = Date.now() - 120_000;
    const capture = async (index: number, reference?: string) => {
      const payload = JSON.stringify({
        source: "manual",
        clientSessionId: `numeric-evidence-${id}`,
        cwd: `/tmp/numeric-evidence-${id}`,
        turnId: `turn-${id}`,
        eventType: reference ? "PostToolUse" : index ? "AssistantMessage" : "UserPromptSubmit",
        role: reference ? "tool" : index ? "assistant" : "user",
        text: reference
          ? undefined
          : index
            ? `Response fragment ${index}`
            : "Compare the Lookup references",
        toolName: reference ? "Lookup" : undefined,
        toolOutput: reference ? { reference: "RAW_NUMBER" } : undefined,
        observedAt: new Date(start + index * 1000).toISOString(),
      }).replace('"RAW_NUMBER"', reference || '"unused"');
      const response = await request.post("/api/events", {
        data: payload,
        headers: { "Content-Type": "application/json" },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { eventId: string; sessionId: string };
    };
    await capture(0);
    const first = await capture(1, "9007199254740992");
    const second = await capture(2, "9007199254740993");
    const transcriptUrl = `/api/sessions/${first.sessionId}/transcript?limit=50`;
    const head = (await (await request.get(transcriptUrl)).json()) as SessionTranscriptResponse;
    expect(head.events.filter((event) => event.toolName === "Lookup")).toHaveLength(2);
    await page.goto(`/sessions/${first.sessionId}?reveal=session`);
    const firstRow = page.locator(`#event-${first.eventId}`);
    const secondRow = page.locator(`#event-${second.eventId}`);
    await expect(firstRow).toBeVisible();
    await expect(secondRow).toBeVisible();
    const search = page.getByRole("searchbox", { name: "Find in session" });
    const searched = page.waitForResponse(
      (response) =>
        response.url().includes(transcriptUrl) &&
        new URL(response.url()).searchParams.get("q") === "Lookup",
    );
    await search.fill("Lookup");
    expect((await searched).ok()).toBeTruthy();
    await expect.soft(firstRow).toBeVisible();
    await expect.soft(secondRow).toBeVisible();
    await search.fill("");
    await expect(firstRow).toBeVisible();
    await expect(secondRow).toBeVisible();

    for (let index = 3; index < 53; index++) await capture(index);
    await page.reload();
    await expect(firstRow).toHaveCount(0);
    await expect(secondRow).toHaveCount(0);
    const older = page.getByRole("button", { name: "Load older events" });
    await older.focus();
    await page.keyboard.press("Enter");
    await expect.soft(firstRow).toBeVisible();
    await expect.soft(secondRow).toBeVisible();
    await expect(older).toHaveCount(0);
    for (const [item, reference] of [
      [first, "9007199254740992"],
      [second, "9007199254740993"],
    ] as const) {
      const saved = (await (await request.get(`/api/events/${item.eventId}`)).json()) as AgentEvent;
      expect(saved.toolOutputJson).toBe(`{"reference":${reference}}`);
    }
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
  });
}
