import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

// Query composition in the Stream box (docs/superpowers/plans/2026-10-15-stream-query-composition.md):
// completion edits the token at the caret, Tab accepts, and the submitted q is exactly what was
// composed. Each test owns fresh /tmp repos captured through the public API and scopes with
// project_exact:, so records appended by other journeys never change what it sees.
async function seedComposeFixture(request: APIRequestContext) {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const repo = `/tmp/stream-compose-${id}`;
  // A project path containing a space, reachable only through a quoted value.
  const spaced = `/tmp/stream compose ${id}`;
  const fixture = {
    id,
    repo,
    spaced,
    decision: `Compose decision ${id}`,
    handoff: `Compose handoff ${id}`,
    observation: `Compose observation ${id}`,
    spacedDecision: `Spaced compose decision ${id}`,
  };
  const captures: Array<[string, Record<string, unknown>]> = [
    [
      "/api/decisions",
      {
        source: "codex",
        clientSessionId: `stream-compose-codex-${id}`,
        repo,
        decision: fixture.decision,
        rationale: "Composition fixture decision.",
      },
    ],
    [
      "/api/handoffs",
      {
        source: "claude",
        clientSessionId: `stream-compose-claude-${id}`,
        repo,
        contextSummary: fixture.handoff,
        nextAction: "Keep composing by keyboard",
      },
    ],
    [
      "/api/events",
      {
        source: "codex",
        clientSessionId: `stream-compose-codex-${id}`,
        cwd: repo,
        eventType: "Observation",
        role: "assistant",
        text: fixture.observation,
      },
    ],
    [
      "/api/decisions",
      {
        source: "codex",
        clientSessionId: `stream-compose-spaced-${id}`,
        repo: spaced,
        decision: fixture.spacedDecision,
        rationale: "Composition fixture with a space in its project path.",
      },
    ],
  ];
  for (const [path, data] of captures) {
    expect((await request.post(path, { data })).ok()).toBeTruthy();
  }
  return fixture;
}

function suggestion(page: Page, name: string) {
  return page
    .getByRole("listbox", { name: "Query suggestions" })
    .getByRole("option", { name, exact: true });
}

async function pressTimes(page: Page, key: string, times: number) {
  for (let step = 0; step < times; step++) await page.keyboard.press(key);
}

for (const width of [1440, 390]) {
  test(`keyboard-only composition narrows the owned Stream at ${width}px`, async ({
    page,
    request,
  }) => {
    const fixture = await seedComposeFixture(request);
    await page.setViewportSize({ width, height: 900 });
    const scope = `project_exact:${fixture.repo}`;
    await page.goto(`/?project=&q=${encodeURIComponent(scope)}`);
    const rows = page.locator(".stream-row");
    await expect(rows).toHaveCount(3);
    // Counts for the applied scope have arrived, so offered values are grounded in this repo.
    await expect(page.getByRole("button", { name: "3 matches", exact: true })).toBeVisible();

    const query = page.getByLabel("Stream query");
    await query.focus();
    await page.keyboard.press("End");
    await page.keyboard.type(" kind:Dec");
    await expect(suggestion(page, "Decision")).toBeVisible();
    // Tab accepts the first option once a prefix is typed, keeping focus in the query.
    await page.keyboard.press("Tab");
    await expect(query).toHaveValue(`${scope} kind:Decision `);
    await expect(query).toBeFocused();
    await expect(page.getByRole("listbox", { name: "Query suggestions" })).toBeHidden();

    await page.keyboard.type("source:co");
    await expect(suggestion(page, "codex")).toBeVisible();
    await page.keyboard.press("ArrowDown");
    await expect(suggestion(page, "codex")).toHaveAttribute("aria-selected", "true");
    await page.keyboard.press("Tab");
    await expect(query).toHaveValue(`${scope} kind:Decision source:codex `);
    await page.keyboard.press("Enter");

    const composed = `${scope} kind:Decision source:codex`;
    await expect(page).toHaveURL((url) => url.searchParams.get("q") === composed);
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(fixture.decision);
  });

  test(`a mid-string edit completes the token at the caret at ${width}px`, async ({
    page,
    request,
  }) => {
    const fixture = await seedComposeFixture(request);
    await page.setViewportSize({ width, height: 900 });
    const scope = `project_exact:${fixture.repo}`;
    await page.goto(`/?project=&q=${encodeURIComponent(scope)}`);
    const rows = page.locator(".stream-row");
    await expect(rows).toHaveCount(3);

    const query = page.getByLabel("Stream query");
    const suffix = " source:claude";
    await query.fill(`${scope} kind:Ha${suffix}`);
    await pressTimes(page, "ArrowLeft", suffix.length);
    await page.keyboard.type("n");
    // The option follows the token under the caret, not the last token.
    await expect(suggestion(page, "Handoff")).toBeVisible();
    await page.keyboard.press("Tab");
    await expect(query).toHaveValue(`${scope} kind:Handoff${suffix}`);
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(
      (url) => url.searchParams.get("q") === `${scope} kind:Handoff${suffix}`,
    );
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(fixture.handoff);
  });

  test(`a quoted project path with a space completes before the rest at ${width}px`, async ({
    page,
    request,
  }) => {
    const fixture = await seedComposeFixture(request);
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/?project=");

    const query = page.getByLabel("Stream query");
    const rest = " kind:Decision";
    // An unclosed quote: everything after the caret must survive the accept byte-for-byte.
    await query.fill(`project_exact:"/tmp/stream compose ${fixture.id.slice(0, 7)}${rest}`);
    await pressTimes(page, "ArrowLeft", rest.length);
    await page.keyboard.type(fixture.id.charAt(7));
    await expect(suggestion(page, fixture.spaced)).toBeVisible();
    await page.keyboard.press("Tab");

    const composed = `project_exact:"${fixture.spaced}"${rest}`;
    await expect(query).toHaveValue(composed);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL((url) => url.searchParams.get("q") === composed);
    const rows = page.locator(".stream-row");
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(fixture.spacedDecision);
  });
}
