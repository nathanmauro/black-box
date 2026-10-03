import { mkdirSync } from "node:fs";
import path from "node:path";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import type { IngestResponse, ProjectSummary } from "../../src/lib/api";
import { assertSafeSeedBaseUrl } from "../../src/e2e/seedData";

// Opt-in README showcase capture. Ordinary `npm run e2e` skips this file; set SBA_SHOWCASE_OUT to
// the directory that should receive hero.png, recall.png, and trajectory.png (docs/showcase.md).
// Everything shown is the synthetic story below, recorded through the public capture API into the
// isolated fixture database, then read back through the unmodified UI.
const OUT = process.env.SBA_SHOWCASE_OUT;
const REPO = "/tmp/acme-auth";
const DAY = 24 * 60 * 60 * 1000;
const HANDOFF = "Auth token strategy chosen and scaffolded; login and refresh endpoints stubbed";

test.skip(!OUT, "Set SBA_SHOWCASE_OUT to regenerate the README showcase screenshots.");
test.use({ viewport: { width: 1400, height: 880 }, deviceScaleFactor: 2, colorScheme: "dark" });

type StoryEvent = {
  daysAgo: number;
  source: "codex" | "claude";
  session: string;
  eventType: string;
  role: string;
  text: string;
  toolName?: string;
  metadata?: Record<string, unknown>;
};

// Epochs sit more than 48h apart so the trajectory spine shows separate bursts.
const STORY: StoryEvent[] = [
  {
    daysAgo: 16,
    source: "codex",
    session: "codex-auth-spike",
    eventType: "UserPromptSubmit",
    role: "user",
    text: "Design the auth token strategy for the Acme API",
  },
  {
    daysAgo: 16,
    source: "codex",
    session: "codex-auth-spike",
    eventType: "PostToolUse",
    role: "tool",
    toolName: "Read",
    text: "Read src/security/middleware.ts — no token layer yet",
  },
  {
    daysAgo: 16,
    source: "codex",
    session: "codex-auth-spike",
    eventType: "Decision",
    role: "assistant",
    text: "Terminate auth at the API gateway, not in each service",
    metadata: {
      title: "Auth boundary",
      kind: "decision",
      decision: "Terminate auth at the API gateway, not in each service",
      rationale: "One token check path; services trust the gateway's signed identity header",
      alternatives: ["Validate tokens in every service", "Sidecar auth proxy per pod"],
      confidence: 0.86,
      repo: REPO,
    },
  },
  {
    daysAgo: 11,
    source: "claude",
    session: "claude-rotation-store",
    eventType: "Decision",
    role: "assistant",
    text: "Store hashed refresh tokens in a SQLite rotation table",
    metadata: {
      title: "Rotation table storage",
      kind: "decision",
      decision: "Store hashed refresh tokens in a SQLite rotation table",
      rationale: "Keeps rotation local and auditable; one family row per login",
      alternatives: ["Keep refresh tokens in Redis"],
      confidence: 0.74,
      repo: REPO,
    },
  },
  {
    daysAgo: 11,
    source: "claude",
    session: "claude-rotation-store",
    eventType: "Observation",
    role: "assistant",
    text: "Rotation load test: p95 refresh latency 18 ms across 5k token families",
    metadata: { title: "Rotation load test", kind: "observation", repo: REPO },
  },
  {
    daysAgo: 6,
    source: "codex",
    session: "codex-login-endpoints",
    eventType: "Handoff",
    role: "assistant",
    text: "Login and refresh endpoints shipped behind the auth-v2 flag",
    metadata: {
      title: "Endpoints behind flag",
      kind: "handoff",
      nextAction: "Enable auth-v2 for the staging tenant",
      repo: REPO,
    },
  },
  {
    daysAgo: 0,
    source: "claude",
    session: "claude-revoke-on-logout",
    eventType: "UserPromptSubmit",
    role: "user",
    text: "Wire revoke-on-logout for the Acme auth flow",
  },
  {
    daysAgo: 0,
    source: "claude",
    session: "claude-revoke-on-logout",
    eventType: "PostToolUse",
    role: "tool",
    toolName: "Edit",
    text: "Edit src/auth/logout.ts — revoke the token family on logout",
  },
  {
    daysAgo: 0,
    source: "claude",
    session: "claude-revoke-on-logout",
    eventType: "PostToolUse",
    role: "tool",
    toolName: "Bash",
    text: "npm test -- auth/logout — 14 passed",
  },
  {
    daysAgo: 0,
    source: "claude",
    session: "claude-revoke-on-logout",
    eventType: "Observation",
    role: "assistant",
    text: "Logout now revokes the whole refresh-token family; reuse returns 401",
    metadata: { title: "Revoke verified", kind: "observation", repo: REPO },
  },
];

// The fresh Claude session arrives after the handoff and closes the open loop.
const TODAY = STORY.filter((event) => event.daysAgo === 0);
STORY.splice(STORY.length - TODAY.length, TODAY.length);

const PROJECTION = {
  source: "claude",
  clientSessionId: "claude-revoke-on-logout",
  repo: REPO,
  basis: "Revoke-on-logout is wired; the remaining auth hardening paths from recorded open loops.",
  paths: [
    { title: "Add refresh-token reuse detection", confidence: 0.71 },
    { title: "Rate-limit login attempts", confidence: 0.52 },
    { title: "Move rotation table to Postgres", confidence: 0.28 },
  ],
};

async function seedStory(request: APIRequestContext) {
  const now = Date.now();
  const captured = new Map<string, IngestResponse>();
  const post = async (event: StoryEvent) => {
    const response = await request.post("/api/events", {
      data: {
        source: event.source,
        clientSessionId: event.session,
        eventType: event.eventType,
        role: event.role,
        toolName: event.toolName,
        text: event.text,
        cwd: REPO,
        metadata: event.metadata ?? { repo: REPO },
        // Today's events keep server time so they land after the real handoff capture.
        observedAt: event.daysAgo ? new Date(now - event.daysAgo * DAY).toISOString() : undefined,
      },
    });
    expect(response.ok(), `capture ${event.text}`).toBeTruthy();
  };
  for (const event of STORY) await post(event);
  // Today's structured intent goes through the dedicated capture endpoints, exactly as agents
  // record it over REST/MCP; these are the records Recall answers from.
  const decision = await request.post("/api/decisions", {
    data: {
      source: "codex",
      clientSessionId: "codex-auth-tokens",
      repo: REPO,
      decision: "Use JWT access tokens with refresh-token rotation",
      rationale: "Stateless and horizontally scalable; avoids a shared session store",
      alternatives: ["Server-side sessions in Redis", "Opaque tokens with introspection"],
      confidence: 0.8,
      openLoops: ["revoke-on-logout is not wired yet", "refresh-token reuse detection is a TODO"],
    },
  });
  expect(decision.ok()).toBeTruthy();
  captured.set("decision", (await decision.json()) as IngestResponse);
  const handoff = await request.post("/api/handoffs", {
    data: {
      source: "codex",
      clientSessionId: "codex-auth-tokens",
      repo: REPO,
      toAgent: "next-session",
      contextSummary: HANDOFF,
      openLoops: ["revoke-on-logout not wired"],
      nextAction: "Wire revoke-on-logout against the rotation table",
    },
  });
  expect(handoff.ok()).toBeTruthy();
  captured.set("handoff", (await handoff.json()) as IngestResponse);
  for (const event of TODAY) await post(event);
  expect((await request.post("/api/projections", { data: PROJECTION })).ok()).toBeTruthy();
  const projects = (await (await request.get("/api/projects")).json()) as ProjectSummary[];
  const project = projects.find((candidate) => candidate.canonicalKey === REPO);
  expect(project, "synthetic project is cataloged").toBeDefined();
  return { project: project!, captured };
}

async function shoot(page: Page, name: string) {
  mkdirSync(OUT!, { recursive: true });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(OUT!, name) });
}

test("showcase: capture, recall, activity, and trajectory from one synthetic story", async ({
  page,
  request,
}) => {
  assertSafeSeedBaseUrl(test.info().project.use.baseURL || "http://127.0.0.1:8799");
  const { project, captured } = await seedStory(request);
  const handoff = captured.get("handoff")!;

  // Recall: pick the project, ask a separate question, read the recorded results.
  // Each capture uses the viewport that fits its story without clipping.
  await page.setViewportSize({ width: 1400, height: 1240 });
  await page.goto("/recall");
  await page.getByRole("button", { name: /^Project / }).click();
  await page.getByLabel("Search projects").fill("acme-auth");
  await page.getByRole("option", { name: /acme-auth/ }).click();
  await page.getByRole("radio", { name: "30d" }).check();
  await page.getByLabel("Question", { exact: true }).fill("revoke-on-logout");
  await page.getByRole("button", { name: "Run recall" }).click();
  const handoffCard = page.getByRole("article", { name: HANDOFF });
  await expect(handoffCard).toBeVisible();
  await expect(handoffCard).toContainText("Wire revoke-on-logout against the rotation table");
  await expect(
    page.getByRole("article", { name: "Use JWT access tokens with refresh-token rotation" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await page.locator("body").click({ position: { x: 5, y: 5 } });
  await shoot(page, "recall.png");

  // The recalled card resolves to the exact captured source event.
  await handoffCard.getByRole("link", { name: `Open ${HANDOFF} in Browse` }).click();
  await expect(page).toHaveURL(
    (url) =>
      url.searchParams.get("session") === handoff.sessionId &&
      url.searchParams.get("event") === handoff.eventId,
  );
  await expect(page.locator(`#event-${handoff.eventId}`)).toHaveClass(/event-flow-row--target/);

  // Activity: the project's stream with typed landmarks and folded tool rows.
  await page.setViewportSize({ width: 1400, height: 880 });
  await page.goto(`/?project=${encodeURIComponent(project.projectKey)}`);
  await expect(page.getByRole("heading", { name: "Activity" })).toBeVisible();
  await expect(
    page.getByText("Logout now revokes the whole refresh-token family").first(),
  ).toBeVisible();
  await shoot(page, "hero.png");

  // Trajectory: select the newest burst and read its evidence in the context rail.
  await page.setViewportSize({ width: 1680, height: 1000 });
  await page.goto(`/projects/${encodeURIComponent(project.projectKey)}`);
  const head = page.locator('.project-trajectory [data-node-kind="head"]');
  await expect(head).toBeVisible();
  await expect(
    page.locator('.project-trajectory [data-node-kind="future-ghost"]').first(),
  ).toBeVisible();
  await head.click();
  const detail = page.getByRole("region", { name: "Trajectory detail" });
  await expect(detail).toBeVisible();
  await expect(detail.getByRole("link", { name: /View in Stream/ })).toBeVisible();
  await shoot(page, "trajectory.png");
});
