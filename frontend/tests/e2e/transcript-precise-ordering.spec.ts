import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { AgentEvent, SessionTranscriptResponse } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`Browse keeps an exact older answer with its own prompt at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const repo = `/tmp/transcript-precision-${id}`;
    const second = new Date(Date.now() - 60_000).toISOString().split(".")[0];
    const capture = async (text: string, role: "user" | "assistant", fraction: number) => {
      const observedAt = `${second}.${fraction}Z`;
      const response = await request.post("/api/events", {
        data: {
          source: "manual",
          clientSessionId: `transcript-precision-${id}`,
          cwd: repo,
          eventType: role === "user" ? "UserPromptSubmit" : "AssistantMessage",
          role,
          text,
          observedAt,
        },
      });
      expect(response.ok()).toBeTruthy();
      return { ...((await response.json()) as { eventId: string; sessionId: string }), observedAt };
    };
    const earlierPrompt = await capture("Explain the earlier task", "user", 123456787);
    const answer = await capture("Answer to the earlier task", "assistant", 123456788);
    const nextPrompt = await capture("Start an unrelated next task", "user", 123456789);
    const replies = [];
    for (let i = 0; i < 49; i++)
      replies.push(
        await capture(`Response fragment ${i} for the next task`, "assistant", 123456790 + i),
      );
    const transcriptUrl = `/api/sessions/${answer.sessionId}/transcript?limit=50`;
    const response = await request.get(transcriptUrl);
    expect(response.ok()).toBeTruthy();
    const head = (await response.json()) as SessionTranscriptResponse;
    expect(head.events).toHaveLength(50);
    expect(head.events.map((event) => event.id)).not.toContain(answer.eventId);
    expect(head.events.at(-1)?.id).toBe(nextPrompt.eventId);
    expect(head.nextBefore).toBeTruthy();

    await page.goto(`/?view=browse&session=${answer.sessionId}&event=${answer.eventId}&project=`);
    const target = page.locator(`#event-${answer.eventId}`);
    await expect(target).toBeVisible();
    await expect(target).toHaveClass(/event-flow-row--target/);
    const preamble = page.locator(".prompt-turn--preamble");
    await expect(preamble).toContainText("Answer to the earlier task");
    await expect(preamble).not.toContainText("Start an unrelated next task");
    await expect(page.locator(".event-flow-row")).toHaveCount(51);
    expect(
      await page.locator(".event-flow-row").evaluateAll((rows) => rows.map((row) => row.id)),
    ).toEqual([
      `event-${answer.eventId}`,
      `event-${nextPrompt.eventId}`,
      ...replies.map((reply) => `event-${reply.eventId}`),
    ]);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await target.scrollIntoViewIfNeeded();
    await expect(target).toBeInViewport();
    await page.screenshot({ path: test.info().outputPath(`exact-target-${width}.png`) });

    const loadOlder = page.getByRole("button", { name: "Load older events" });
    await loadOlder.focus();
    const olderResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/sessions/${answer.sessionId}/transcript?`) &&
        new URL(response.url()).searchParams.get("before") === head.nextBefore,
    );
    await page.keyboard.press("Enter");
    expect((await olderResponse).ok()).toBeTruthy();
    await expect(page.locator(".event-flow-row")).toHaveCount(52);
    await expect(preamble).toHaveCount(0);
    const previousTurn = page.locator(".prompt-turn").filter({ has: target });
    await expect(previousTurn).toContainText("Explain the earlier task");
    await expect(previousTurn).toContainText("Answer to the earlier task");
    await expect(previousTurn).not.toContainText("Start an unrelated next task");
    await expect(previousTurn.locator(".event-flow-row")).toHaveCount(2);
    await expect(page.locator(".prompt-turn")).toHaveCount(2);
    await expect(target).toHaveCount(1);
    await expect(target).toHaveClass(/event-flow-row--target/);
    await expect(loadOlder).toHaveCount(0);
    expect(await page.locator(".event-flow-row").first().getAttribute("id")).toBe(
      `event-${earlierPrompt.eventId}`,
    );
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await target.scrollIntoViewIfNeeded();
    await expect(target).toBeInViewport();
    await page.screenshot({ path: test.info().outputPath(`own-prompt-${width}.png`) });
    const saved = await request.get(`/api/events/${answer.eventId}`);
    expect(saved.ok()).toBeTruthy();
    expect(((await saved.json()) as AgentEvent).observedAt).toBe(answer.observedAt);
  });
}
