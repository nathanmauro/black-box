import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
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
    askStatus: vi.fn(async () => ({
      chat: { enabled: true, available: true },
      elasticsearch: { enabled: true, available: true },
    })),
    getSessionEvents: vi.fn(async () => []),
    getEventFeed: vi.fn(async () => ({
      limit: 100,
      count: 0,
      items: [],
      nextBefore: null,
    })),
    getProjects: vi.fn(async () => [
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
    ]),
    search: vi.fn(async (query: string) => ({
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
    })),
    searchValues: vi.fn(async () => []),
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

  it("restores remembered project when the URL has none", async () => {
    localStorage.setItem("blackbox.activity.projectKey", "sba-key");
    render(() => <ActivityPage />);

    expect(await screen.findByRole("button", { name: /sba-agentic/ })).toBeInTheDocument();
  });
});
