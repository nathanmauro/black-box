import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

for (const width of [1440, 390]) {
  test(`Browse keeps lineage and pagination owned by the selected session at ${width}px`, async ({
    page,
    request,
  }) => {
    assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
    await page.setViewportSize({ width, height: 900 });
    const id = randomUUID();
    const repo = `/tmp/reader-request-state-${id}`;
    const titleA = `Reader A ${id}`;
    const titleB = `Reader B ${id}`;
    const capture = async (
      client: string,
      title: string,
      text: string,
      offset: number,
      metadata: Record<string, string> = {},
    ) => {
      const response = await request.post("/api/events", {
        data: {
          source: "claude",
          clientSessionId: `${id}-${client}`,
          cwd: repo,
          eventType: metadata.parentClientSessionId ? "SubagentStart" : "AssistantMessage",
          role: "assistant",
          text,
          observedAt: new Date(Date.now() - 86400_000 + offset).toISOString(),
          metadata: { title, ...metadata },
        },
      });
      expect(response.ok()).toBeTruthy();
      return (await response.json()) as { sessionId: string; eventId: string };
    };
    const olderA = await capture("a", titleA, "Earlier A evidence", 0);
    const headA = await capture("a", titleA, "Newest A evidence", 1000);
    const olderB = await capture("b", titleB, "Earlier B evidence", 0);
    const headB = await capture("b", titleB, "Newest B evidence", 1000);
    await capture("a-child", "A related child", "A child evidence", 2000, {
      parentClientSessionId: `${id}-a`,
      agentType: "reviewer",
    });
    const projects = (await (await request.get("/api/projects")).json()) as {
      canonicalKey: string;
      projectKey: string;
    }[];
    const project = projects.find((item) => item.canonicalKey === repo)!;
    expect(project).toBeTruthy();
    const dagResponse = await request.get(`/api/dag?sessionId=${headA.sessionId}`);
    expect(dagResponse.ok()).toBeTruthy();
    expect((await dagResponse.json()).nodes).toHaveLength(2);

    const dagGate = deferred<void>();
    const dagFinished = deferred<void>();
    let heldDag = false;
    const pages: {
      sessionId: string;
      requestUrl: string;
      release: (outcome: "success" | "error") => void;
      finished: Promise<void>;
    }[] = [];
    await page.route("**/api/dag?*", async (route) => {
      const url = new URL(route.request().url());
      if (url.searchParams.get("sessionId") !== headB.sessionId || heldDag) {
        await route.continue();
        return;
      }
      heldDag = true;
      const response = await route.fetch();
      await dagGate.promise;
      await route.fulfill({ response });
      dagFinished.resolve();
    });
    await page.route("**/api/sessions/*/transcript?*", async (route) => {
      const url = new URL(route.request().url());
      // Ask the real fixture API for a one-event page, retaining its real cursor and events.
      // This exercises native pagination without seeding hundreds of shared-session rows.
      url.searchParams.set("limit", "1");
      if (!url.searchParams.has("before")) {
        const response = await route.fetch({ url: url.toString() });
        await route.fulfill({ response });
        return;
      }
      // Register before the first await so cleanup can release every started delayed handler.
      const gate = deferred<"success" | "error">();
      const finished = deferred<void>();
      pages.push({
        sessionId: url.pathname.split("/")[3],
        requestUrl: route.request().url(),
        release: gate.resolve,
        finished: finished.promise,
      });
      const response = await route.fetch({ url: url.toString() });
      if ((await gate.promise) === "error")
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: '{"message":"Synthetic delayed page failure"}',
        });
      else await route.fulfill({ response });
      finished.resolve();
    });
    const choose = async (title: string, head: string) => {
      if (width <= 880) await page.getByRole("button", { name: /^Sessions / }).click();
      await page.locator(".session-row").filter({ hasText: title }).click();
      await expect(page.locator(".detail-header h1")).toHaveText(title);
      await expect(page.locator(".timeline-pane")).toContainText(head);
    };
    const startOlder = async (count: number, sessionId: string) => {
      const button = page.getByRole("button", { name: "Load older events", exact: true });
      await expect(button).toBeEnabled();
      await button.focus();
      await page.keyboard.press("Enter");
      await expect.poll(() => pages.length).toBe(count);
      expect(pages[count - 1].sessionId).toBe(sessionId);
      await expect(
        page.getByRole("button", { name: "Loading older…", exact: true }),
      ).toBeDisabled();
    };
    const releasePage = async (index: number, outcome: "success" | "error") => {
      const pending = pages[index];
      const completed = page.waitForEvent("requestfinished", {
        predicate: (sent) => sent.url() === pending.requestUrl,
      });
      pending.release(outcome);
      await completed;
      await pending.finished;
      // Check after response consumption and a paint, not merely after route fulfillment.
      await page.evaluate(
        () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
      );
    };
    const stillPending = async () => {
      await expect(
        page.getByRole("button", { name: "Loading older…", exact: true }),
      ).toBeDisabled();
      await expect(page.locator(".transcript-page-error")).toHaveCount(0);
    };
    try {
      await page.goto(`/?view=browse&project=${project.projectKey}&session=${headA.sessionId}`);
      await expect(page.locator(".timeline-pane")).toContainText("Newest A evidence");
      await expect(page.getByRole("button", { name: /Subagent:.*A related child/ })).toBeVisible();
      await startOlder(1, headA.sessionId);
      await choose(titleB, "Newest B evidence");
      await expect.poll(() => heldDag).toBe(true);
      await expect(page.getByRole("navigation", { name: "Agent lineage" })).toHaveCount(0);
      await startOlder(2, headB.sessionId);
      await releasePage(0, "success");
      await stillPending();
      await expect(page.locator(`#event-${olderA.eventId}`)).toHaveCount(0);
      dagGate.resolve();
      await dagFinished.promise;

      // A second A request becomes obsolete after another A → B → A round-trip.
      await choose(titleA, "Newest A evidence");
      await startOlder(3, headA.sessionId);
      await choose(titleB, "Newest B evidence");
      await choose(titleA, "Newest A evidence");
      await startOlder(4, headA.sessionId);
      await releasePage(2, "success");
      await stillPending();
      await expect(page.locator(`#event-${olderA.eventId}`)).toHaveCount(0);
      await releasePage(1, "error");
      await stillPending();
      await expect(page.locator(`#event-${olderB.eventId}`)).toHaveCount(0);
      await releasePage(3, "success");
      await expect(page.locator(`#event-${olderA.eventId}`)).toBeVisible();
      await expect(page.locator(`#event-${headA.eventId}`)).toBeVisible();
      await expect(page.getByRole("button", { name: /Load older|Loading older/ })).toHaveCount(0);
      await expect(page.locator(".event-flow-row")).toHaveCount(2);

      // A live capture advances this same session's first-page cursor while an older page waits.
      await choose(titleB, "Newest B evidence");
      await choose(titleA, "Newest A evidence");
      await startOlder(5, headA.sessionId);
      const refreshed = await capture("a", titleA, "Refreshed A evidence", 5000);
      await expect(page.locator(`#event-${refreshed.eventId}`)).toBeVisible();
      await releasePage(4, "success");
      await expect(page.locator(`#event-${olderA.eventId}`)).toHaveCount(0);
      await expect(page.locator(`#event-${headA.eventId}`)).toHaveCount(0);
      await startOlder(6, headA.sessionId);
      await releasePage(5, "success");
      await expect(page.locator(`#event-${headA.eventId}`)).toBeVisible();
      await expect(page.locator(`#event-${olderA.eventId}`)).toHaveCount(0);
      await startOlder(7, headA.sessionId);
      await releasePage(6, "success");
      await expect(page.locator(`#event-${olderA.eventId}`)).toBeVisible();
      await expect(page.locator(".event-flow-row")).toHaveCount(3);
      await expect(page.getByRole("button", { name: /Load older|Loading older/ })).toHaveCount(0);
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
        .toBe(true);
    } finally {
      dagGate.resolve();
      for (const pending of pages) pending.release("success");
      await page.unrouteAll({ behavior: "wait" });
    }
  });
}
