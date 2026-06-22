import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AgentSession } from "../../lib/api";
import ActivityPage from "../ActivityPage";

let params: { q?: string };
let setParams: SetStoreFunction<{ q?: string }>;

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
    useNavigate: () => navigate,
    useParams: () => ({}),
    useSearchParams: () => [params, setParams],
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
    askStatus: vi.fn(async () => ({
      chat: { enabled: true, available: true },
      elasticsearch: { enabled: true, available: true },
    })),
    getSessionEvents: vi.fn(async () => []),
    search: vi.fn(async (query: string) => ({
      query,
      local: [],
      elastic: [],
      elasticHealth: {},
    })),
    searchValues: vi.fn(async () => []),
  };
});

beforeEach(() => {
  [params, setParams] = createStore<{ q?: string }>({});
  navigate.mockReset();
});

describe("ActivityPage", () => {
  it("combines session browsing and find/ask work into one workspace", async () => {
    render(() => <ActivityPage />);

    expect(screen.getByRole("heading", { name: "Activity" })).toBeInTheDocument();
    const modes = screen.getByRole("tablist", { name: "Activity mode" });
    expect(within(modes).getByRole("tab", { name: "Browse" })).toHaveAttribute("aria-selected", "true");
    expect(within(modes).getByRole("tab", { name: "Find" })).toBeInTheDocument();
    expect(within(modes).getByRole("tab", { name: "Ask" })).toBeInTheDocument();

    const rail = document.querySelector(".session-list-pane") as HTMLElement;
    expect(await within(rail).findByText("Focused session")).toBeInTheDocument();
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
});
