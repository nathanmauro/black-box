export type SeedEventPayload = {
  source: string;
  clientSessionId: string;
  eventType: string;
  role?: string;
  text: string;
  cwd: string;
  metadata: {
    title: string;
    kind?: string;
    decision?: string;
    rationale?: string;
    alternatives?: string[];
    openLoops?: string[];
    confidence?: number;
    repo?: string;
  };
};

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export const E2E_PROJECT_CWD = "/tmp/black-box-e2e";

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
];

export function assertSafeSeedBaseUrl(baseURL: string): void {
  const url = new URL(baseURL);
  const hostname = url.hostname.toLowerCase();
  const port = url.port || (url.protocol === "https:" ? "443" : "80");
  if (!["127.0.0.1", "localhost", "::1"].includes(hostname)) {
    throw new Error(`Refusing to seed non-local Black Box URL: ${baseURL}`);
  }
  if (port === "8766") {
    throw new Error(`Refusing to seed production Black Box port 8766: ${baseURL}`);
  }
}

export async function seedBlackBoxE2e(baseURL: string, fetchImpl: FetchLike = fetch): Promise<void> {
  assertSafeSeedBaseUrl(baseURL);
  const endpoint = new URL("/api/events", baseURL).toString();
  for (const event of E2E_SEED_EVENTS) {
    const response = await fetchImpl(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(event),
    });
    if (!response.ok) {
      throw new Error(`Failed to seed ${event.metadata.title}: HTTP ${response.status} ${response.statusText}`);
    }
  }
}
