import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";
import type { RecallResult } from "../../src/lib/api";

type Capture = { eventId: string; sessionId: string };
for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`retained recalled evidence updates optional lists at ${viewport.width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize(viewport);
    const id = randomUUID();
    const project = `/tmp/recall-evidence-selection-${id}`;
    const questionText = `recovery ${id}`;
    const empty = {
      decision: `Local recovery ${id}`,
      rationale: "Keep the first experiment self-contained.",
      alternatives: [] as string[],
      openLoops: [] as string[],
    };
    const populated = {
      decision: `Shared recovery ${id}`,
      rationale: "Verify rollback before sharing the store.",
      alternatives: ["Keep a separate recovery copy"],
      openLoops: ["Verify rollback before replacing the store"],
    };
    const captures: Capture[] = [];
    const canonical: unknown[] = [];
    for (const [index, decision] of [empty, populated].entries()) {
      const response = await request.post("/api/decisions", {
        data: {
          source: "codex",
          clientSessionId: `recall-evidence-${id}-${index}`,
          repo: project,
          ...decision,
        },
      });
      expect(response.ok()).toBeTruthy();
      const captured = (await response.json()) as Capture;
      captures.push(captured);
      canonical.push(await (await request.get(`/api/events/${captured.eventId}`)).json());
    }
    const response = await request.get("/api/recall", {
      params: { project, query: questionText, withinHours: 168, kinds: "decision,handoff" },
    });
    expect(response.ok()).toBeTruthy();
    const recalled = (await response.json()) as RecallResult;
    expect(recalled.items.map((item) => item.eventId).sort()).toEqual(
      captures.map((capture) => capture.eventId).sort(),
    );
    const withLists = recalled.items.find((item) => item.eventId === captures[1].eventId)!;
    expect(withLists.alternatives).toEqual(populated.alternatives);
    expect(withLists.openLoops).toEqual(populated.openLoops);

    await page.goto(`/recall?project=${encodeURIComponent(project)}`);
    const question = page.getByLabel("Question", { exact: true });
    await question.fill(questionText);
    const evidence = page.getByRole("region", { name: "Selected evidence" });
    for (const [index, selected] of [empty, populated, empty].entries()) {
      if (index > 0) await question.focus();
      const option = page.getByRole("option", { name: new RegExp(selected.decision) });
      await expect(option).toBeVisible();
      // Enter activates the native suggestion button without changing the query or closing evidence.
      await option.focus();
      await option.press("Enter");
      const card = evidence.getByRole("article", { name: selected.decision });
      await expect(card).toBeVisible();
      await expect(card).toContainText(selected.rationale);
      await expect(card).not.toContainText(
        selected === empty ? populated.rationale : empty.rationale,
      );
      const capture = captures[selected === empty ? 0 : 1];
      const source = new URL((await card.getByRole("link").getAttribute("href"))!, page.url());
      expect(source.searchParams.get("event")).toBe(capture.eventId);
      expect(source.searchParams.get("session")).toBe(capture.sessionId);
      if (selected === populated) {
        await expect(card.getByText("alternatives", { exact: true })).toBeVisible();
        await expect(card.getByText("open loops", { exact: true })).toBeVisible();
        await expect(card.getByRole("listitem")).toHaveText([
          ...populated.alternatives,
          ...populated.openLoops,
        ]);
        await evidence.screenshot({
          path: test.info().outputPath(`evidence-lists-${viewport.width}.png`),
        });
      } else {
        await expect(card.getByText("alternatives", { exact: true })).toHaveCount(0);
        await expect(card.getByText("open loops", { exact: true })).toHaveCount(0);
        await expect(card.getByRole("list")).toHaveCount(0);
        await expect(card).not.toContainText(populated.alternatives[0]);
        await expect(card).not.toContainText(populated.openLoops[0]);
      }
      await expect(question).toHaveValue(questionText);
      await expect(page).toHaveURL((url) => url.searchParams.get("project") === project);
    }
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
    for (const [index, capture] of captures.entries()) {
      expect(await (await request.get(`/api/events/${capture.eventId}`)).json()).toEqual(
        canonical[index],
      );
    }
  });
}
