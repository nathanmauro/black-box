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
    expect(within(row).getByText("~/Developer/proj/sba-agentic")).toBeInTheDocument();
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

  it("refetches when meaningful-only filtering changes", async () => {
    render(() => <StreamPage />);
    await screen.findByRole("button", { name: /Make stream default/ });

    fireEvent.click(screen.getByLabelText("meaningful events only"));

    await waitFor(() => expect(getEventFeed).toHaveBeenLastCalledWith({ limit: 100, q: "", meaningful: false }));
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
