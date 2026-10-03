import { test, expect } from "@playwright/test";
import { E2E_PROJECT_CWD } from "../../src/e2e/seedData";

const SHOT_DIR = "test-results/shots";

test("companion discloses mini, projects and items with Black Box links, and receives live handoffs", async ({
  page,
  request,
}) => {
  await page.goto("/companion");
  await expect(page.locator(".app-utility-bar")).toHaveCount(0);
  await expect(page.locator(".app-shell")).toHaveCSS("display", "block");
  const chip = page.getByRole("button", { name: /Black Box companion/ });
  await expect(chip).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-mini.png` });

  await chip.click();
  const projectRow = page.getByRole("button", { name: /^black-box-e2e:/ });
  await expect(projectRow).toBeVisible();
  await page.screenshot({ path: `${SHOT_DIR}/companion-compact.png` });

  await projectRow.click();
  const decision = page
    .locator(".companion-item")
    .filter({ hasText: "Use SolidJS + Vite for the UI rewrite" });
  await expect(decision).toBeVisible();
  await expect(decision).toHaveAttribute("href", /\/\?view=browse&session=[^&]+&event=[^&]+/);
  await expect(decision).toHaveAttribute("target", "_blank");
  await page.screenshot({ path: `${SHOT_DIR}/companion-expanded.png` });

  const marker = "COMPANION-HANDOFF-" + Date.now();
  const res = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: marker,
      eventType: "Handoff",
      role: "assistant",
      text: `Handoff to next-session: ${marker}`,
      cwd: E2E_PROJECT_CWD,
      metadata: {
        title: marker,
        kind: "handoff",
        contextSummary: marker,
        nextAction: "Verify the companion shows this",
        openLoops: ["none"],
      },
    },
  });
  expect(res.ok()).toBeTruthy();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.getByText("Next: Verify the companion shows this")).toBeVisible();

  await page.getByRole("button", { name: "River" }).click();
  await expect(page.locator(".companion-item").filter({ hasText: marker })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("button", { name: /^black-box-e2e:/ })).toBeVisible();
});

test("embedded companion paints no page background so the shell's clear panel shows through", async ({
  page,
}) => {
  await page.goto("/companion?embedded=1");
  await expect(page.getByRole("button", { name: /Black Box companion/ })).toBeVisible();
  await expect(page.locator("body")).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
  await expect(page.locator(".app-shell")).toHaveCSS("background-image", "none");
  await expect(page.locator(".app-shell")).toHaveCSS("background-color", "rgba(0, 0, 0, 0)");
});

type ProjectSummary = {
  projectKey: string;
  canonicalKey: string;
  scopes?: { projectKey: string; canonicalKey: string; primary: boolean }[];
};

test("companion recall hands a scoped question to Recall in a new browsing context", async ({
  page,
  context,
  request,
}) => {
  const token = `NAT326-${Date.now()}`;
  const question = `C++ & #${token} café ✓`;
  const seeded = await request.post("/api/decisions", {
    data: {
      source: "codex",
      clientSessionId: `companion-recall-${token}`,
      repo: E2E_PROJECT_CWD,
      decision: `Quick recall evidence: ${question}`,
      rationale: "Seeded for the companion recall journey",
    },
  });
  expect(seeded.ok()).toBeTruthy();
  const projects = (await (await request.get("/api/projects")).json()) as ProjectSummary[];
  const project = projects.find((candidate) =>
    (candidate.scopes ?? []).some((scope) => scope.canonicalKey === E2E_PROJECT_CWD),
  );
  expect(project).toBeDefined();
  const canonical =
    project!.scopes!.find((scope) => scope.primary)?.canonicalKey ?? project!.canonicalKey;

  await page.setViewportSize({ width: 400, height: 560 });
  await page.goto("/companion");
  await page.getByRole("button", { name: /Black Box companion/ }).click();
  await page.getByRole("button", { name: /^black-box-e2e:/ }).click();
  const field = page.getByRole("textbox", { name: "Recall in black-box-e2e" });
  const submit = page.getByRole("button", { name: "Recall", exact: true });
  await expect(field).toBeVisible();
  await expect(submit).toBeDisabled();
  await field.fill(" x ");
  await expect(submit).toBeDisabled();
  await field.press("Enter");
  await page.waitForTimeout(300);
  expect(context.pages()).toHaveLength(1);
  await page.screenshot({ path: `${SHOT_DIR}/companion-recall-expanded.png` });

  const recallRequests: string[] = [];
  context.on("request", (req) => {
    if (new URL(req.url()).pathname === "/api/recall") recallRequests.push(req.url());
  });
  await field.fill(`  ${question}  `);
  const navigation = context.waitForEvent(
    "request",
    (req) => req.isNavigationRequest() && new URL(req.url()).pathname === "/recall",
  );
  const popupPromise = page.waitForEvent("popup");
  await field.press("Enter");
  const [opened, popup] = await Promise.all([navigation, popupPromise]);

  const openedUrl = new URL(opened.url());
  expect([...openedUrl.searchParams.keys()]).toEqual(["project", "query", "run"]);
  expect(openedUrl.searchParams.get("project")).toBe(canonical);
  expect(openedUrl.searchParams.get("query")).toBe(question);
  expect(openedUrl.searchParams.get("run")).toBe("1");
  expect(openedUrl.hash).toBe("");
  expect(openedUrl.search).toBe(
    `?${new URLSearchParams({ project: canonical, query: question, run: "1" })}`,
  );
  // The companion itself never navigates.
  expect(new URL(page.url()).pathname).toBe("/companion");
  await expect(page.locator(".companion")).toHaveAttribute("data-mode", "expanded");

  await expect(
    popup.getByText(`Quick recall evidence: ${question}`, { exact: false }).first(),
  ).toBeVisible();
  await popup.waitForTimeout(800);
  expect(recallRequests).toHaveLength(1);
  const recallUrl = new URL(recallRequests[0]);
  expect(recallUrl.searchParams.get("project")).toBe(canonical);
  expect(recallUrl.searchParams.get("query")).toBe(question);
  await popup.screenshot({ path: `${SHOT_DIR}/companion-recall-popup.png` });
  await popup.close();

  // River: all projects, so no project scope.
  await page.getByRole("button", { name: "River" }).click();
  const riverField = page.getByRole("textbox", { name: "Recall across projects" });
  await riverField.fill(token);
  const riverNavigation = context.waitForEvent(
    "request",
    (req) => req.isNavigationRequest() && new URL(req.url()).pathname === "/recall",
  );
  const riverPopup = page.waitForEvent("popup");
  await page.getByRole("button", { name: "Recall", exact: true }).click();
  const riverUrl = new URL((await riverNavigation).url());
  expect(riverUrl.search).toBe(`?${new URLSearchParams({ query: token, run: "1" })}`);
  // Let the popup finish loading before closing it so the server never writes to a closed socket.
  const river = await riverPopup;
  await river.waitForLoadState("load");
  await river.close();

  // Escape while editing stays in the field; once the field is left, Escape steps down again.
  await riverField.fill("half a thought");
  await riverField.press("Escape");
  await expect(riverField).toHaveValue("");
  await expect(page.locator(".companion")).toHaveAttribute("data-mode", "expanded");
  await riverField.press("Escape");
  await expect(page.getByRole("button", { name: "Back to projects" })).toBeFocused();
  await expect(page.locator(".companion")).toHaveAttribute("data-mode", "expanded");
  await page.keyboard.press("Escape");
  await expect(page.locator(".companion")).toHaveAttribute("data-mode", "compact");
});

test("companion recall form fits a narrow expanded panel without overflow", async ({ page }) => {
  // The shell's panel loads ?embedded=1, which drops the app's 320px body minimum.
  await page.setViewportSize({ width: 300, height: 420 });
  await page.goto("/companion?embedded=1");
  await page.getByRole("button", { name: /Black Box companion/ }).click();
  await page.getByRole("button", { name: /^black-box-e2e:/ }).click();
  const form = page.getByRole("search");
  await expect(form).toBeVisible();
  const overflow = await form.evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const fieldBox = await page.getByRole("textbox", { name: /^Recall/ }).boundingBox();
  const buttonBox = await page.getByRole("button", { name: "Recall", exact: true }).boundingBox();
  expect(fieldBox!.width).toBeGreaterThan(120);
  expect(buttonBox!.x + buttonBox!.width).toBeLessThanOrEqual(300);
  await page.screenshot({ path: `${SHOT_DIR}/companion-recall-narrow.png` });
});
