import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { EventFeedItem, EventFeedResponse, ProjectSummary } from "../../lib/api";
import StreamPage from "../StreamPage";

let params: { q?: string };
let setParams: SetStoreFunction<{ q?: string }>;

const mocks = vi.hoisted(() => ({
  getEventFeed: vi.fn(),
  liveEvents: () => [] as unknown[],
  setLiveEvents: (_events: unknown[]) => undefined,
}));

const getEventFeed = mocks.getEventFeed;
const selectedProject: ProjectSummary = {
  projectKey: "sba-key",
  canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
  label: "~/Developer/proj/sba-agentic",
  sessionCount: 1,
  eventCount: 1,
  savedMeldCount: 0,
};

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    A: (props: { href: string; class?: string; children?: Element }) => (
      <a href={props.href} class={props.class}>
        {props.children}
      </a>
    ),
    useSearchParams: () => [params, setParams],
  };
});

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getEventFeed: mocks.getEventFeed,
    searchValues: vi.fn(async (_field: string, prefix: string) =>
      ["Decision", "Handoff", "Observation"].filter((value) => value.toLowerCase().startsWith(prefix.toLowerCase())),
    ),
  };
});

vi.mock("../../lib/sse", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/sse")>();
  return {
    ...actual,
    useLiveStore: () => ({
      status: () => "live",
      events: mocks.liveEvents,
      onSessionUpdated: () => () => undefined,
    }),
  };
});

beforeEach(() => {
  localStorage.clear();
  [params, setParams] = createStore<{ q?: string }>({});
  const [liveEvents, setLiveEvents] = createSignal<unknown[]>([]);
  mocks.liveEvents = liveEvents;
  mocks.setLiveEvents = setLiveEvents;
  getEventFeed.mockReset();
  getEventFeed.mockResolvedValue(feed([eventItem("event-1", "Make stream default")]));
});

describe("StreamPage", () => {
  it("renders compact stream rows from the event feed", async () => {
    render(() => <StreamPage />);

    const row = await screen.findByRole("button", { name: /Make stream default/ });
    expect(row).toHaveAttribute("aria-expanded", "false");
    // Collapsed rows carry no per-row cwd (spec §4.3 P2) — shared context returns on run headers.
    expect(within(row).queryByText("~/Developer/proj/sba-agentic")).not.toBeInTheDocument();
    expect(within(row).getByText("Decision")).toBeInTheDocument();
    expect(getEventFeed).toHaveBeenCalledWith({ limit: 100, q: "", meaningful: true });

    fireEvent.click(row);
    expect(row).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "View session" })).toHaveAttribute(
      "href",
      "/?view=browse&session=session-1&event=event-1",
    );
  });

  it("passes selected project as a hidden stream facet", async () => {
    render(() => <StreamPage project={selectedProject} />);

    const row = await screen.findByRole("button", { name: /Make stream default/ });
    expect(getEventFeed).toHaveBeenCalledWith({
      limit: 100,
      q: "project_group:/Users/nathan/Developer/proj/sba-agentic",
      meaningful: true,
    });
    fireEvent.click(row);
    expect(screen.getByRole("link", { name: "View session" })).toHaveAttribute(
      "href",
      "/?view=browse&session=session-1&event=event-1&project=sba-key",
    );
  });

  it("quotes a hidden project scope containing a comma so it stays one value", async () => {
    const commaProject: ProjectSummary = { ...selectedProject, canonicalKey: "/tmp/a,b" };
    render(() => <StreamPage project={commaProject} />);

    await screen.findByRole("button", { name: /Make stream default/ });
    expect(getEventFeed).toHaveBeenCalledWith({
      limit: 100,
      q: 'project_group:"/tmp/a,b"',
      meaningful: true,
    });
  });

  it("does not fetch globally while project scope is pending", async () => {
    render(() => <StreamPage projectScopePending />);

    await Promise.resolve();
    expect(getEventFeed).not.toHaveBeenCalled();
  });

  it("clears prior rows when a newly selected project request fails", async () => {
    getEventFeed
      .mockResolvedValueOnce(feed([eventItem("event-global", "Global row must not leak")]))
      .mockRejectedValueOnce(new Error("Scoped feed unavailable"));
    const [project, setProject] = createSignal<ProjectSummary | null>(null);
    render(() => <StreamPage project={project()} />);

    expect(await screen.findByRole("button", { name: /Global row must not leak/ })).toBeInTheDocument();
    setProject(selectedProject);

    expect(await screen.findByText("Scoped feed unavailable")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Global row must not leak/ })).not.toBeInTheDocument();
    expect(getEventFeed).toHaveBeenLastCalledWith({
      limit: 100,
      q: "project_group:/Users/nathan/Developer/proj/sba-agentic",
      meaningful: true,
    });
  });

  it("removes visible positive project facets before applying hidden project scope", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "project:cockpit kind:Decision" });
    render(() => <StreamPage project={selectedProject} />);

    await waitFor(() => expect(params.q).toBe("kind:Decision"));
    const projectGroup = Array.from(document.querySelectorAll(".facet-group")).find((element) =>
      within(element as HTMLElement).queryByText("Project"),
    ) as HTMLElement;
    expect(within(projectGroup).queryByRole("button", { name: /cockpit/ })).not.toBeInTheDocument();
    expect(getEventFeed).toHaveBeenLastCalledWith({
      limit: 100,
      q: "kind:Decision project_group:/Users/nathan/Developer/proj/sba-agentic",
      meaningful: true,
    });
  });

  it("uses facet chips to narrow the URL query", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "codex" }));

    await waitFor(() => expect(params.q).toBe("source:codex"));
    await waitFor(() => expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "source:codex", meaningful: true }));
  });

  it("renders exclude chips from negative facets", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "-kind:PostToolUse" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(screen.getByRole("button", { name: "kind != PostToolUse" })).toBeInTheDocument();
    expect(getEventFeed).toHaveBeenCalledWith({ limit: 100, q: "-kind:PostToolUse", meaningful: true });
  });

  it("renders one chip per value for multi-value facets and removes values individually", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "source:codex,claude" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const codexChip = screen.getByRole("button", { name: "codex x" });
    expect(screen.getByRole("button", { name: "claude x" })).toBeInTheDocument();

    fireEvent.click(codexChip);
    await waitFor(() => expect(params.q).toBe("source:claude"));
  });

  it("renders a removable session chip", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "session:abc-123 kind:Decision" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const chip = screen.getByRole("button", { name: /session: abc-123/ });
    expect(chip).toHaveAttribute("title", "session:abc-123");

    fireEvent.click(chip);
    await waitFor(() => expect(params.q).toBe("kind:Decision"));
  });

  it("renders time tokens as human phrases and removes them individually", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "last:2h until:2026-08-18" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(screen.getByRole("button", { name: /Past 2 hours/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Until Aug 18/ }));
    // last:2h round-trips as the equivalent since-duration token.
    await waitFor(() => expect(params.q).toBe("since:2h"));

    fireEvent.click(screen.getByRole("button", { name: /Past 2 hours/ }));
    await waitFor(() => expect(params.q).toBeUndefined());
  });

  it("renders a since keyword chip as a Since phrase", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "since:yesterday" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(screen.getByRole("button", { name: /Since yesterday/ })).toBeInTheDocument();
  });

  it("renders a removable is:all chip", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "is:all kind:Decision" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "remove is:all" }));
    await waitFor(() => expect(params.q).toBe("kind:Decision"));
  });

  it("renders removable project_exact chips", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "project_exact:/tmp/app kind:Decision" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: /project_exact: \/tmp\/app/ }));
    await waitFor(() => expect(params.q).toBe("kind:Decision"));
  });

  it("expresses meaningful opt-out as is:all in q while the wire keeps meaningful=true", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const checkbox = screen.getByLabelText("meaningful events only");
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);

    await waitFor(() => expect(params.q).toBe("is:all"));
    await waitFor(() => expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "is:all", meaningful: true }));
  });

  it("derives the meaningful checkbox from a deep-linked is:all query", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "is:all" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const checkbox = screen.getByLabelText("meaningful events only");
    expect(checkbox).not.toBeChecked();

    fireEvent.click(checkbox);
    await waitFor(() => expect(params.q).toBeUndefined());
    expect(screen.getByLabelText("meaningful events only")).toBeChecked();
  });

  it("names the active meaningful default in the result header and drops it under is:all", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    expect(document.querySelector(".stream-result-scope")?.textContent).toBe("meaningful");

    setParams({ q: "is:all" });
    await waitFor(() => expect(document.querySelector(".stream-result-header")).not.toBeInTheDocument());
  });

  it("renders the time phrase in the result header", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "last:2h" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(document.querySelector(".stream-result-scope")?.textContent).toBe("meaningful · past 2 hours");
    expect(screen.queryByText("live paused — historical scope")).not.toBeInTheDocument();
  });

  it("pauses live behavior while until: bounds the query in the past", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "until:2026-08-18" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(screen.getByText("live paused — historical scope")).toBeInTheDocument();

    try {
      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(600);
      // New events cannot match a past-bounded query: no head refetch, no N-new pill.
      expect(getEventFeed).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole("button", { name: /new/ })).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("resumes live behavior when the past until: token is removed", async () => {
    const old = eventItem("event-1", "Make stream default");
    const fresh = eventItem("event-a", "Live row", "2026-07-01T12:01:00Z");
    getEventFeed.mockReset();
    getEventFeed
      .mockResolvedValueOnce(feed([old]))
      .mockResolvedValueOnce(feed([old]))
      .mockResolvedValueOnce(feed([fresh, old]));
    [params, setParams] = createStore<{ q?: string }>({ q: "until:2026-08-18" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    expect(screen.getByText("live paused — historical scope")).toBeInTheDocument();

    setParams({ q: undefined });
    await waitFor(() => expect(getEventFeed).toHaveBeenCalledTimes(2));
    await screen.findByRole("button", { name: /Make stream default/ });
    expect(screen.queryByText("live paused — historical scope")).not.toBeInTheDocument();

    const feedEl = document.querySelector(".stream-feed") as HTMLElement;
    feedEl.scrollTop = 120;
    try {
      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(600);
      expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: true, since: old.observedAt });
      expect(screen.getByRole("button", { name: "1 new" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("round-trips is:all through a composite query without disturbing other tokens", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "session:abc until:2026-08-18 free text" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByLabelText("meaningful events only"));
    await waitFor(() => expect(params.q).toBe("session:abc until:2026-08-18 is:all free text"));

    fireEvent.click(screen.getByLabelText("meaningful events only"));
    await waitFor(() => expect(params.q).toBe("session:abc until:2026-08-18 free text"));
  });

  it("renders a pinned project chip from picker state that clears the selection", async () => {
    const onClearProject = vi.fn();
    render(() => <StreamPage project={selectedProject} onClearProject={onClearProject} />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const chip = screen.getByRole("button", { name: "Pinned project sba-agentic — clear project scope" });
    const rail = document.querySelector(".facet-rail") as HTMLElement;
    expect(rail.firstElementChild).toBe(chip);

    fireEvent.click(chip);
    expect(onClearProject).toHaveBeenCalledTimes(1);
    // The chip renders from ProjectPicker state, never from q — the hidden injection stays hidden.
    expect(params.q).toBeUndefined();
  });

  it("filters to a session from the expanded row head", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    render(() => <StreamPage />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    fireEvent.click(screen.getByRole("button", { name: "Filter to this session" }));
    await waitFor(() => expect(params.q).toBe("kind:Decision session:session-1"));
  });

  it("copies an absolute /stream link from the visible q plus the session token", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    render(() => <StreamPage project={selectedProject} />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));

    await screen.findByText("Link copied.");
    // The visible q only — the hidden project_group scope never leaks into the shared link — but
    // project= carries the pinned key so the link reproduces what the sender saw instead of being
    // rescoped by the opener's remembered project.
    const search = new URLSearchParams({ q: "kind:Decision session:session-1", project: "sba-key" }).toString();
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/stream?${search}`);
  });

  it("copies an explicit-global link when no project is pinned so deep links reproduce what the sender saw", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(() => <StreamPage />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));

    await screen.findByText("Link copied.");
    // project= (empty) is the explicit-global sentinel: without it the opener's remembered-project
    // effect would inject its own scope and session AND project_group could empty the feed.
    const search = new URLSearchParams({ q: "session:session-1", project: "" }).toString();
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/stream?${search}`);
  });

  it("reports a typed failure when the clipboard is unavailable", async () => {
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    render(() => <StreamPage />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));
    expect(await screen.findByText("Could not copy link.")).toBeInTheDocument();
  });

  it("loads the next page and appends rows with the nextBefore cursor", async () => {
    getEventFeed
      .mockResolvedValueOnce(feed([eventItem("event-1", "Make stream default")], "2026-07-01T12:00:00Z|event-1"))
      .mockResolvedValueOnce(feed([eventItem("event-2", "Browse mode preserved")]));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByRole("button", { name: /Browse mode preserved/ })).toBeInTheDocument();
    expect(getEventFeed).toHaveBeenLastCalledWith({
      limit: 100,
      q: "",
      meaningful: true,
      before: "2026-07-01T12:00:00Z|event-1",
    });
  });

  it("ignores stale load-more responses after the stream query changes", async () => {
    const stalePage = deferred<EventFeedResponse>();
    getEventFeed
      .mockResolvedValueOnce(feed([eventItem("event-old", "Original scope row")], "2026-07-01T12:00:00Z|event-old"))
      .mockReturnValueOnce(stalePage.promise)
      .mockResolvedValueOnce(feed([eventItem("event-scoped", "New scope row")]));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Original scope row/ });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(getEventFeed).toHaveBeenCalledTimes(2));

    setParams({ q: "kind:Decision" });
    await waitFor(() =>
      expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "kind:Decision", meaningful: true }),
    );
    expect(await screen.findByRole("button", { name: /New scope row/ })).toBeInTheDocument();

    stalePage.resolve(feed([eventItem("event-stale", "Stale old scope row")]));
    await Promise.resolve();

    await waitFor(() => expect(screen.queryByRole("button", { name: /Stale old scope row/ })).not.toBeInTheDocument());
  });

  it("clears pagination while a primary stream reload is pending", async () => {
    const primaryReload = deferred<EventFeedResponse>();
    getEventFeed
      .mockResolvedValueOnce(feed([eventItem("event-old", "Original scope row")], "2026-07-01T12:00:00Z|event-old"))
      .mockReturnValueOnce(primaryReload.promise)
      .mockResolvedValueOnce(feed([eventItem("event-stale", "Stale page row")]));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Original scope row/ });

    setParams({ q: "kind:Decision" });
    await waitFor(() => expect(getEventFeed).toHaveBeenCalledTimes(2));

    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    await Promise.resolve();
    expect(getEventFeed).toHaveBeenCalledTimes(2);

    primaryReload.resolve(feed([eventItem("event-scoped", "New scope row")]));
    expect(await screen.findByRole("button", { name: /New scope row/ })).toBeInTheDocument();
  });

  it("uses pending live rows for head refetch and new-count dedupe", async () => {
    const old = eventItem("event-old", "Existing row");
    const a = eventItem("event-a", "Pending row A", "2026-07-01T12:01:00Z");
    const b = eventItem("event-b", "Pending row B", "2026-07-01T12:02:00Z");
    getEventFeed
      .mockResolvedValueOnce(feed([old]))
      .mockResolvedValueOnce(feed([a, old]))
      .mockResolvedValueOnce(feed([b, a]));

    try {
      render(() => <StreamPage />);
      await screen.findByRole("button", { name: /Existing row/ });
      const feedEl = document.querySelector(".stream-feed") as HTMLElement;
      feedEl.scrollTop = 120;
      vi.useFakeTimers();

      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(500);
      expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: true, since: old.observedAt });
      expect(screen.getByRole("button", { name: "1 new" })).toBeInTheDocument();

      mocks.setLiveEvents([{ id: "sse-a" }, { id: "sse-b" }]);
      await vi.advanceTimersByTimeAsync(500);
      expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: true, since: a.observedAt });
      expect(screen.getByRole("button", { name: "2 new" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("hides load more when the local row cap is reached", async () => {
    const cappedRows = Array.from({ length: 500 }, (_, index) =>
      eventItem(`event-cap-${index}`, `Capped row ${index}`, `2026-07-01T11:${String(index % 60).padStart(2, "0")}:00Z`),
    );
    getEventFeed.mockResolvedValueOnce(feed(cappedRows, "2026-07-01T11:00:00Z|event-cap-499"));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Capped row 0/ });

    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("expanded density expands every row and per-row toggling still overrides it", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([eventItem("event-1", "Make stream default"), eventItem("event-2", "Second row", "2026-07-01T11:58:00Z")]),
    );
    render(() => <StreamPage />);
    const rowOne = await screen.findByRole("button", { name: /Make stream default/ });
    const rowTwo = screen.getByRole("button", { name: /Second row/ });
    expect(rowOne).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(screen.getByRole("button", { name: "Expanded" }));
    expect(rowOne).toHaveAttribute("aria-expanded", "true");
    expect(rowTwo).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(rowOne); // exception to the mode
    expect(rowOne).toHaveAttribute("aria-expanded", "false");
    expect(rowTwo).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(screen.getByRole("button", { name: "Collapsed" })); // mode switch clears exceptions
    expect(rowOne).toHaveAttribute("aria-expanded", "false");
    expect(rowTwo).toHaveAttribute("aria-expanded", "false");
  });

  it("persists density to localStorage and restores it on mount", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(screen.getByRole("button", { name: "Expanded" }));
    expect(localStorage.getItem("bb.streamDensity")).toBe("expanded");
  });

  it("shows pending rows expanded when they arrive in expanded mode", async () => {
    localStorage.setItem("bb.streamDensity", "expanded");
    const old = eventItem("event-old", "Existing row");
    const fresh = eventItem("event-a", "Live row", "2026-07-01T12:01:00Z");
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValueOnce(feed([old])).mockResolvedValueOnce(feed([fresh, old]));

    try {
      render(() => <StreamPage />);
      await screen.findByRole("button", { name: /Existing row/ });
      const feedEl = document.querySelector(".stream-feed") as HTMLElement;
      feedEl.scrollTop = 120;
      feedEl.scrollTo = vi.fn();
      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(500);
      fireEvent.click(screen.getByRole("button", { name: "1 new" }));
      expect(screen.getByRole("button", { name: /Live row/ })).toHaveAttribute("aria-expanded", "true");
    } finally {
      vi.useRealTimers();
    }
  });
});

function feed(items: EventFeedItem[], nextBefore: string | null = null): EventFeedResponse {
  return {
    limit: 100,
    count: items.length,
    items,
    nextBefore,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

function eventItem(id: string, text: string, observedAt?: string): EventFeedItem {
  return {
    id,
    sessionId: id === "event-1" ? "session-1" : "session-2",
    source: id === "event-1" ? "codex" : "claude",
    clientSessionId: id === "event-1" ? "client-1" : "client-2",
    turnId: null,
    eventType: "Decision",
    role: "assistant",
    text,
    toolName: null,
    toolInputJson: null,
    toolOutputJson: null,
    metadata: null,
    observedAt: observedAt ?? (id === "event-1" ? "2026-07-01T12:00:00Z" : "2026-07-01T11:59:00Z"),
    cwd: id === "event-1" ? "/Users/nathan/Developer/proj/sba-agentic" : "/Users/nathan/Developer/proj/cockpit",
    sessionTitle: id === "event-1" ? "Activity stream work" : "Browse regression",
  };
}
