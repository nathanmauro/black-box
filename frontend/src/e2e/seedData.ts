export type SeedEventPayload = {
  source: string;
  clientSessionId: string;
  eventType: string;
  role?: string;
  toolName?: string;
  text: string;
  cwd: string;
  metadata: {
    title: string;
    kind?: string;
    decision?: string;
    rationale?: string;
    alternatives?: string[];
    openLoops?: string[];
    nextAction?: string;
    confidence?: number;
    repo?: string;
  };
};

export type SeedProjectionPayload = {
  source: string;
  clientSessionId: string;
  repo: string;
  basis: string;
  paths: Array<{
    title: string;
    description?: string;
    confidence?: number;
  }>;
};

export type SeedIdeaPayload = {
  source: string;
  clientSessionId: string;
  repo: string;
  title: string;
  oneLiner: string;
  origin: "human-aside" | "agent-proposed" | "joint";
  quote?: string;
  legs?: number;
  status?: "untouched" | "partially-built" | "built-unused" | "superseded" | "tracked";
  connects?: string[];
  resumeStep?: string;
  link?: string;
};

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

type SeedProject = {
  projectKey: string;
  canonicalKey: string;
};

type SeedSession = {
  id: string;
};

export const E2E_PROJECT_CWD = "/tmp/black-box-e2e";

export const E2E_HUMAN_ASIDE = "quick tangent: what if the stream led with what I actually said";
export const E2E_MACHINE_PROMPT =
  "<task-notification>\n<task-id>x</task-id>\n<status>completed</status>\n</task-notification>";

export const E2E_SEED_EVENTS: SeedEventPayload[] = [
  {
    source: "codex",
    clientSessionId: "black-box-e2e-codex-ui-rewrite",
    eventType: "Decision",
    role: "assistant",
    text:
      "Use SolidJS + Vite for the UI rewrite\n\n" +
      "Why: Matches agent-observatory; stays self-contained in the jar at runtime\n\n" +
      "Open loops: keep the reproducible gate documented",
    cwd: E2E_PROJECT_CWD,
    metadata: {
      title: "UI rewrite kickoff",
      kind: "decision",
      decision: "Use SolidJS + Vite for the UI rewrite",
      rationale: "Matches agent-observatory; stays self-contained in the jar at runtime",
      alternatives: ["Keep the retired vanilla-JS overhaul", "Serve a separate frontend process"],
      openLoops: ["keep the reproducible gate documented"],
      confidence: 0.92,
      repo: E2E_PROJECT_CWD,
    },
  },
  {
    source: "codex",
    clientSessionId: "black-box-e2e-codex-frontend-build",
    eventType: "Observation",
    role: "assistant",
    text: "Frontend build completed for the self-contained SolidJS jar.",
    cwd: E2E_PROJECT_CWD,
    metadata: {
      title: "Frontend build",
      kind: "observation",
      repo: E2E_PROJECT_CWD,
    },
  },
  {
    source: "claude",
    clientSessionId: "black-box-e2e-claude-design-prompt",
    eventType: "UserPromptSubmit",
    role: "user",
    text: "Rewrite the UI to match agent-observatory",
    cwd: E2E_PROJECT_CWD,
    metadata: {
      title: "Claude design prompt",
      repo: E2E_PROJECT_CWD,
    },
  },
  {
    source: "codex",
    clientSessionId: "black-box-e2e-codex-release-worktree",
    eventType: "Handoff",
    role: "assistant",
    text: "Release worktree verification is complete. Next action: review the catalog-backed workspace.",
    cwd: `${E2E_PROJECT_CWD}/.worktrees/release`,
    metadata: {
      title: "Release worktree handoff",
      kind: "handoff",
      nextAction: "Review the catalog-backed workspace",
      repo: `${E2E_PROJECT_CWD}/.worktrees/release`,
    },
  },
  // Human-turn slice: one session with a real human aside, a machine-injected prompt, and a tool
  // event. human-turns.spec.ts asserts the aside survives the "My turns" filter and the others
  // do not.
  {
    source: "claude",
    clientSessionId: "black-box-e2e-claude-human-turns",
    eventType: "UserPromptSubmit",
    role: "user",
    text: E2E_HUMAN_ASIDE,
    cwd: E2E_PROJECT_CWD,
    metadata: { title: "Human aside", repo: E2E_PROJECT_CWD },
  },
  {
    source: "claude",
    clientSessionId: "black-box-e2e-claude-human-turns",
    eventType: "UserPromptSubmit",
    role: "user",
    text: E2E_MACHINE_PROMPT,
    cwd: E2E_PROJECT_CWD,
    metadata: { title: "Machine notification", repo: E2E_PROJECT_CWD },
  },
  {
    source: "claude",
    clientSessionId: "black-box-e2e-claude-human-turns",
    eventType: "PostToolUse",
    role: "tool",
    toolName: "Read",
    text: "Read src/human-turns-fixture.ts",
    cwd: E2E_PROJECT_CWD,
    metadata: { title: "Human turns tool event", repo: E2E_PROJECT_CWD },
  },
];

export const E2E_SEED_PROJECTION: SeedProjectionPayload = {
  source: "codex",
  clientSessionId: "black-box-e2e-codex-projection",
  repo: E2E_PROJECT_CWD,
  basis: "The graph smoke test needs one deterministic ghost set for the seeded project.",
  paths: [
    {
      title: "Polish trajectory graph",
      description: "Refine default graph affordances after e2e coverage lands.",
      confidence: 0.74,
    },
    {
      title: "Expand projection recall",
      description: "Use captured projection paths in follow-on agent handoffs.",
      confidence: 0.58,
    },
    {
      title: "Retire parked graph page",
      description: "Decide separately whether the legacy /graph surface still earns a slot.",
      confidence: 0.31,
    },
  ],
};

// ideas.spec.ts seeds and asserts these two (see seedE2eIdeas): the agent-proposed untouched idea
// lands in the pinned "Nobody answered these" section and the human-aside idea survives an origin
// filter.
export const E2E_SEED_IDEAS: SeedIdeaPayload[] = [
  {
    source: "claude",
    clientSessionId: "black-box-e2e-claude-idea-agent",
    repo: E2E_PROJECT_CWD,
    title: "Tangent router for human asides",
    oneLiner: "Detect asides in the human-turn stream and file them as ideas.",
    origin: "agent-proposed",
    quote: "what if the stream led with what I actually said",
    legs: 7,
    status: "untouched",
    connects: ["human-turn-first", "idea kind"],
    resumeStep: "Sketch the aside classifier over the seeded human turns",
  },
  {
    source: "codex",
    clientSessionId: "black-box-e2e-codex-idea-human",
    repo: E2E_PROJECT_CWD,
    title: "Evidence capture kind",
    oneLiner: "Record evidence separately from observations.",
    origin: "human-aside",
    legs: 4,
    status: "tracked",
    link: "https://example.com/issues/evidence-kind",
  },
];

export function assertSafeSeedBaseUrl(baseURL: string): void {
  const url = new URL(baseURL);
  const hostname = url.hostname.toLowerCase();
  const port = url.port || (url.protocol === "https:" ? "443" : "80");
  if (!["127.0.0.1", "localhost", "::1"].includes(hostname)) {
    throw new Error(`Refusing to seed non-local Black Box URL: ${baseURL}`);
  }
  if (port !== "8799") {
    throw new Error(`Refusing to seed outside isolated Black Box port 8799: ${baseURL}`);
  }
}

export async function seedBlackBoxE2e(
  baseURL: string,
  fetchImpl: FetchLike = fetch,
): Promise<void> {
  assertSafeSeedBaseUrl(baseURL);
  const endpoint = new URL("/api/events", baseURL).toString();
  for (const event of E2E_SEED_EVENTS) {
    const response = await fetchImpl(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(event),
    });
    if (!response.ok) {
      throw new Error(
        `Failed to seed ${event.metadata.title}: HTTP ${response.status} ${response.statusText}`,
      );
    }
  }
  const projectionResponse = await fetchImpl(new URL("/api/projections", baseURL).toString(), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(E2E_SEED_PROJECTION),
  });
  await requireOk(projectionResponse, "seed the trajectory projection");

  const projectsResponse = await fetchImpl(new URL("/api/projects", baseURL).toString(), {
    headers: { accept: "application/json" },
  });
  await requireOk(projectsResponse, "load the seeded project catalog");
  const projects = (await projectsResponse.json()) as SeedProject[];
  const project = projects.find((candidate) => candidate.canonicalKey === E2E_PROJECT_CWD);
  if (!project) throw new Error(`Seeded project was not grouped under ${E2E_PROJECT_CWD}`);

  const sessionsUrl = new URL(
    `/api/projects/${encodeURIComponent(project.projectKey)}/sessions`,
    baseURL,
  );
  sessionsUrl.searchParams.set("limit", "20");
  const sessionsResponse = await fetchImpl(sessionsUrl.toString(), {
    headers: { accept: "application/json" },
  });
  await requireOk(sessionsResponse, "load seeded project sessions");
  const sessions = (await sessionsResponse.json()) as SeedSession[];
  if (!sessions.length)
    throw new Error("Seeded project did not expose any sessions for meld provenance");

  const meldResponse = await fetchImpl(new URL("/api/melds", baseURL).toString(), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      projectKey: project.projectKey,
      title: "Release workspace synthesis",
      body: "The release worktree is grouped with the primary project while every source session keeps its raw path.",
      provider: "fixture",
      model: "deterministic",
      promptVersion: "e2e-v1",
      executionMode: "export_bundle",
      savedFromPreview: false,
      sessionIds: sessions.map((session) => session.id),
      metadata: { fixture: true },
    }),
  });
  await requireOk(meldResponse, "save the seeded read-only meld");
}

// Seeded by ideas.spec.ts itself rather than global setup, so a jar without POST /api/ideas fails
// only the ideas spec instead of aborting every spec in the suite. Capturing an idea twice (a
// retried worker) only adds a revision to the same ideaKey, so reseeding is safe.
export async function seedE2eIdeas(baseURL: string, fetchImpl: FetchLike = fetch): Promise<void> {
  assertSafeSeedBaseUrl(baseURL);
  for (const idea of E2E_SEED_IDEAS) {
    const ideaResponse = await fetchImpl(new URL("/api/ideas", baseURL).toString(), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(idea),
    });
    await requireOk(ideaResponse, `seed the idea "${idea.title}"`);
  }
}

async function requireOk(response: Response, action: string): Promise<void> {
  if (!response.ok)
    throw new Error(`Failed to ${action}: HTTP ${response.status} ${response.statusText}`);
}
