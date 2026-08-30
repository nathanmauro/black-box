import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getEvent, getProjectSessions, getSession, getSessionChildCounts, getSessionDag, getSessionEvents, getSessionLinks, getSessionTranscript, getSessions, getTaskDag } from "../../lib/api";
import type { AgentEvent, AgentSession, DagResponse, SessionLinksResponse, SessionTranscriptParams, SessionTranscriptResponse } from "../../lib/api";
import { createSessionsResource, sourceFilter } from "../../lib/stores";
import SessionsPage from "../SessionsPage";

const navigate = vi.fn();
type TendrilSearchParams = { task?: string };
let searchParams: TendrilSearchParams;
let setSearchParams: SetStoreFunction<TendrilSearchParams>;

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
    eventType: "Stop",
    role: "agent",
    text: "I made the reading view calmer.",
    observedAt: "2026-06-22T20:01:00Z",
  },
  {
    id: "evt-user",
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    eventType: "UserPromptSubmit",
    role: "agent",
    text: "Focus the session reader.",
    observedAt: "2026-06-22T20:00:00Z",
  },
];

function transcriptResponse(
  responseEvents: AgentEvent[] = events,
  overrides: Partial<SessionTranscriptResponse> = {},
): SessionTranscriptResponse {
  return {
    sessionId: "session-1",
    available: true,
    complete: true,
    reason: null,
    limit: 100,
    count: responseEvents.length,
    events: responseEvents,
    nextBefore: null,
    ...overrides,
  };
}

const childLinks: SessionLinksResponse = {
  parents: [],
  children: [
    {
      linkId: "link-1",
      parentSessionId: "session-1",
      childSessionId: "child-1",
      linkType: "spawned",
      taskId: null,
      createdAt: "2026-06-22T20:05:00Z",
      session: { id: "child-1", title: "code-reviewer", source: "claude" },
    },
  ],
};

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    useNavigate: () => navigate,
    useParams: () => ({ sessionId: "session-1" }),
    useSearchParams: () => [searchParams, setSearchParams],
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
    getSession: vi.fn(async (id: string) => sessions.find((session) => session.id === id) ?? sessions[0]),
    getEvent: vi.fn(),
    getProjectSessions: vi.fn(async () => [sessions[0]]),
    getSessionEvents: vi.fn(async () => events),
    getSessionTranscript: vi.fn(async () => transcriptResponse()),
    getTaskDag: vi.fn(async () => ({ nodes: [], edges: [] })),
    getSessionDag: vi.fn(async () => ({ nodes: [], edges: [] })),
    getSessionLinks: vi.fn(async () => ({ parents: [], children: [] })),
    getSessionChildCounts: vi.fn(async () => ({})),
  };
});

beforeEach(() => {
  [searchParams, setSearchParams] = createStore<TendrilSearchParams>({});
  navigate.mockReset();
  vi.mocked(createSessionsResource).mockClear();
  vi.mocked(getSessions).mockReset();
  vi.mocked(getSessions).mockResolvedValue(sessions);
  vi.mocked(getSession).mockReset();
  vi.mocked(getSession).mockImplementation(async (id: string) => sessions.find((session) => session.id === id) ?? sessions[0]);
  vi.mocked(getEvent).mockReset();
  vi.mocked(getProjectSessions).mockReset();
  vi.mocked(getProjectSessions).mockResolvedValue([sessions[0]]);
  vi.mocked(getSessionEvents).mockReset();
  vi.mocked(getSessionEvents).mockResolvedValue(events);
  vi.mocked(getSessionTranscript).mockReset();
  vi.mocked(getSessionTranscript).mockImplementation(async (id: string) => transcriptResponse(events, { sessionId: id }));
  vi.mocked(getTaskDag).mockReset();
  vi.mocked(getTaskDag).mockResolvedValue({ nodes: [], edges: [] });
  vi.mocked(getSessionDag).mockReset();
  vi.mocked(getSessionDag).mockResolvedValue({ nodes: [], edges: [] });
  vi.mocked(getSessionLinks).mockReset();
  vi.mocked(getSessionLinks).mockResolvedValue({ parents: [], children: [] });
  vi.mocked(getSessionChildCounts).mockReset();
  vi.mocked(getSessionChildCounts).mockResolvedValue({});
  vi.mocked(sourceFilter.key).mockReset();
  vi.mocked(sourceFilter.key).mockReturnValue("");
  vi.mocked(sourceFilter.matches).mockReset();
  vi.mocked(sourceFilter.matches).mockImplementation(<T extends { source: string }>(items: T[]) => items);
});

describe("SessionsPage", () => {
  it("does not render tendril context without a task query parameter", () => {
    render(() => <SessionsPage />);

    expect(document.querySelector(".tendril-header")).not.toBeInTheDocument();
    expect(getTaskDag).not.toHaveBeenCalled();
  });

  it("renders active task context with an enabled steer box", async () => {
    [searchParams, setSearchParams] = createStore<TendrilSearchParams>({ task: "task-1" });
    vi.mocked(getTaskDag).mockResolvedValue(taskDag("in_progress"));

    render(() => <SessionsPage />);

    const header = document.querySelector(".tendril-header") as HTMLElement;
    expect(await within(header).findByText("Full-auto board runner")).toBeInTheDocument();
    const status = within(header).getByText("In progress");
    expect(status).toHaveClass("tendril-status--in_progress");
    expect(within(header).getByRole("textbox", { name: "Steer this run" })).toBeEnabled();
    expect(within(header).getByRole("button", { name: "Send steer" })).toBeEnabled();
    expect(getTaskDag).toHaveBeenCalledWith("task-1");
  });

  it("disables steering when the task is blocked", async () => {
    [searchParams, setSearchParams] = createStore<TendrilSearchParams>({ task: "task-1" });
    vi.mocked(getTaskDag).mockResolvedValue(taskDag("blocked"));

    render(() => <SessionsPage />);

    const header = document.querySelector(".tendril-header") as HTMLElement;
    expect(await within(header).findByText("Blocked")).toHaveClass("tendril-status--blocked");
    expect(within(header).getByText("Steering is only available while the task is in progress.")).toBeInTheDocument();
    expect(within(header).queryByRole("textbox", { name: "Steer this run" })).not.toBeInTheDocument();
  });

  it("lazily fetches and renders the selected session DAG from the tendril header", async () => {
    [searchParams, setSearchParams] = createStore<TendrilSearchParams>({ task: "task-1" });
    vi.mocked(getTaskDag).mockResolvedValue(taskDag("in_progress"));
    vi.mocked(getSessionDag).mockResolvedValue({
      nodes: [
        { id: "task-1", type: "task", label: "Implement card detail", status: "in_progress", ref: "/tasks/task-1" },
        { id: "session:session-1", type: "session", label: "Worker tendril 1", ref: "session-1" },
      ],
      edges: [{ from: "task-1", to: "session:session-1", type: "worker_session" }],
    });

    render(() => <SessionsPage />);

    expect(getSessionDag).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "View session DAG" }));
    await waitFor(() => expect(getSessionDag).toHaveBeenCalledWith("session-1"));
    expect(await screen.findByText("Worker tendril 1", { selector: ".dag-label" })).toBeInTheDocument();
    expect(document.querySelector('[data-node-id="session:session-1"]')).toHaveClass("dag-node--current");
  });

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

  it("renders prompts, agent responses, and tools by default with memory events opt-in", async () => {
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
    const conversationOutline = screen.getByRole("navigation", { name: "Conversation outline" });
    expect(conversationOutline).toBeInTheDocument();
    expect(within(conversationOutline).getByRole("link", { name: "Turn 1: Focus the session reader." })).toHaveAttribute(
      "href",
      "#prompt-evt-user",
    );
    expect(within(promptTurns[0] as HTMLElement).getByText("You")).toBeInTheDocument();
    expect(within(promptTurns[0] as HTMLElement).getByText("Codex")).toBeInTheDocument();
    expect(within(promptTurns[0] as HTMLElement).getByText("agent response")).toBeInTheDocument();

    expect(screen.queryByText("Use the calmer session layout")).not.toBeInTheDocument();
    expect(screen.queryByText("Reader should keep memory cards behind a layer toggle.")).not.toBeInTheDocument();
    expect(await screen.findByText(/hidden-tool-output/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Show tool events" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("checkbox", { name: "Show memory events" }));
    expect(await screen.findByText("Use the calmer session layout")).toBeInTheDocument();
    expect(screen.getByText("Reader should keep memory cards behind a layer toggle.")).toBeInTheDocument();
    expect(screen.getByText(/hidden-tool-output/)).toBeInTheDocument();
  });

  it("searches message and tool fields, retains complete matching turns, and navigates matches", async () => {
    const searchableEvents: AgentEvent[] = [
      {
        id: "evt-answer-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "assistant",
        text: "The second turn stays independent.",
        observedAt: "2026-06-22T20:06:00Z",
      },
      {
        id: "evt-tool-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "tool_result",
        role: "tool",
        toolName: "Bash",
        toolInputJson: '{"command":"npm test"}',
        toolOutputJson: '{"output":"42 tests passed"}',
        observedAt: "2026-06-22T20:05:30Z",
      },
      {
        id: "evt-prompt-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "user",
        text: "Run the checks.",
        observedAt: "2026-06-22T20:05:00Z",
      },
      {
        id: "evt-answer-1",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "assistant",
        text: "This full answer remains visible for turn context.",
        observedAt: "2026-06-22T20:02:00Z",
      },
      {
        id: "evt-tool-1",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "tool_call",
        role: "tool",
        toolName: "Read",
        toolInputJson: '{"file_path":"frontend/vite.config.ts"}',
        toolOutputJson: '{"content":"defineConfig"}',
        observedAt: "2026-06-22T20:01:30Z",
      },
      {
        id: "evt-prompt-1",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "user",
        text: "Inspect the frontend config.",
        observedAt: "2026-06-22T20:01:00Z",
      },
    ];
    vi.mocked(getSessionTranscript).mockImplementation(async (
      id: string,
      params: SessionTranscriptParams = {},
    ) => {
      if (params.q === 'read "vite.config"') {
        return transcriptResponse([searchableEvents[4]], { sessionId: id, count: 1 });
      }
      if (params.q === "42 tests passed") {
        return transcriptResponse([searchableEvents[1]], { sessionId: id, count: 1 });
      }
      return transcriptResponse(searchableEvents, { sessionId: id });
    });
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: scrollIntoView,
    });

    render(() => <SessionsPage />);

    const search = await screen.findByRole("searchbox", { name: "Find in session" });
    await waitFor(() => expect(document.querySelectorAll(".prompt-turn")).toHaveLength(2));
    fireEvent.input(search, { target: { value: 'read "vite.config"' } });

    expect(await screen.findByText("1 matching turn")).toBeInTheDocument();
    expect(getSessionTranscript).toHaveBeenCalledWith("session-1", {
      limit: 100,
      q: 'read "vite.config"',
    });
    expect(document.querySelectorAll(".prompt-turn")).toHaveLength(1);
    expect(screen.getByText("Inspect the frontend config.")).toBeInTheDocument();
    expect(screen.getByText("This full answer remains visible for turn context.")).toBeInTheDocument();
    expect(screen.queryByText("Run the checks.")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Next transcript match" }));
    await waitFor(() => expect(scrollIntoView).toHaveBeenLastCalledWith({ block: "center", behavior: "smooth" }));
    expect(document.getElementById("prompt-evt-prompt-1")).toHaveClass("prompt-turn--search-active");
    expect(screen.getByText("1 of 1 matching turn")).toBeInTheDocument();

    fireEvent.input(search, { target: { value: "42 tests passed" } });
    expect(await screen.findByText("Run the checks.")).toBeInTheDocument();
    expect(screen.getByText("The second turn stays independent.")).toBeInTheDocument();
    expect(screen.queryByText("Inspect the frontend config.")).not.toBeInTheDocument();

    fireEvent.keyDown(search, { key: "Enter" });
    expect(document.getElementById("prompt-evt-prompt-2")).toHaveClass("prompt-turn--search-active");
    fireEvent.keyDown(search, { key: "Escape" });
    expect(search).toHaveValue("");
    expect(await screen.findByText("Inspect the frontend config.")).toBeInTheDocument();
    expect(document.querySelectorAll(".prompt-turn")).toHaveLength(2);
  });

  it("groups event-name variants, deduplicates repeated responses, and navigates with dock proximity", async () => {
    const variantEvents: AgentEvent[] = [
      {
        id: "evt-blank-stop",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "Stop",
        role: "agent",
        text: "   ",
        observedAt: "2026-06-22T20:06:00Z",
      },
      {
        id: "evt-session-end",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "SessionEnd",
        role: "agent",
        text: "Lifecycle metadata should stay hidden.",
        observedAt: "2026-06-22T20:05:00Z",
      },
      {
        id: "evt-stop-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "Stop",
        role: "agent",
        text: "Second captured response.",
        observedAt: "2026-06-22T20:04:00Z",
      },
      {
        id: "evt-response-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "assistant",
        text: "Second captured response.",
        observedAt: "2026-06-22T20:03:59Z",
      },
      {
        id: "evt-user-2",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "user_prompt_submit",
        role: "agent",
        text: "Show me the second exchange.",
        observedAt: "2026-06-22T20:03:00Z",
      },
      {
        id: "evt-stop-1",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "Stop",
        role: "agent",
        text: "First captured response.",
        observedAt: "2026-06-22T20:02:00Z",
      },
      {
        id: "evt-user-1-archive",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "user",
        text: "  SHOW me the first   exchange. ",
        observedAt: "2026-06-22T20:01:01Z",
      },
      {
        id: "evt-user-1",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "beforeSubmitPrompt",
        role: "agent",
        text: "Show me the first exchange.",
        observedAt: "2026-06-22T20:01:00Z",
      },
      {
        id: "evt-session-start",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "SessionStart",
        role: "agent",
        text: "Startup metadata should stay hidden.",
        observedAt: "2026-06-22T20:00:00Z",
      },
    ];
    vi.mocked(getSessionTranscript).mockResolvedValue(transcriptResponse(variantEvents));
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
      configurable: true,
      value: scrollIntoView,
    });

    render(() => <SessionsPage />);

    await waitFor(() => expect(document.querySelectorAll(".prompt-turn")).toHaveLength(2));
    const turns = document.querySelectorAll(".prompt-turn");
    expect(within(turns[0] as HTMLElement).getByText("Show me the first exchange.")).toBeInTheDocument();
    expect(within(turns[0] as HTMLElement).queryByText(/SHOW me the first/)).not.toBeInTheDocument();
    expect(within(turns[0] as HTMLElement).getByText("First captured response.")).toBeInTheDocument();
    expect(within(turns[1] as HTMLElement).getByText("Show me the second exchange.")).toBeInTheDocument();
    expect(within(turns[1] as HTMLElement).getAllByText("Second captured response.")).toHaveLength(1);
    expect(screen.queryByText("Lifecycle metadata should stay hidden.")).not.toBeInTheDocument();
    expect(screen.queryByText("Startup metadata should stay hidden.")).not.toBeInTheDocument();

    const outline = screen.getByRole("navigation", { name: "Conversation outline" });
    const first = within(outline).getByRole("link", { name: "Turn 1: Show me the first exchange." });
    const second = within(outline).getByRole("link", { name: "Turn 2: Show me the second exchange." });
    expect(first).toHaveAttribute("href", "#prompt-evt-user-1");
    expect(second).toHaveAttribute("href", "#prompt-evt-user-2");

    fireEvent.mouseEnter(second);
    const markers = outline.querySelectorAll(".conversation-navigator-item");
    expect(markers[0]).toHaveAttribute("data-distance", "1");
    expect(markers[1]).toHaveAttribute("data-distance", "0");
    expect(markers[1]).toHaveAttribute("data-preview", "true");
    expect(within(outline).getByText("Codex response")).toBeInTheDocument();

    first.focus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    expect(second).toHaveFocus();
    fireEvent.click(second);
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "start", behavior: "smooth" });
    expect(second).toHaveAttribute("aria-current", "location");
  });

  it("uses a flat session rail scoped to the selected project", async () => {
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
    expect(getProjectSessions).toHaveBeenCalledWith("sba-key", 120);
    expect(createSessionsResource).not.toHaveBeenCalled();
    expect(getSessions).not.toHaveBeenCalled();
  });

  it("hydrates an exact session that is outside the recent session rail", async () => {
    const oldSession: AgentSession = {
      id: "session-old",
      source: "codex",
      clientSessionId: "client-old",
      title: "Older exact session",
      cwd: "/Users/nathan/Developer/proj/sba-agentic",
      summary: "Loaded directly by id.",
      startedAt: "2026-05-01T20:00:00Z",
      lastSeenAt: "2026-05-01T20:10:00Z",
      eventCount: 3,
    };
    vi.mocked(getSessions).mockResolvedValue([sessions[0]]);
    vi.mocked(getSession).mockResolvedValue(oldSession);

    render(() => <SessionsPage selectedSessionId="session-old" defaultToFirst />);

    expect(await screen.findByRole("heading", { name: "Older exact session" })).toBeInTheDocument();
    expect(getSession).toHaveBeenCalledWith("session-old");
    expect(getSessionTranscript).toHaveBeenCalledWith("session-old", { limit: 100, q: undefined });
    expect(getSessionEvents).not.toHaveBeenCalled();
  });

  it("loads older transcript pages by cursor without a client-side event cap", async () => {
    const olderPage: AgentEvent[] = [
      {
        id: "evt-older-response",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "assistant",
        text: "An older response loaded on demand.",
        observedAt: "2026-06-22T19:01:00Z",
      },
      {
        id: "evt-older-prompt",
        sessionId: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        eventType: "response_item",
        role: "user",
        text: "Load the older exchange.",
        observedAt: "2026-06-22T19:00:00Z",
      },
    ];
    vi.mocked(getSessionTranscript).mockImplementation(async (id: string, params: SessionTranscriptParams = {}) => (
      params.before
        ? transcriptResponse(olderPage, { sessionId: id, nextBefore: null })
        : transcriptResponse(events, {
            sessionId: id,
            nextBefore: "2026-06-22T20:00:00Z|evt-user",
          })
    ));

    render(() => <SessionsPage />);

    const loadOlder = await screen.findByRole("button", { name: "Load older events" });
    expect(screen.getByText(`${events.length} loaded`)).toBeInTheDocument();
    fireEvent.click(loadOlder);

    expect(await screen.findByText("Load the older exchange.")).toBeInTheDocument();
    expect(screen.getByText("An older response loaded on demand.")).toBeInTheDocument();
    expect(getSessionTranscript).toHaveBeenCalledWith("session-1", {
      limit: 100,
      before: "2026-06-22T20:00:00Z|evt-user",
      q: undefined,
    });
    expect(screen.queryByRole("button", { name: "Load older events" })).not.toBeInTheDocument();
  });

  it("uses recorded events returned with an unavailable transcript without falling back", async () => {
    vi.mocked(getSessionTranscript).mockResolvedValue(transcriptResponse(events, {
      available: false,
      complete: false,
      reason: "missing",
    }));

    render(() => <SessionsPage />);

    expect(await screen.findByText("I made the reading view calmer.")).toBeInTheDocument();
    expect(document.querySelector(".session-transcript-status")).toHaveTextContent(
      "Source transcript unavailable; showing recorded events (missing).",
    );
    expect(getSessionEvents).not.toHaveBeenCalled();
  });

  it("degrades to the legacy recorded-event endpoint only when the transcript request fails", async () => {
    vi.mocked(getSessionTranscript).mockRejectedValue(new Error("route unavailable"));
    vi.mocked(getSessionEvents).mockResolvedValue(events);

    render(() => <SessionsPage />);

    expect(await screen.findByText("I made the reading view calmer.")).toBeInTheDocument();
    expect(getSessionEvents).toHaveBeenCalledWith("session-1", 2_000);
    expect(document.querySelector(".session-transcript-status")).toHaveTextContent(
      "Full transcript service could not be reached; showing recorded events.",
    );
  });

  it("clears previous project sessions while the next project is loading", async () => {
    const [project, setProject] = createSignal({
      projectKey: "project-a",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "~/Developer/proj/sba-agentic",
      sessionCount: 1,
      eventCount: 4,
      savedMeldCount: 0,
    });
    vi.mocked(getProjectSessions).mockImplementation((projectKey: string) => {
      if (projectKey === "project-a") return Promise.resolve([sessions[0]]);
      return new Promise<AgentSession[]>(() => undefined);
    });

    render(() => <SessionsPage project={project()} defaultToFirst />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    await waitFor(() => expect(getSessionTranscript).toHaveBeenCalledWith("session-1", { limit: 100, q: undefined }));
    vi.mocked(getSessionTranscript).mockClear();

    setProject({
      projectKey: "project-b",
      canonicalKey: "/Users/nathan/Developer/proj/cockpit",
      label: "~/Developer/proj/cockpit",
      sessionCount: 1,
      eventCount: 8,
      savedMeldCount: 0,
    });

    await waitFor(() => expect(getProjectSessions).toHaveBeenCalledWith("project-b", 120));
    expect(within(rail).queryByText("Focused session")).not.toBeInTheDocument();
    expect(getSessionTranscript).not.toHaveBeenCalled();
  });

  it("applies source filtering to project-scoped sessions", async () => {
    vi.mocked(getProjectSessions).mockResolvedValue(sessions);
    vi.mocked(sourceFilter.matches).mockImplementation(<T extends { source: string }>(items: T[]) =>
      items.filter((item) => item.source === "codex"),
    );

    render(() => (
      <SessionsPage
        project={{
          projectKey: "sba-key",
          canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
          label: "~/Developer/proj/sba-agentic",
          sessionCount: 2,
          eventCount: 12,
          savedMeldCount: 0,
        }}
        defaultToFirst
      />
    ));

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    await waitFor(() => expect(sourceFilter.matches).toHaveBeenCalledWith(sessions));
    expect(within(rail).queryByText("Cockpit cleanup")).not.toBeInTheDocument();
  });

  it("falls back from a stale selected session to the first project-scoped session", async () => {
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
    expect(getProjectSessions).toHaveBeenCalledWith("sba-key", 120);
    await waitFor(() => expect(getSessionTranscript).toHaveBeenCalledWith("session-1", { limit: 100, q: undefined }));
    expect(getSessionTranscript).not.toHaveBeenCalledWith("session-2", expect.anything());
  });

  it("reveals and highlights a target event", async () => {
    render(() => <SessionsPage selectedSessionId="session-1" targetEventId="evt-decision" />);

    await screen.findAllByText("Use the calmer session layout");
    const row = document.getElementById("event-evt-decision");
    expect(row).toHaveClass("event-flow-row--target");
    expect(getEvent).not.toHaveBeenCalled();
  });

  it("hydrates and highlights an exact target outside the capped session event batch", async () => {
    const olderTarget: AgentEvent = {
      id: "evt-older-decision",
      sessionId: "session-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "Decision",
      role: "assistant",
      text: "Keep the exact older decision reachable",
      metadata: { decision: "Keep the exact older decision reachable" },
      observedAt: "2026-05-01T20:00:00Z",
    };
    vi.mocked(getSessionTranscript).mockResolvedValue(transcriptResponse(events.filter((event) => event.id !== olderTarget.id)));
    vi.mocked(getEvent).mockResolvedValue(olderTarget);

    render(() => <SessionsPage selectedSessionId="session-1" targetEventId={olderTarget.id} />);

    expect(await screen.findByText("Keep the exact older decision reachable")).toBeInTheDocument();
    expect(document.getElementById(`event-${olderTarget.id}`)).toHaveClass("event-flow-row--target");
    expect(getSessionTranscript).toHaveBeenCalledWith("session-1", { limit: 100, q: undefined });
    expect(getEvent).toHaveBeenCalledOnce();
    expect(getEvent).toHaveBeenCalledWith(olderTarget.id);
  });

  it("does not merge an exact target that belongs to another session", async () => {
    vi.mocked(getEvent).mockResolvedValue({
      ...events[1],
      id: "evt-other-session",
      sessionId: "session-2",
      text: "Wrong owning session",
    });

    render(() => <SessionsPage selectedSessionId="session-1" targetEventId="evt-other-session" />);

    await waitFor(() => expect(getEvent).toHaveBeenCalledWith("evt-other-session"));
    expect(screen.queryByText("Wrong owning session")).not.toBeInTheDocument();
    expect(document.getElementById("event-evt-other-session")).not.toBeInTheDocument();
  });

  it("keeps the session rail rendered when the batch child-count request is rejected", async () => {
    vi.mocked(getSessionChildCounts).mockRejectedValue(new Error("request-uri too large"));

    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();
    expect(within(rail).queryByRole("button", { name: /subagent sessions/ })).not.toBeInTheDocument();
  });

  it("renders an expander with the batch child count for parent sessions", async () => {
    vi.mocked(getSessionChildCounts).mockResolvedValue({ "session-1": 2 });

    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByRole("button", { name: "Toggle 2 subagent sessions" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(getSessionChildCounts).toHaveBeenCalledWith(["session-1", "session-2"]);
    expect(getSessions).toHaveBeenCalledWith(120);
    const cockpitRow = within(rail).getByText("Cockpit cleanup").closest(".session-row-block") as HTMLElement;
    expect(within(cockpitRow).queryByRole("button", { name: /subagent sessions/ })).not.toBeInTheDocument();
    expect(getSessionLinks).not.toHaveBeenCalled();
  });

  it("lazy-loads child rows with agent type badges on expand and collapses locally", async () => {
    vi.mocked(getSessionChildCounts).mockResolvedValue({ "session-1": 1 });
    vi.mocked(getSessionLinks).mockResolvedValue(childLinks);

    render(() => <SessionsPage />);

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    const expander = await within(rail).findByRole("button", { name: "Toggle 1 subagent sessions" });
    expect(getSessionLinks).not.toHaveBeenCalled();

    fireEvent.click(expander);
    await waitFor(() => expect(getSessionLinks).toHaveBeenCalledWith("session-1"));
    expect(await within(rail).findByText("code-reviewer", { selector: ".agent-type-badge" })).toBeInTheDocument();
    expect(expander).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(within(rail).getByText("code-reviewer", { selector: ".session-row--child strong" }));
    expect(navigate).toHaveBeenCalledWith("/sessions/child-1");

    fireEvent.click(expander);
    expect(within(rail).queryByText("code-reviewer", { selector: ".agent-type-badge" })).not.toBeInTheDocument();
  });

  it("shows the compact lineage rail and opens its horizontal relationship map", async () => {
    vi.mocked(getSessionDag).mockResolvedValue({
      nodes: [
        { id: "session:session-1", type: "session", label: "Focused session", ref: "session-1" },
        { id: "session:child-1", type: "session", label: "code-reviewer", ref: "child-1" },
      ],
      edges: [{ from: "session:session-1", to: "session:child-1", type: "spawned" }],
    });

    render(() => <SessionsPage />);

    expect(await screen.findByRole("navigation", { name: "Agent lineage" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Current agent: Focused session" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Subagent: code-reviewer" })).toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Expand lineage map" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(screen.getByRole("region", { name: "Agent lineage map" })).toBeInTheDocument();
    expect(document.querySelector(".dag-stage--lineage")).toBeInTheDocument();
    const lineage = document.querySelector(".session-lineage") as HTMLElement;
    expect(lineage).toBeInTheDocument();
    expect(lineage.querySelector('[data-node-id="session:session-1"]')).toHaveClass("dag-node--current");
    expect(getSessionDag).toHaveBeenCalledWith("session-1");
    expect(document.querySelector(".tendril-header")).not.toBeInTheDocument();
  });

  it("keeps the lineage dock hidden when the DAG has no related agent session", async () => {
    vi.mocked(getSessionDag).mockResolvedValue({
      nodes: [
        { id: "task-1", type: "task", label: "Task context", ref: "task-1" },
        { id: "session:session-1", type: "session", label: "Focused session", ref: "session-1" },
      ],
      edges: [{ from: "task-1", to: "session:session-1", type: "worker_session" }],
    });

    render(() => <SessionsPage />);

    await waitFor(() => expect(getSessionDag).toHaveBeenCalledWith("session-1"));
    expect(document.querySelector(".session-lineage")).not.toBeInTheDocument();
  });
});

function taskDag(status: string): DagResponse {
  return {
    nodes: [
      { id: "spec-1", type: "spec", label: "Full-auto board runner", ref: "/specs/spec-1" },
      { id: "task-1", type: "task", label: "Implement card detail", status, ref: "/tasks/task-1" },
    ],
    edges: [{ from: "spec-1", to: "task-1", type: "has_task" }],
  };
}
