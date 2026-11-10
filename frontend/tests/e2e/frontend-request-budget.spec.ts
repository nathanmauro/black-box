import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

test("palette coalesces rapid typing and opens the exact captured event", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const id = randomUUID();
  const text = `performance remote result ${id}`;
  const captured = await request.post("/api/events", {
    data: {
      source: "claude",
      clientSessionId: `request-budget-${id}`,
      cwd: "/tmp/black-box-request-budget",
      eventType: "Observation",
      text,
      metadata: { title: "Request budget fixture" },
    },
  });

  expect(captured.ok()).toBeTruthy();
  const event = (await captured.json()) as { eventId: string; sessionId: string };
  const searches: string[] = [];

  page.on("request", (request) => {
    const url = new URL(request.url());

    if (url.pathname === "/api/search" && url.searchParams.get("limit") === "8") {
      searches.push(url.searchParams.get("q") || "");
    }
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Open command palette" }).click();
  const palette = page.getByRole("dialog", { name: "Command palette" });

  await palette.getByRole("textbox").pressSequentially("performance");
  const result = palette.getByRole("option").filter({ hasText: text });

  await expect(result).toBeVisible();
  expect(searches).toEqual(["performance"]);
  await result.click();
  await expect(page).toHaveURL(new RegExp(`session=${event.sessionId}&event=${event.eventId}`));
  await expect(page.locator(".event-flow-row--target")).toContainText(text);
});

test("project stories fetch on selection and preserve the persisted Timeline view", async ({
  page,
  request,
}) => {
  const projects = (await (await request.get("/api/projects")).json()) as Array<{
    projectKey: string;
    canonicalKey: string;
  }>;
  const project = projects.find((item) => item.canonicalKey === "/tmp/black-box-e2e");

  expect(project).toBeDefined();
  const requests = { timeline: 0, trajectory: 0 };

  page.on("request", (request) => {
    const pathname = new URL(request.url()).pathname;

    if (pathname === `/api/projects/${project!.projectKey}/timeline`) {
      requests.timeline += 1;
    }

    if (pathname === `/api/projects/${project!.projectKey}/graph`) {
      requests.trajectory += 1;
    }
  });
  await page.goto(`/projects/${project!.projectKey}`);
  await expect(page.locator('.project-trajectory [data-node-kind="head"]')).toBeVisible();
  expect(requests).toEqual({ timeline: 0, trajectory: 1 });

  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await expect(page.locator(".project-timeline-row").first()).toBeVisible();
  expect(requests).toEqual({ timeline: 2, trajectory: 1 });

  requests.timeline = 0;
  requests.trajectory = 0;
  await page.reload();
  await expect(page.getByRole("button", { name: "Timeline", exact: true })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(page.locator(".project-timeline-row").first()).toBeVisible();
  expect(requests).toEqual({ timeline: 2, trajectory: 0 });
});
