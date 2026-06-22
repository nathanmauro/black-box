import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
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
  {
    id: "session-2",
    source: "claude",
    clientSessionId: "client-2",
    title: "Cockpit cleanup",
    cwd: "/Users/nathan/Developer/proj/cockpit",
    summary: null,
    startedAt: "2026-06-22T19:00:00Z",
    lastSeenAt: "2026-06-22T19:20:00Z",
    eventCount: 8,
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
    role: "assistant",
    text: "Use the calmer session layout",
    metadata: {
      decision: "Use the calmer session layout",
      rationale: "The default view should prioritize reading over tool telemetry.",
      confidence: 0.84,
    },
    observedAt: "2026-06-22T20:02:00Z",
  },
  {
    id: "evt-observation",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "Observation",
    role: "assistant",
    text: "Reader should keep memory cards behind a layer toggle.",
    observedAt: "2026-06-22T20:01:30Z",
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
  it("filters the session rail by text, project, and source facets", async () => {
    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();
    expect(rail.querySelector(".virtual-spacer")).not.toBeInTheDocument();

    fireEvent.input(screen.getByLabelText("Find sessions"), { target: { value: "project:cockpit" } });
    await waitFor(() => expect(within(rail).queryByText("Focused session")).not.toBeInTheDocument());
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();

    fireEvent.input(screen.getByLabelText("Find sessions"), { target: { value: "source:codex focused" } });
    await waitFor(() => expect(within(rail).queryByText("Cockpit cleanup")).not.toBeInTheDocument());
    expect(within(rail).getByText("Focused session")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear session filters" }));
    expect(await within(rail).findByText("Cockpit cleanup")).toBeInTheDocument();
  });

  it("defaults to a prompt-focused reader with memory events opt-in and tools hidden", async () => {
    render(() => <SessionsPage />);

    expect(await screen.findByRole("heading", { name: "Focused session" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText("Focus the session reader.")).not.toHaveLength(0));
    expect(screen.getAllByText("I made the reading view calmer.")).not.toHaveLength(0);

    expect(document.querySelector(".outline-pane")).not.toBeInTheDocument();
    expect(document.querySelector(".timeline-pane .virtual-spacer")).not.toBeInTheDocument();
    const promptOutline = document.querySelector(".prompt-outline");
    expect(promptOutline).toBeInTheDocument();
    expect(within(promptOutline as HTMLElement).getByText("Focus the session reader.")).toBeInTheDocument();

    expect(screen.queryByText("Use the calmer session layout")).not.toBeInTheDocument();
    expect(screen.queryByText("Reader should keep memory cards behind a layer toggle.")).not.toBeInTheDocument();
    expect(screen.queryByText(/hidden-tool-output/)).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Show tool events" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: "Show memory events" }));
    expect(await screen.findByText("Use the calmer session layout")).toBeInTheDocument();
    expect(screen.getByText("Reader should keep memory cards behind a layer toggle.")).toBeInTheDocument();
    expect(screen.queryByText(/hidden-tool-output/)).not.toBeInTheDocument();
  });
});
