import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { AgentEvent } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

for (const width of [1440, 390]) {
  test(`Browse exposes exact numeric tool input and result by keyboard at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const raw = '{"reference":9007199254740993,"label":"Exact captured reference"}';
    const capture = async (doubleEncoded: boolean, safe = false) => {
      const value = safe
        ? '{"count":100,"label":"9007199254740993 is text"}'
        : doubleEncoded
          ? JSON.stringify(raw)
          : raw;
      const data = JSON.stringify({
        source: "manual",
        clientSessionId: `number-preview-${id}`,
        cwd: `/tmp/number-preview-${id}`,
        eventType: "PostToolUse",
        role: "tool",
        toolName: "Lookup",
        toolInput: "RAW_INPUT",
        toolOutput: "RAW_OUTPUT",
      })
        .replace('"RAW_INPUT"', value)
        .replace('"RAW_OUTPUT"', value);
      const response = await request.post("/api/events", {
        data,
        headers: { "Content-Type": "application/json" },
      });
      expect(response.ok()).toBeTruthy();
      const receipt = (await response.json()) as { eventId: string; sessionId: string };
      const event = (await (
        await request.get(`/api/events/${receipt.eventId}`)
      ).json()) as AgentEvent;
      expect(event.toolInputJson).toBe(value);
      expect(event.toolOutputJson).toBe(value);
      return { ...receipt, event, value };
    };
    const plain = await capture(false);
    const double = await capture(true);
    const safe = await capture(false, true);
    await page.goto(`/sessions/${plain.sessionId}?reveal=session`);
    for (const captured of [plain, double]) {
      const row = page.locator(`#event-${captured.eventId}`);
      await expect(row).toBeVisible();
      for (const label of ["input", "result"]) {
        const summary = row
          .locator("summary")
          .filter({ hasText: new RegExp(`^Original ${label}`) });
        await expect(summary).toBeVisible();
        const section = summary.locator("xpath=ancestor::section[1]");
        await expect(section).toContainText(
          "Numeric values changed in this preview. Open Original for exact captured text.",
        );
        await expect(section.locator(".tool-payload-original")).toHaveCount(0);
        await summary.focus();
        await page.keyboard.press("Enter");
        expect(await section.locator(".tool-payload-original").textContent()).toBe(captured.value);
        await page.keyboard.press("Space");
        await expect(section.locator(".tool-payload-original")).not.toBeVisible();
      }
      const unchanged = (await (
        await request.get(`/api/events/${captured.eventId}`)
      ).json()) as AgentEvent;
      expect(unchanged.toolInputJson).toBe(captured.event.toolInputJson);
      expect(unchanged.toolOutputJson).toBe(captured.event.toolOutputJson);
    }
    await page.screenshot({ path: test.info().outputPath(`numeric-preview-${width}.png`) });
    const safeRow = page.locator(`#event-${safe.eventId}`);
    await expect(safeRow).toContainText("9007199254740993 is text");
    await expect(safeRow.locator("summary")).toHaveCount(0);
    await expect(safeRow.locator(".tool-payload-note")).toHaveCount(0);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
  });
}
