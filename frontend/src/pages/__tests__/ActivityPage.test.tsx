import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { ErrorBoundary } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AgentSession } from "../../lib/api";
import ActivityPage from "../ActivityPage";

type ActivitySearchParams = { q?: string; session?: string; view?: string; project?: string; event?: string };

let params: ActivitySearchParams;
let setParams: SetStoreFunction<ActivitySearchParams>;

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

const navigate = vi.fn();
const apiMocks = vi.hoisted(() => ({
  askStatus: vi.fn(),
  getSessionEvents: vi.fn(),
  getEventFeed: vi.fn(),
  getProjects: vi.fn(),
  search: vi.fn(),
  searchValues: vi.fn(),
}));

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    A: (props: { href: string; class?: string; onClick?: (event: MouseEvent) => void; children?: Element }) => (
      <a href={props.href} class={props.class} onClick={props.onClick}>
        {props.children}
      </a>
    ),
    useNavigate: () => navigate,
    useParams: () => ({}),
    useSearchParams: () => [params, setParams],
  };
});

vi.mock("../../lib/stores", () => ({
  createSessionsResource: () => [() => sessions],
  sourceFilter: {
    matches: <T,>(items: T[]) => items,
  },
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    askStatus: apiMocks.askStatus,
    getSessionEvents: apiMocks.getSessionEvents,
    getEventFeed: apiMocks.getEventFeed,
    getProjects: apiMocks.getProjects,
    search: apiMocks.search,
    searchValues: apiMocks.searchValues,
  };
});

vi.mock("../../lib/sse", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/sse")>();
  return {
    ...actual,
    useLiveStore: () => ({
      status: () => "live",
      events: () => [],
      onSessionUpdated: () => () => undefined,
    }),
  };
});

beforeEach(() => {
  [params, setParams] = createStore<ActivitySearchParams>({});
  localStorage.clear();
  navigate.mockReset();
  apiMocks.askStatus.mockReset();
  apiMocks.askStatus.mockResolvedValue({
    chat: { enabled: true, available: true },
    elasticsearch: { enabled: true, available: true },
  });
  apiMocks.getSessionEvents.mockReset();
  apiMocks.getSessionEvents.mockResolvedValue([]);
  apiMocks.getEventFeed.mockReset();
  apiMocks.getEventFeed.mockResolvedValue({
    limit: 100,
    count: 0,
    items: [],
    nextBefore: null,
  });
  apiMocks.getProjects.mockReset();
  apiMocks.getProjects.mockResolvedValue([
    {
      projectKey: "sba-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "~/Developer/proj/sba-agentic",
      sessionCount: 1,
      eventCount: 4,
      savedMeldCount: 0,
      lastSeenAt: "2026-06-22T20:10:00Z",
    },
    {
      projectKey: "cockpit-key",
      canonicalKey: "/Users/nathan/Developer/proj/cockpit",
      label: "~/Developer/proj/cockpit",
      sessionCount: 1,
      eventCount: 8,
      savedMeldCount: 0,
      lastSeenAt: "2026-06-22T19:20:00Z",
    },
  ]);
  apiMocks.search.mockReset();
  apiMocks.search.mockImplementation(async (query: string) => ({
    query,
    local: query
      ? [
          {
            id: "event-frontend-build",
            sessionId: "session-1",
            source: "codex",
            clientSessionId: "client-1",
            eventType: "UserPromptSubmit",
            role: "user",
            text: "Open the focused session from search.",
            cwd: "/Users/nathan/Developer/proj/sba-agentic",
            observedAt: "2026-06-22T20:04:00Z",
          },
        ]
      : [],
    elastic: [],
    elasticHealth: {},
  }));
  apiMocks.searchValues.mockReset();
  apiMocks.searchValues.mockResolvedValue([]);
});

describe("ActivityPage", () => {
  it("combines session browsing and find/ask work into one workspace", async () => {
    render(() => <ActivityPage />);

    expect(screen.getByRole("heading", { name: "Activity" })).toBeInTheDocument();
    const modes = screen.getByRole("tablist", { name: "Activity mode" });
    expect(within(modes).getByRole("tab", { name: "Stream" })).toHaveAttribute("aria-selected", "true");
    expect(within(modes).getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "false");
    expect(within(modes).getByRole("tab", { name: "Find" })).toBeInTheDocument();
    expect(within(modes).getByRole("tab", { name: "Ask" })).toBeInTheDocument();

    fireEvent.click(within(modes).getByRole("tab", { name: "Browse" }));
    expect(within(modes).getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    const detail = document.querySelector(".session-detail-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
    expect(await within(detail).findByRole("heading", { name: "Focused session" })).toBeInTheDocument();

    fireEvent.click(within(rail).getByRole("button", { name: /Cockpit cleanup/ }));
    await waitFor(() => expect(within(detail).getByRole("heading", { name: "Cockpit cleanup" })).toBeInTheDocument());
    expect(navigate).not.toHaveBeenCalled();
    expect(params.session).toBe("session-2");

    fireEvent.input(screen.getByLabelText("Find sessions"), { target: { value: "project:cockpit" } });
    await waitFor(() => expect(within(rail).queryByText("Focused session")).not.toBeInTheDocument());
    expect(within(rail).getByText("Cockpit cleanup")).toBeInTheDocument();

    fireEvent.click(within(modes).getByRole("tab", { name: "Find" }));
    expect(screen.getByLabelText("Search query")).toBeInTheDocument();
    expect(screen.getByText("Source")).toBeInTheDocument();
    expect(screen.getByLabelText("meaningful events only")).toBeInTheDocument();

    fireEvent.click(within(modes).getByRole("tab", { name: "Ask" }));
    expect(await screen.findByPlaceholderText("Ask across the recorded memory…")).toBeInTheDocument();
  });

  it("opens Search results inside the Activity session reader", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({
      q: "focused",
      view: "find",
    });
    render(() => <ActivityPage />);

    expect(await screen.findByLabelText("Search query")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("link", { name: /Open the focused session from search/ }));

    await waitFor(() => expect(params.view).toBe("browse"));
    expect(params.q).toBeUndefined();
    expect(params.session).toBe("session-1");
    expect(params.event).toBe("event-frontend-build");

    const modes = screen.getByRole("tablist", { name: "Activity mode" });
    expect(within(modes).getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("heading", { name: "Focused session" })).toBeInTheDocument();
  });

  it("selects a shared Activity project and stores it in the URL", async () => {
    render(() => <ActivityPage />);

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "sba" } });
    fireEvent.click(await screen.findByRole("option", { name: /sba-agentic/ }));

    await waitFor(() => expect(params.project).toBe("sba-key"));
  });

  it("restores remembered project when the URL has none and clears browse state", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({ session: "session-2", event: "event-old" });
    localStorage.setItem("blackbox.activity.projectKey", "sba-key");
    render(() => <ActivityPage />);

    expect(await screen.findByRole("button", { name: /sba-agentic/ })).toBeInTheDocument();
    await waitFor(() => expect(params.project).toBe("sba-key"));
    expect(params.session).toBeUndefined();
    expect(params.event).toBeUndefined();
  });

  it("clears a stale remembered project when the URL has no project", async () => {
    localStorage.setItem("blackbox.activity.projectKey", "missing-key");
    render(() => <ActivityPage />);

    await waitFor(() => expect(apiMocks.getProjects).toHaveBeenCalled());
    await screen.findByRole("button", { name: /All projects/ });
    expect(localStorage.getItem("blackbox.activity.projectKey")).toBeNull();
    expect(params.project).toBeUndefined();
  });

  it("defers Activity Stream fetches while a URL project is unresolved", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({ project: "sba-key" });
    apiMocks.getProjects.mockImplementation(() => new Promise(() => undefined));

    render(() => <ActivityPage />);

    await waitFor(() => expect(apiMocks.getProjects).toHaveBeenCalled());
    expect(apiMocks.getEventFeed).not.toHaveBeenCalled();
  });

  it("defers Activity Find searches while a URL project is unresolved", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({
      project: "sba-key",
      q: "kind:Decision",
      view: "find",
    });
    apiMocks.getProjects.mockImplementation(() => new Promise(() => undefined));

    render(() => <ActivityPage />);

    expect(await screen.findByLabelText("Search query")).toBeInTheDocument();
    await waitFor(() => expect(apiMocks.getProjects).toHaveBeenCalled());
    expect(apiMocks.search).not.toHaveBeenCalled();
  });

  it("shows a project picker error when project loading fails", async () => {
    apiMocks.getProjects.mockRejectedValue(new Error("Project load failed."));
    render(() => (
      <ErrorBoundary fallback={<p>Project resource crashed.</p>}>
        <ActivityPage />
      </ErrorBoundary>
    ));

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));

    expect(await screen.findByText("Unable to load projects.")).toBeInTheDocument();
  });

  it("clears a stale URL project after projects load and falls back to all projects", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({ project: "missing-key", session: "session-2", event: "event-old" });
    localStorage.setItem("blackbox.activity.projectKey", "missing-key");

    render(() => <ActivityPage />);

    await waitFor(() => expect(params.project).toBeUndefined());
    expect(params.session).toBeUndefined();
    expect(params.event).toBeUndefined();
    expect(localStorage.getItem("blackbox.activity.projectKey")).toBeNull();
    expect(await screen.findByRole("button", { name: /All projects/ })).toBeInTheDocument();
    expect(apiMocks.getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: true });
  });

  it("falls back to all projects when project loading fails with a URL project", async () => {
    [params, setParams] = createStore<ActivitySearchParams>({ project: "sba-key" });
    localStorage.setItem("blackbox.activity.projectKey", "sba-key");
    apiMocks.getProjects.mockRejectedValue(new Error("Project load failed."));

    render(() => (
      <ErrorBoundary fallback={<p>Project resource crashed.</p>}>
        <ActivityPage />
      </ErrorBoundary>
    ));

    await waitFor(() => expect(params.project).toBeUndefined());
    expect(localStorage.getItem("blackbox.activity.projectKey")).toBeNull();
    expect(apiMocks.getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: true });

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    expect(await screen.findByText("Unable to load projects.")).toBeInTheDocument();
  });
});
