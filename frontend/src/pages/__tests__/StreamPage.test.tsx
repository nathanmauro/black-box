import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  EventFacetCounts,
  EventFeedItem,
  EventFeedResponse,
  ProjectSummary,
} from "../../lib/api";
import StreamPage from "../StreamPage";

let params: { q?: string };
let setParams: SetStoreFunction<{ q?: string }>;

const mocks = vi.hoisted(() => ({
  getEventFeed: vi.fn(),
  getEventFacets: vi.fn(),
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
    getEventFacets: mocks.getEventFacets,
    searchValues: vi.fn(async (_field: string, prefix: string) =>
      ["Decision", "Handoff", "Observation"].filter((value) =>
        value.toLowerCase().startsWith(prefix.toLowerCase()),
      ),
    ),
    getSessions: vi.fn(async () => [
      {
        id: "session-1",
        source: "codex",
        clientSessionId: "client-abc",
        title: "Stream work",
        startedAt: "",
        lastSeenAt: "",
        eventCount: 3,
      },
      {
        id: "session-2",
        source: "claude",
        clientSessionId: "client-xyz",
        title: "Browse fix",
        startedAt: "",
        lastSeenAt: "",
        eventCount: 2,
      },
    ]),
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
  mocks.getEventFacets.mockReset();
  // Default to the degraded envelope: counts omitted, so every pre-slice-8 expectation about the
  // result header holds unchanged unless a test opts into real counts.
  mocks.getEventFacets.mockResolvedValue({ total: null, fields: null, reason: "backfill" });
});

describe("StreamPage", () => {
  it("renders compact stream rows from the event feed", async () => {
    render(() => <StreamPage />);

    const row = await screen.findByRole("button", { name: /Make stream default/ });
    expect(row).toHaveAttribute("aria-expanded", "false");
    // Collapsed rows carry no per-row cwd (spec §4.3 P2) — shared context lives on run headers.
    expect(within(row).queryByText("~/Developer/proj/sba-agentic")).not.toBeInTheDocument();
    expect(within(row).getByText("Decision")).toBeInTheDocument();
    expect(getEventFeed).toHaveBeenCalledWith({ limit: 100, q: "", meaningful: true });
    // Session-level link on the run header (spec §4.1) — no event position.
    expect(screen.getByRole("link", { name: "View session" })).toHaveAttribute(
      "href",
      "/?view=browse&session=session-1",
    );

    fireEvent.click(row);
    expect(row).toHaveAttribute("aria-expanded", "true");
    // Per-event position link on the expanded card (spec §4.3/§9).
    expect(screen.getByRole("link", { name: "Open at this event" })).toHaveAttribute(
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
    expect(screen.getByRole("link", { name: "Open at this event" })).toHaveAttribute(
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

    expect(
      await screen.findByRole("button", { name: /Global row must not leak/ }),
    ).toBeInTheDocument();
    setProject(selectedProject);

    expect(await screen.findByText("Scoped feed unavailable")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Global row must not leak/ }),
    ).not.toBeInTheDocument();
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
    // The stripped project facet leaves no chip anywhere — and no standing Project group exists.
    expect(screen.queryByRole("button", { name: /cockpit/ })).not.toBeInTheDocument();
    expect(screen.queryByText("Project")).not.toBeInTheDocument();
    expect(getEventFeed).toHaveBeenLastCalledWith({
      limit: 100,
      q: "kind:Decision project_group:/Users/nathan/Developer/proj/sba-agentic",
      meaningful: true,
    });
  });

  it("narrows the URL query by picking a quick value from the popover", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    // No standing QUICK_VALUES rail (spec §4.6): no codex chip, no chip rail at all.
    expect(screen.queryByRole("button", { name: "codex" })).not.toBeInTheDocument();
    expect(document.querySelector(".facet-rail")).not.toBeInTheDocument();

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "source:" } });
    const option = await screen.findByRole("option", { name: "codex" });
    fireEvent.click(option);
    expect(input).toHaveValue("source:codex ");

    fireEvent.submit(document.querySelector(".stream-filter-bar") as HTMLFormElement);
    await waitFor(() => expect(params.q).toBe("source:codex"));
    await waitFor(() =>
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "source:codex",
        meaningful: true,
      }),
    );
  });

  it("renders exclude chips from negative facets", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "-kind:PostToolUse" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(screen.getByRole("button", { name: "kind != PostToolUse" })).toBeInTheDocument();
    expect(getEventFeed).toHaveBeenCalledWith({
      limit: 100,
      q: "-kind:PostToolUse",
      meaningful: true,
    });
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
    [params, setParams] = createStore<{ q?: string }>({
      q: "project_exact:/tmp/app kind:Decision",
    });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: /project_exact: \/tmp\/app/ }));
    await waitFor(() => expect(params.q).toBe("kind:Decision"));
  });

  it("expresses meaningful opt-out as is:all in q while the wire keeps meaningful=true", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    openOptions();
    const checkbox = screen.getByLabelText("meaningful events only");
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);

    await waitFor(() => expect(params.q).toBe("is:all"));
    await waitFor(() =>
      expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "is:all", meaningful: true }),
    );
  });

  it("derives the meaningful checkbox from a deep-linked is:all query", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "is:all" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    openOptions();
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
    await waitFor(() =>
      expect(document.querySelector(".stream-result-header")).not.toBeInTheDocument(),
    );
  });

  it("renders the time phrase in the result header", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "last:2h" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(document.querySelector(".stream-result-scope")?.textContent).toBe(
      "meaningful · past 2 hours",
    );
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
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "",
        meaningful: true,
        since: old.observedAt,
      });
      expect(screen.getByRole("button", { name: "1 new" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("round-trips is:all through a composite query without disturbing other tokens", async () => {
    [params, setParams] = createStore<{ q?: string }>({
      q: "session:abc until:2026-08-18 free text",
    });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    openOptions();
    fireEvent.click(screen.getByLabelText("meaningful events only"));
    await waitFor(() => expect(params.q).toBe("session:abc until:2026-08-18 is:all free text"));

    fireEvent.click(screen.getByLabelText("meaningful events only"));
    await waitFor(() => expect(params.q).toBe("session:abc until:2026-08-18 free text"));
  });

  it("renders a pinned project chip from picker state that clears the selection", async () => {
    const onClearProject = vi.fn();
    render(() => <StreamPage project={selectedProject} onClearProject={onClearProject} />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const chip = screen.getByRole("button", {
      name: "Pinned project sba-agentic — clear project scope",
    });
    const rail = document.querySelector(".facet-rail") as HTMLElement;
    expect(rail.firstElementChild).toBe(chip);

    fireEvent.click(chip);
    expect(onClearProject).toHaveBeenCalledTimes(1);
    // The chip renders from ProjectPicker state, never from q — the hidden injection stays hidden.
    expect(params.q).toBeUndefined();
  });

  it("filters to a session from the run header", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Filter to this session" }));
    await waitFor(() => expect(params.q).toBe("kind:Decision session:session-1"));
  });

  it("copies an absolute /stream link from the visible q plus the session token", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    render(() => <StreamPage project={selectedProject} />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));

    await screen.findByText("Link copied.");
    // The visible q only — the hidden project_group scope never leaks into the shared link — but
    // project= carries the pinned key so the link reproduces what the sender saw instead of being
    // rescoped by the opener's remembered project.
    const search = new URLSearchParams({
      q: "kind:Decision session:session-1",
      project: "sba-key",
    }).toString();
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/stream?${search}`);
  });

  it("copies an explicit-global link when no project is pinned so deep links reproduce what the sender saw", async () => {
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

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
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Copy link" }));
    expect(await screen.findByText("Could not copy link.")).toBeInTheDocument();
  });

  it("loads the next page and appends rows with the nextBefore cursor", async () => {
    getEventFeed
      .mockResolvedValueOnce(
        feed([eventItem("event-1", "Make stream default")], "2026-07-01T12:00:00Z|event-1"),
      )
      .mockResolvedValueOnce(feed([eventItem("event-2", "Browse mode preserved")]));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(
      await screen.findByRole("button", { name: /Browse mode preserved/ }),
    ).toBeInTheDocument();
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
      .mockResolvedValueOnce(
        feed([eventItem("event-old", "Original scope row")], "2026-07-01T12:00:00Z|event-old"),
      )
      .mockReturnValueOnce(stalePage.promise)
      .mockResolvedValueOnce(feed([eventItem("event-scoped", "New scope row")]));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Original scope row/ });

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    await waitFor(() => expect(getEventFeed).toHaveBeenCalledTimes(2));

    setParams({ q: "kind:Decision" });
    await waitFor(() =>
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "kind:Decision",
        meaningful: true,
      }),
    );
    expect(await screen.findByRole("button", { name: /New scope row/ })).toBeInTheDocument();

    stalePage.resolve(feed([eventItem("event-stale", "Stale old scope row")]));
    await Promise.resolve();

    await waitFor(() =>
      expect(screen.queryByRole("button", { name: /Stale old scope row/ })).not.toBeInTheDocument(),
    );
  });

  it("clears pagination while a primary stream reload is pending", async () => {
    const primaryReload = deferred<EventFeedResponse>();
    getEventFeed
      .mockResolvedValueOnce(
        feed([eventItem("event-old", "Original scope row")], "2026-07-01T12:00:00Z|event-old"),
      )
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
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "",
        meaningful: true,
        since: old.observedAt,
      });
      expect(screen.getByRole("button", { name: "1 new" })).toBeInTheDocument();

      mocks.setLiveEvents([{ id: "sse-a" }, { id: "sse-b" }]);
      await vi.advanceTimersByTimeAsync(500);
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "",
        meaningful: true,
        since: a.observedAt,
      });
      expect(screen.getByRole("button", { name: "2 new" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("replaces load more with the honest endcap when the local row cap is reached", async () => {
    const cappedRows = Array.from({ length: 500 }, (_, index) =>
      eventItem(
        `event-cap-${index}`,
        `Capped row ${index}`,
        `2026-07-01T11:${String(index % 60).padStart(2, "0")}:00Z`,
      ),
    );
    getEventFeed.mockResolvedValueOnce(feed(cappedRows, "2026-07-01T11:00:00Z|event-cap-499"));

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Capped row 0/ });

    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    expect(
      screen.getByText("500 of many shown — refine the filter to go deeper."),
    ).toBeInTheDocument();
  });

  it("shows no endcap below the cap", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    expect(document.querySelector(".stream-endcap")).not.toBeInTheDocument();
  });

  it("expanded density expands every row and per-row toggling still overrides it", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        eventItem("event-1", "Make stream default"),
        eventItem("event-2", "Second row", "2026-07-01T11:58:00Z"),
      ]),
    );
    render(() => <StreamPage />);
    const rowOne = await screen.findByRole("button", { name: /Make stream default/ });
    const rowTwo = screen.getByRole("button", { name: /Second row/ });
    expect(rowOne).toHaveAttribute("aria-expanded", "false");

    openOptions();
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
    openOptions();
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
      expect(screen.getByRole("button", { name: /Live row/ })).toHaveAttribute(
        "aria-expanded",
        "true",
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it("renders run headers with session context and a compact variant for short runs", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    // The run wrapper is a generic div (§4.6: no region landmark between feed and articles);
    // the header itself is an article labeled by session.
    const wrapper = document.querySelector(".stream-run") as HTMLElement;
    expect(wrapper.tagName).toBe("DIV");
    expect(wrapper).not.toHaveAttribute("aria-label");
    const head = wrapper.querySelector(".stream-run-head") as HTMLElement;
    expect(head.tagName).toBe("ARTICLE");
    expect(head).toHaveAttribute("aria-label", "Session Activity stream work");
    expect(head.querySelector(".source-dot")).toBeInTheDocument();
    expect(within(head).getByText("Activity stream work")).toBeInTheDocument();
    expect(within(head).getByText("~/Developer/proj/sba-agentic")).toBeInTheDocument();
    expect(within(head).getByText("1 event")).toBeInTheDocument();
    // A 1-event run gets the compact inline variant, never sticky (spec §4.1).
    expect(head.classList.contains("stream-run-head--compact")).toBe(true);
    expect(head.classList.contains("stream-run-head--sticky")).toBe(false);
  });

  it("renders the sticky header variant for runs of three or more events", async () => {
    const sameSession = { sessionId: "session-1", source: "codex", clientSessionId: "client-1" };
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        eventItem("event-1", "Row one", "2026-07-01T12:00:00Z"),
        eventItem("event-2", "Row two", "2026-07-01T11:58:00Z", sameSession),
        eventItem("event-3", "Row three", "2026-07-01T11:56:00Z", sameSession),
      ]),
    );
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Row one/ });

    const heads = document.querySelectorAll(".stream-run-head");
    expect(heads).toHaveLength(1);
    expect((heads[0] as HTMLElement).classList.contains("stream-run-head--sticky")).toBe(true);
    expect(within(heads[0] as HTMLElement).getByText("3 events")).toBeInTheDocument();
  });

  it("shows a row's own cwd inline only when it differs from its run's", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        eventItem("event-1", "Same cwd row", "2026-07-01T12:00:00Z"),
        eventItem("event-2", "Worktree row", "2026-07-01T11:58:00Z", {
          sessionId: "session-1",
          source: "codex",
          clientSessionId: "client-1",
          cwd: "/Users/nathan/Developer/proj/sba-agentic-worktree",
        }),
      ]),
    );
    render(() => <StreamPage />);
    const sameRow = await screen.findByRole("button", { name: /Same cwd row/ });
    const exceptionRow = screen.getByRole("button", { name: /Worktree row/ });

    expect(sameRow.querySelector(".stream-row-cwd")).not.toBeInTheDocument();
    expect(exceptionRow.querySelector(".stream-row-cwd")).toHaveTextContent(
      "~/Developer/proj/sba-agentic-worktree",
    );
  });

  it("renders a dateline with the quiet gap phrasing between date-crossing runs", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        eventItem("event-1", "Newer day row", localIso(4, 12)),
        eventItem("event-2", "Older day row", localIso(1, 12)),
      ]),
    );
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Newer day row/ });

    const daybreak = document.querySelector(".stream-daybreak") as HTMLElement;
    expect(daybreak).toHaveTextContent("Wed Jul 1");
    // No visible filter → the world was quiet (spec §4.2, P4).
    expect(daybreak).toHaveTextContent("quiet 3d");
  });

  it("flips the gap phrasing to no-matches when a visible filter is active", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        eventItem("event-1", "Newer day row", localIso(4, 12)),
        eventItem("event-2", "Older day row", localIso(1, 12)),
      ]),
    );
    [params, setParams] = createStore<{ q?: string }>({ q: "source:codex" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Newer day row/ });

    const daybreak = document.querySelector(".stream-daybreak") as HTMLElement;
    expect(daybreak).toHaveTextContent("no matches for 3d");
    expect(daybreak).not.toHaveTextContent("quiet");
  });

  it("keeps context-zone actions on run headers, not expanded cards", async () => {
    render(() => <StreamPage />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });

    const head = document.querySelector(".stream-run-head") as HTMLElement;
    expect(
      within(head).getByRole("button", { name: "Filter to this session" }),
    ).toBeInTheDocument();
    expect(within(head).getByRole("button", { name: "Copy link" })).toBeInTheDocument();

    fireEvent.click(row);
    const card = document.querySelector(".stream-row-expanded") as HTMLElement;
    expect(
      within(card).queryByRole("button", { name: "Filter to this session" }),
    ).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Copy link" })).not.toBeInTheDocument();
    // The card keeps only the per-event position link; the session title moved to the header.
    expect(within(card).getByRole("link", { name: "Open at this event" })).toBeInTheDocument();
    expect(within(card).queryByText("Activity stream work")).not.toBeInTheDocument();
  });

  it("folds a same-tool chatter streak into one honest row that keeps a swallowed failure loud", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        chatterItem("event-1", "npm test", "2026-07-01T12:03:00Z"),
        chatterItem("event-2", "npm run build", "2026-07-01T12:02:00Z"),
        chatterItem("event-3", "false", "2026-07-01T12:01:00Z", {
          toolOutputJson: '{"exit_code":1,"output":"boom"}',
        }),
        chatterItem("event-4", "pwd", "2026-07-01T12:00:00Z"),
      ]),
    );
    render(() => <StreamPage />);
    await waitFor(() => expect(document.querySelector(".stream-fold")).toBeInTheDocument());

    const fold = document.querySelector(".stream-fold") as HTMLElement;
    expect(fold).toHaveAttribute("aria-expanded", "false");
    expect(fold.textContent).toContain("Bash ×4");
    expect(fold.textContent).toContain("npm test, npm run build, +2 more");
    expect(fold.textContent).toContain("over 3m");
    // The fold swallowed a failure: the mark stays red (P4/D3).
    expect(fold.querySelector(".kind-mark--error")).toBeInTheDocument();
    // The run header still counts raw events.
    expect(screen.getByText("4 events")).toBeInTheDocument();
  });

  it("unfolds in place and moves focus to the first revealed row", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        chatterItem("event-1", "npm test", "2026-07-01T12:03:00Z"),
        chatterItem("event-2", "npm run build", "2026-07-01T12:02:00Z"),
        chatterItem("event-3", "ls", "2026-07-01T12:01:00Z"),
        chatterItem("event-4", "pwd", "2026-07-01T12:00:00Z"),
      ]),
    );
    render(() => <StreamPage />);
    await waitFor(() => expect(document.querySelector(".stream-fold")).toBeInTheDocument());

    fireEvent.click(document.querySelector(".stream-fold") as HTMLElement);

    expect(document.querySelector(".stream-fold")).not.toBeInTheDocument();
    const revealed = document.querySelector('button[data-event-id="event-1"]') as HTMLButtonElement;
    expect(revealed).toBeInTheDocument();
    expect(document.activeElement).toBe(revealed);
  });

  it("keeps an unfolded streak open when an SSE prepend extends its newest edge", async () => {
    const base = [
      chatterItem("event-1", "npm test", "2026-07-01T12:03:00Z"),
      chatterItem("event-2", "npm run build", "2026-07-01T12:02:00Z"),
      chatterItem("event-3", "ls", "2026-07-01T12:01:00Z"),
      chatterItem("event-4", "pwd", "2026-07-01T12:00:00Z"),
    ];
    const fresh = chatterItem("event-0", "git status", "2026-07-01T12:04:00Z");
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValueOnce(feed(base)).mockResolvedValueOnce(feed([fresh, ...base]));

    try {
      render(() => <StreamPage />);
      await waitFor(() => expect(document.querySelector(".stream-fold")).toBeInTheDocument());
      fireEvent.click(document.querySelector(".stream-fold") as HTMLElement);

      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(600);

      // The oldest-member key is unchanged, so the extended streak stays unfolded (spec §4.4).
      expect(document.querySelector('button[data-event-id="event-0"]')).toBeInTheDocument();
      expect(document.querySelector(".stream-fold")).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("removes folds entirely in global expanded mode — Expanded means expanded", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([
        chatterItem("event-1", "npm test", "2026-07-01T12:03:00Z"),
        chatterItem("event-2", "npm run build", "2026-07-01T12:02:00Z"),
        chatterItem("event-3", "ls", "2026-07-01T12:01:00Z"),
        chatterItem("event-4", "pwd", "2026-07-01T12:00:00Z"),
      ]),
    );
    render(() => <StreamPage />);
    await waitFor(() => expect(document.querySelector(".stream-fold")).toBeInTheDocument());

    openOptions();
    fireEvent.click(screen.getByRole("button", { name: "Expanded" }));

    expect(document.querySelector(".stream-fold")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".stream-row-wrap--expanded")).toHaveLength(4);
  });

  it("suggests static quick values for an empty kind: prefix and supports keyboard selection", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "kind:" } });
    // PostToolUse is in the static QUICK_VALUES but not the live searchValues mock — its
    // presence proves the empty-prefix path serves the static list (spec §4.6).
    await screen.findByRole("option", { name: "PostToolUse" });
    expect(input).toHaveAttribute("aria-expanded", "true");

    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(input).toHaveAttribute("aria-activedescendant", "stream-suggest-option-0");
    expect(screen.getByRole("option", { name: "Decision" })).toHaveAttribute(
      "aria-selected",
      "true",
    );

    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(input).toHaveAttribute("aria-activedescendant", "stream-suggest-option-0");

    fireEvent.keyDown(input, { key: "Enter" });
    expect(input).toHaveValue("kind:Decision ");
  });

  it("suggests recent sessions for the session: token", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "session:xyz" } });
    const option = await screen.findByRole("option", { name: "client-xyz" });
    expect(screen.queryByRole("option", { name: "client-abc" })).not.toBeInTheDocument();

    fireEvent.click(option);
    expect(input).toHaveValue("session:client-xyz ");
  });

  it("closes the popover on Escape without accepting", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "kind:" } });
    await screen.findByRole("option", { name: "Decision" });

    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByRole("option", { name: "Decision" })).not.toBeInTheDocument();
    expect(input).toHaveAttribute("aria-expanded", "false");
    expect(input).toHaveValue("kind:");
  });

  it("holds density and meaningful controls behind a collapsed Options disclosure", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const trigger = screen.getByRole("button", { name: "Options" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByLabelText("meaningful events only")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Stream density" })).not.toBeInTheDocument();

    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByLabelText("meaningful events only")).toBeChecked();
    expect(screen.getByRole("group", { name: "Stream density" })).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.queryByLabelText("meaningful events only")).not.toBeInTheDocument();
  });

  it("announces pending merges through the persistent live region", async () => {
    const old = eventItem("event-old", "Existing row");
    const fresh = eventItem("event-a", "Pending row A", "2026-07-01T12:01:00Z");
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValueOnce(feed([old])).mockResolvedValueOnce(feed([fresh, old]));

    try {
      render(() => <StreamPage />);
      await screen.findByRole("button", { name: /Existing row/ });
      const region = document.querySelector('[aria-live="polite"].visually-hidden') as HTMLElement;
      // The region exists before anything is pending — it must never mount conditionally.
      expect(region).toBeInTheDocument();
      expect(region.textContent).toBe("");

      const feedEl = document.querySelector(".stream-feed") as HTMLElement;
      feedEl.scrollTop = 120;
      vi.useFakeTimers();
      mocks.setLiveEvents([{ id: "sse-a" }]);
      await vi.advanceTimersByTimeAsync(500);

      expect(region.textContent).toBe("1 new event");
    } finally {
      vi.useRealTimers();
    }
  });

  it("marks the feed role and busies it during load-more", async () => {
    const nextPage = deferred<EventFeedResponse>();
    getEventFeed
      .mockResolvedValueOnce(
        feed([eventItem("event-1", "Make stream default")], "2026-07-01T12:00:00Z|event-1"),
      )
      .mockReturnValueOnce(nextPage.promise);

    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    const feedEl = screen.getByRole("feed");
    expect(feedEl).toHaveAttribute("aria-busy", "false");

    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(feedEl).toHaveAttribute("aria-busy", "true");

    nextPage.resolve(feed([eventItem("event-2", "Second page row")]));
    await screen.findByRole("button", { name: /Second page row/ });
    expect(feedEl).toHaveAttribute("aria-busy", "false");
  });

  it("keeps rows and run headers as articles inside the feed", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const feedEl = screen.getByRole("feed");
    const articles = feedEl.querySelectorAll("article");
    // One header article + one row article; nothing between them exposes a landmark role.
    expect(articles).toHaveLength(2);
    expect(articles[0].classList.contains("stream-run-head")).toBe(true);
    expect(articles[1].classList.contains("stream-row-wrap")).toBe(true);
    expect(feedEl.querySelector("section")).not.toBeInTheDocument();
  });

  it("renders the match count in the result header when counts are available", async () => {
    mocks.getEventFacets.mockResolvedValue(countsPayload(1204));
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const countButton = await screen.findByRole("button", { name: "1,204 matches" });
    expect(countButton).toHaveAttribute("aria-expanded", "false");
    expect(mocks.getEventFacets).toHaveBeenCalledWith(
      { q: "", meaningful: true },
      expect.any(AbortSignal),
    );
    // The header reads "N matches · meaningful" — count beside the scope phrase (spec §4.6).
    expect(document.querySelector(".stream-result-scope")?.textContent).toBe("meaningful");
  });

  it("omits the match count while counts are unavailable — scope phrase only", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "last:2h" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    await waitFor(() => expect(mocks.getEventFacets).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.querySelector(".stream-result-scope")?.textContent).toBe(
      "meaningful · past 2 hours",
    );
    expect(document.querySelector(".stream-result-count")).not.toBeInTheDocument();
    expect(document.querySelector(".stream-count-browser")).not.toBeInTheDocument();
  });

  it("aborts in-flight counts on a query change and never renders a stale count", async () => {
    const stale = deferred<EventFacetCounts>();
    let staleSignal: AbortSignal | undefined;
    mocks.getEventFacets.mockReset();
    mocks.getEventFacets
      .mockImplementationOnce((_params: unknown, signal: AbortSignal) => {
        staleSignal = signal;
        return stale.promise;
      })
      .mockResolvedValueOnce(countsPayload(7));

    try {
      vi.useFakeTimers();
      render(() => <StreamPage />);
      await vi.advanceTimersByTimeAsync(0); // flush the feed promise
      await vi.advanceTimersByTimeAsync(300); // first debounce fires
      expect(mocks.getEventFacets).toHaveBeenCalledTimes(1);

      setParams({ q: "kind:Decision" });
      await vi.advanceTimersByTimeAsync(300); // second debounce fires for the new q
      expect(mocks.getEventFacets).toHaveBeenCalledTimes(2);
      expect(staleSignal?.aborted).toBe(true);

      stale.resolve(countsPayload(999)); // the old q's response lands late
      await vi.advanceTimersByTimeAsync(0);
      expect(screen.queryByRole("button", { name: "999 matches" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "7 matches" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("debounces count fetches while the query keeps changing", async () => {
    mocks.getEventFacets.mockResolvedValue(countsPayload(7));
    try {
      vi.useFakeTimers();
      render(() => <StreamPage />);
      await vi.advanceTimersByTimeAsync(0);
      await vi.advanceTimersByTimeAsync(100);
      setParams({ q: "kind:Decision" });
      await vi.advanceTimersByTimeAsync(100);
      setParams({ q: "kind:Handoff" });
      await vi.advanceTimersByTimeAsync(300);
      // Three q states, one surviving fetch — the earlier debounce windows never fired.
      expect(mocks.getEventFacets).toHaveBeenCalledTimes(1);
      expect(mocks.getEventFacets).toHaveBeenCalledWith(
        { q: "kind:Handoff", meaningful: true },
        expect.any(AbortSignal),
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it("opens the counted browser from the match count, narrows on click, and dims zero counts", async () => {
    mocks.getEventFacets.mockResolvedValue(countsPayload(42));
    [params, setParams] = createStore<{ q?: string }>({ q: "tool:Zed" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    expect(document.querySelector(".stream-count-browser")).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "42 matches" }));
    const browser = document.querySelector(".stream-count-browser") as HTMLElement;
    expect(browser).toBeInTheDocument();

    // Project values render through truncatePath but click with the raw value.
    expect(within(browser).getByText("~/Developer/proj/sba-agentic")).toBeInTheDocument();
    // The active tool:Zed is absent from the server's list: honest zero, dim, still clickable.
    const zed = within(browser).getByRole("button", { name: "Zed 0" });
    expect(zed.classList.contains("stream-count-value--zero")).toBe(true);
    const bash = within(browser).getByRole("button", { name: "Bash 500" });
    expect(bash.classList.contains("stream-count-value--zero")).toBe(false);

    fireEvent.click(bash);
    await waitFor(() => expect(params.q).toBe("tool:Bash"));
  });

  it("suggests counted top tool values from the facets endpoint for an empty tool: prefix", async () => {
    mocks.getEventFacets.mockResolvedValue(countsPayload(42));
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    await screen.findByRole("button", { name: "42 matches" }); // counts have arrived

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "tool:" } });
    const option = await screen.findByRole("option", { name: "Bash 500" });
    expect(option.querySelector(".suggest-option-count")?.textContent).toBe("500");

    fireEvent.click(option);
    expect(input).toHaveValue("tool:Bash ");
  });

  it("falls back to searchValues for tool: suggestions while counts are unavailable", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const input = screen.getByLabelText("Stream query");
    fireEvent.input(input, { target: { value: "tool:" } });
    const option = await screen.findByRole("option", { name: "Decision" });
    expect(option.querySelector(".suggest-option-count")).not.toBeInTheDocument();
  });

  it("applies each Views preset exactly as its locked q string", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const trigger = screen.getByRole("button", { name: "Views" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    const presets: Array<[RegExp, string]> = [
      [/Decisions this week/, "kind:Decision last:7d"],
      [/Handoffs/, "kind:Handoff last:7d"],
      [/Codex right now/, "source:codex last:2h"],
      [/Prompts today/, "kind:UserPromptSubmit last:24h"],
    ];
    for (const [name, q] of presets) {
      fireEvent.click(screen.getByRole("button", { name: "Views" }));
      fireEvent.click(screen.getByRole("button", { name }));
      await waitFor(() => expect(params.q).toBe(q));
      // Picking a view closes the panel.
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
  });

  it("keeps the pinned project scope untouched when a view is picked", async () => {
    render(() => <StreamPage project={selectedProject} />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    fireEvent.click(screen.getByRole("button", { name: /Codex right now/ }));

    // With a pinned project the slice-6 project-strip effect round-trips q through the parser,
    // normalizing last:2h to its equivalent since-duration token (same semantics, D7).
    await waitFor(() => expect(params.q).toBe("source:codex since:2h"));
    await waitFor(() =>
      expect(getEventFeed).toHaveBeenLastCalledWith({
        limit: 100,
        q: "source:codex since:2h project_group:/Users/nathan/Developer/proj/sba-agentic",
        meaningful: true,
      }),
    );
    expect(
      screen.getByRole("button", { name: "Pinned project sba-agentic — clear project scope" }),
    ).toBeInTheDocument();
  });

  it("saves the current view, persists it across mounts, applies it, and removes it", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision last:7d" });
    const first = render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    fireEvent.input(screen.getByLabelText("Saved view name"), {
      target: { value: "My weekly decisions" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(JSON.parse(localStorage.getItem("blackbox.savedViews")!)).toMatchObject([
      { name: "My weekly decisions", q: "kind:Decision last:7d" },
    ]);
    expect(screen.getByRole("button", { name: "My weekly decisions" })).toBeInTheDocument();
    first.unmount();

    [params, setParams] = createStore<{ q?: string }>({});
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    fireEvent.click(screen.getByRole("button", { name: "My weekly decisions" }));
    await waitFor(() => expect(params.q).toBe("kind:Decision last:7d"));

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    fireEvent.click(screen.getByRole("button", { name: "Remove saved view My weekly decisions" }));
    expect(screen.queryByRole("button", { name: "My weekly decisions" })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem("blackbox.savedViews")!)).toEqual([]);
  });

  it("disables saving a view while the visible q is empty", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    expect(screen.getByLabelText("Saved view name")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("fails soft to presets only when saved-view storage is corrupt", async () => {
    localStorage.setItem("blackbox.savedViews", "{not json");
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByRole("button", { name: "Views" }));
    expect(screen.getByRole("button", { name: /Decisions this week/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Remove saved view/ })).not.toBeInTheDocument();
  });

  it("offers the quiet Ask-memory affordance only when q carries free text", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });
    expect(screen.queryByRole("link", { name: /Ask memory about/ })).not.toBeInTheDocument();

    setParams({ q: 'kind:Decision "recall bug"' });
    const link = await screen.findByRole("link", { name: /Ask memory about «recall bug»/ });
    expect(link).toHaveAttribute(
      "href",
      `/?${new URLSearchParams({ view: "ask", q: "recall bug" }).toString()}`,
    );
  });

  it("carries the pinned project on the Ask-memory link", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "recall" });
    render(() => <StreamPage project={selectedProject} />);
    await screen.findByRole("button", { name: /Make stream default/ });

    const link = await screen.findByRole("link", { name: /Ask memory about «recall»/ });
    expect(link).toHaveAttribute(
      "href",
      `/?${new URLSearchParams({ view: "ask", q: "recall", project: "sba-key" }).toString()}`,
    );
  });

  it("links expanded landmark cards to the trajectory when the cwd resolves in the catalog", async () => {
    render(() => <StreamPage projects={[selectedProject]} />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    const link = screen.getByRole("link", { name: "Trajectory" });
    expect(link).toHaveAttribute(
      "href",
      `/projects/sba-key?${new URLSearchParams({ focus: "capture:event-1" }).toString()}`,
    );
    // The per-event position link stays alongside it (D14 keeps position precision).
    expect(screen.getByRole("link", { name: "Open at this event" })).toBeInTheDocument();
  });

  it("omits the Trajectory link when the cwd resolves to no catalog project — never guesses", async () => {
    getEventFeed.mockReset();
    getEventFeed.mockResolvedValue(
      feed([eventItem("event-1", "Make stream default", undefined, { cwd: "/somewhere/unknown" })]),
    );
    render(() => <StreamPage projects={[selectedProject]} />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    expect(screen.queryByRole("link", { name: "Trajectory" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open at this event" })).toBeInTheDocument();
  });

  it("omits the Trajectory link while no catalog is available", async () => {
    render(() => <StreamPage />);
    const row = await screen.findByRole("button", { name: /Make stream default/ });
    fireEvent.click(row);

    expect(screen.queryByRole("link", { name: "Trajectory" })).not.toBeInTheDocument();
  });
});

function countsPayload(total: number): EventFacetCounts {
  return {
    total,
    fields: {
      source: [
        { value: "codex", count: 900 },
        { value: "claude", count: 304 },
      ],
      kind: [{ value: "Decision", count: 700 }],
      tool: [
        { value: "Bash", count: 500 },
        { value: "Read", count: 404 },
      ],
      project: [{ value: "/Users/nathan/Developer/proj/sba-agentic", count: total }],
    },
    reason: null,
  };
}

function feed(items: EventFeedItem[], nextBefore: string | null = null): EventFeedResponse {
  return {
    limit: 100,
    count: items.length,
    items,
    nextBefore,
  };
}

function openOptions() {
  fireEvent.click(screen.getByRole("button", { name: "Options" }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

function eventItem(
  id: string,
  text: string,
  observedAt?: string,
  overrides: Partial<EventFeedItem> = {},
): EventFeedItem {
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
    cwd:
      id === "event-1"
        ? "/Users/nathan/Developer/proj/sba-agentic"
        : "/Users/nathan/Developer/proj/cockpit",
    sessionTitle: id === "event-1" ? "Activity stream work" : "Browse regression",
    ...overrides,
  };
}

// Local-time ISO instants keep dateline assertions timezone-independent (segmentation groups
// by local date).
function localIso(day: number, hour: number): string {
  return new Date(2026, 6, day, hour, 0, 0).toISOString();
}

// Same-session tool chatter for fold cases.
function chatterItem(
  id: string,
  command: string,
  observedAt: string,
  extra: Partial<EventFeedItem> = {},
): EventFeedItem {
  return eventItem(id, "", observedAt, {
    sessionId: "session-1",
    source: "codex",
    clientSessionId: "client-1",
    cwd: "/Users/nathan/Developer/proj/sba-agentic",
    sessionTitle: "Activity stream work",
    eventType: "PostToolUse",
    toolName: "Bash",
    toolInputJson: JSON.stringify({ command }),
    ...extra,
  });
}
