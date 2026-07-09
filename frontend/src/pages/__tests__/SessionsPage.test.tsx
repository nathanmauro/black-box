import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { getProjectSessions, getSessionEvents, getSessions } from "../../lib/api";
import type { AgentEvent, AgentSession } from "../../lib/api";
import { createSessionsResource } from "../../lib/stores";
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
  createSessionsResource: vi.fn(() => [() => sessions]),
  sourceFilter: {
    key: vi.fn(() => ""),
    matches: vi.fn(<T,>(items: T[]) => items),
  },
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getSessions: vi.fn(async () => sessions),
    getProjectSessions: vi.fn(async () => [sessions[0]]),
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

  it("applies negative source and project facets to the session rail", async () => {
    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();

    fireEvent.input(screen.getByLabelText("Find sessions"), { target: { value: "-source:codex" } });
    await waitFor(() => expect(within(rail).queryByText("Focused session")).not.toBeInTheDocument());
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();

    fireEvent.input(screen.getByLabelText("Find sessions"), { target: { value: "NOT project:cockpit" } });
    await waitFor(() => expect(within(rail).queryByText("Cockpit cleanup")).not.toBeInTheDocument());
    expect(within(rail).getByText("Focused session")).toBeInTheDocument();
  });

  it("defaults to a prompt-focused reader with memory events opt-in and tools hidden", async () => {
    render(() => <SessionsPage />);

    expect(await screen.findByRole("heading", { name: "Focused session" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText("Focus the session reader.")).not.toHaveLength(0));
    expect(screen.getAllByText("I made the reading view calmer.")).not.toHaveLength(0);

    const promptTurns = document.querySelectorAll(".prompt-turn");
    expect(promptTurns).toHaveLength(1);
    expect(promptTurns[0]).toHaveAttribute("id", "prompt-evt-user");
    expect(within(promptTurns[0] as HTMLElement).getAllByText("Focus the session reader.")).not.toHaveLength(0);
    expect(within(promptTurns[0] as HTMLElement).getAllByText("I made the reading view calmer.")).not.toHaveLength(0);

    expect(document.querySelector(".outline-pane")).not.toBeInTheDocument();
    expect(document.querySelector(".timeline-pane .virtual-spacer")).not.toBeInTheDocument();
    const promptOutline = document.querySelector(".prompt-outline");
    expect(promptOutline).toBeInTheDocument();
    expect(within(promptOutline as HTMLElement).getByText("Focus the session reader.").closest("a")).toHaveAttribute(
      "href",
      "#prompt-evt-user",
    );

    expect(screen.queryByText("Use the calmer session layout")).not.toBeInTheDocument();
    expect(screen.queryByText("Reader should keep memory cards behind a layer toggle.")).not.toBeInTheDocument();
    expect(screen.queryByText(/hidden-tool-output/)).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Show tool events" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: "Show memory events" }));
    expect(await screen.findByText("Use the calmer session layout")).toBeInTheDocument();
    expect(screen.getByText("Reader should keep memory cards behind a layer toggle.")).toBeInTheDocument();
    expect(screen.queryByText(/hidden-tool-output/)).not.toBeInTheDocument();
  });

  it("uses a flat session rail scoped to the selected project", async () => {
    vi.mocked(createSessionsResource).mockClear();
    vi.mocked(getSessions).mockClear();
    vi.mocked(getProjectSessions).mockClear();

    render(() => (
      <SessionsPage
        project={{
          projectKey: "sba-key",
          canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
          label: "~/Developer/proj/sba-agentic",
          sessionCount: 1,
          eventCount: 4,
          savedMeldCount: 0,
        }}
        defaultToFirst
      />
    ));

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    expect(within(rail).queryByText("Cockpit cleanup")).not.toBeInTheDocument();
    expect(rail.querySelector(".session-group")).not.toBeInTheDocument();
    expect(getProjectSessions).toHaveBeenCalledWith("sba-key", 2_000);
    expect(createSessionsResource).not.toHaveBeenCalled();
    expect(getSessions).not.toHaveBeenCalled();
  });

  it("falls back from a stale selected session to the first project-scoped session", async () => {
    vi.mocked(getProjectSessions).mockClear();
    vi.mocked(getSessionEvents).mockClear();

    render(() => (
      <SessionsPage
        selectedSessionId="session-2"
        project={{
          projectKey: "sba-key",
          canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
          label: "~/Developer/proj/sba-agentic",
          sessionCount: 1,
          eventCount: 4,
          savedMeldCount: 0,
        }}
        defaultToFirst
      />
    ));

    expect(await screen.findByRole("heading", { name: "Focused session" })).toBeInTheDocument();
    expect(getProjectSessions).toHaveBeenCalledWith("sba-key", 2_000);
    await waitFor(() => expect(getSessionEvents).toHaveBeenCalledWith("session-1", 2_000));
    expect(getSessionEvents).not.toHaveBeenCalledWith("session-2", expect.anything());
  });

  it("reveals and highlights a target event", async () => {
    render(() => <SessionsPage selectedSessionId="session-1" targetEventId="evt-decision" />);

    const target = await screen.findByText("Use the calmer session layout");
    const row = target.closest(".event-flow-row");
    expect(row).toHaveClass("event-flow-row--target");
  });
});
