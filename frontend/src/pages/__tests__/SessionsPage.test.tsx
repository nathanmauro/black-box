import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { AgentEvent, AgentSession } from "../../lib/api";
import SessionsPage from "../SessionsPage";

const navigate = vi.fn();

const sessions: AgentSession[] = [
  {
    id: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    title: "Focused session",
    cwd: "/Users/nathan/Developer/proj/sba-agentic",
    summary: "A concise summary.",
    startedAt: "2026-06-22T20:00:00Z",
    lastSeenAt: "2026-06-22T20:10:00Z",
    eventCount: 4,
  },
];

const events: AgentEvent[] = [
  {
    id: "evt-tool",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "PostToolUse",
    role: "tool",
    toolName: "Read",
    toolInputJson: '{"file_path":"/Users/nathan/Developer/proj/sba-agentic/src/hidden-tool-output.ts"}',
    toolOutputJson: '{"ok":true}',
    observedAt: "2026-06-22T20:03:00Z",
  },
  {
    id: "evt-decision",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "Decision",
    text: "Use the calmer session layout",
    metadata: {
      decision: "Use the calmer session layout",
      rationale: "The default view should prioritize reading over tool telemetry.",
      confidence: 0.84,
    },
    observedAt: "2026-06-22T20:02:00Z",
  },
  {
    id: "evt-assistant",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "AssistantMessage",
    role: "assistant",
    text: "I made the reading view calmer.",
    observedAt: "2026-06-22T20:01:00Z",
  },
  {
    id: "evt-user",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "UserPromptSubmit",
    role: "user",
    text: "Focus the session reader.",
    observedAt: "2026-06-22T20:00:00Z",
  },
];

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    useNavigate: () => navigate,
    useParams: () => ({ sessionId: "session-1" }),
  };
});

vi.mock("@tanstack/solid-virtual", () => ({
  createVirtualizer: (options: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => options.count * options.estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: options.count }, (_, index) => ({
        index,
        start: index * options.estimateSize(),
      })),
  }),
}));

vi.mock("../../lib/stores", () => ({
  createSessionsResource: () => [() => sessions],
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getSessionEvents: vi.fn(async () => events),
  };
});

describe("SessionsPage", () => {
  it("defaults to a reading-focused detail without outline or tool-event noise", async () => {
    render(() => <SessionsPage />);

    expect(await screen.findByRole("heading", { name: "Focused session" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText("Focus the session reader.")).not.toHaveLength(0));
    expect(screen.getAllByText("I made the reading view calmer.")).not.toHaveLength(0);
    expect(screen.getByText("Use the calmer session layout")).toBeInTheDocument();

    expect(document.querySelector(".outline-pane")).not.toBeInTheDocument();
    expect(screen.queryByText(/hidden-tool-output/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: "Show tool events" }));
    await waitFor(() => expect(screen.getAllByText(/hidden-tool-output/)).not.toHaveLength(0));
    expect(screen.getByText("tool payload").closest("details")).not.toHaveAttribute("open");
  });
});
