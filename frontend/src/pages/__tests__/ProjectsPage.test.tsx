import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  deleteProjectAlias,
  getProjectMelds,
  getProjects,
  getProjectSessions,
  getProjectTimeline,
  getProjectTrajectory,
  mergeProjectAlias,
  type ProjectSummary,
  type ProjectTimelineResponse,
  type ProjectTrajectoryResponse,
} from "../../lib/api";
import ProjectsPage from "../ProjectsPage";

let routeParams: { projectKey?: string };
let searchParams: { focus?: string };
let setSearchParamsStore: SetStoreFunction<{ focus?: string }>;
const navigate = vi.fn();

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; class?: string; children: JSX.Element }) => (
    <a href={props.href} class={props.class}>
      {props.children}
    </a>
  ),
  useNavigate: () => navigate,
  useParams: () => routeParams,
  // Same store idiom as the StreamPage tests; the component may pass navigate options as a
  // second argument, which the store setter must never see.
  useSearchParams: () => [
    searchParams,
    (update: { focus?: string }) => setSearchParamsStore(update),
  ],
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    deleteProjectAlias: vi.fn(),
    getProjectMelds: vi.fn(),
    getProjects: vi.fn(),
    getProjectSessions: vi.fn(),
    getProjectTimeline: vi.fn(),
    getProjectTrajectory: vi.fn(),
    mergeProjectAlias: vi.fn(),
  };
});

const groupedProject: ProjectSummary = {
  projectKey: "sba-key",
  canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
  label: "~/Developer/proj/sba-agentic",
  sessionCount: 310,
  eventCount: 4_200,
  savedMeldCount: 1,
  lastSeenAt: "2026-07-15T16:00:00Z",
  scopes: [
    {
      projectKey: "sba-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "~/Developer/proj/sba-agentic",
      primary: true,
    },
    {
      projectKey: "sba-worktree-key",
      canonicalKey: "/Users/nathan/.codex/worktrees/abc/sba-agentic",
      label: "SBA worktree",
      primary: false,
      source: "manual",
    },
    {
      projectKey: "sba-auto-worktree-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic/.worktrees/feature",
      label: "Feature worktree",
      primary: false,
      source: "nested-worktree",
    },
  ],
};

const otherProject: ProjectSummary = {
  projectKey: "cockpit-key",
  canonicalKey: "/Users/nathan/Developer/proj/cockpit",
  label: "~/Developer/proj/cockpit",
  sessionCount: 10,
  eventCount: 80,
  savedMeldCount: 0,
  lastSeenAt: "2026-07-14T16:00:00Z",
};

const protectedProjects: ProjectSummary[] = [
  {
    projectKey: "root-key",
    canonicalKey: "/",
    label: "Filesystem root",
    sessionCount: 1,
    eventCount: 1,
    savedMeldCount: 0,
  },
  {
    projectKey: "no-project-key",
    canonicalKey: "__no_project__",
    label: "__no_project__",
    sessionCount: 1,
    eventCount: 1,
    savedMeldCount: 0,
  },
];

beforeEach(() => {
  routeParams = {};
  [searchParams, setSearchParamsStore] = createStore<{ focus?: string }>({});
  localStorage.clear();
  navigate.mockReset();
  vi.mocked(getProjects)
    .mockReset()
    .mockResolvedValue([groupedProject, otherProject, ...protectedProjects]);
  vi.mocked(getProjectSessions)
    .mockReset()
    .mockResolvedValue([
      {
        id: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        title: "Finish project integration",
        startedAt: "2026-07-15T15:00:00Z",
        lastSeenAt: "2026-07-15T16:00:00Z",
        eventCount: 18,
      },
    ]);
  vi.mocked(getProjectTimeline)
    .mockReset()
    .mockImplementation(async (_key, limit, offset) => {
      if (limit === 1)
        return timelineResponse(400, [
          { id: "probe", text: "Oldest probe", observedAt: "2025-01-01T00:00:00Z" },
        ]);
      expect(offset).toBe(150);
      return timelineResponse(400, [
        { id: "older", text: "Older project observation", observedAt: "2026-07-14T12:00:00Z" },
        { id: "newest", text: "Newest project observation", observedAt: "2026-07-15T12:00:00Z" },
      ]);
    });
  vi.mocked(getProjectTrajectory).mockReset().mockResolvedValue(trajectoryResponse());
  vi.mocked(getProjectMelds)
    .mockReset()
    .mockResolvedValue([
      {
        id: "meld-1",
        projectKey: "sba-key",
        canonicalKey: groupedProject.canonicalKey,
        title: "Project integration synthesis",
        body: "The catalog and activity surfaces now share one identity.",
        provider: "local",
        model: "context-bundle",
        promptVersion: "v1",
        executionMode: "export_bundle",
        savedFromPreview: true,
        createdAt: "2026-07-15T16:00:00Z",
        metadata: {},
        sessions: [
          {
            id: "session-1",
            source: "codex",
            clientSessionId: "client-1",
            title: "Finish project integration",
            eventCount: 18,
          },
        ],
      },
    ]);
  vi.mocked(mergeProjectAlias).mockReset().mockResolvedValue({
    id: "alias-1",
    aliasKey: otherProject.canonicalKey,
    canonicalKey: groupedProject.canonicalKey,
    source: "manual",
    createdAt: "2026-07-15T17:00:00Z",
  });
  vi.mocked(deleteProjectAlias).mockReset().mockResolvedValue();
});

describe("ProjectsPage", () => {
  it("uses the trajectory graph as the default center-pane view", async () => {
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const toggle = screen.getByRole("group", { name: "Project storyline view" });
    expect(within(toggle).getByRole("button", { name: "Trajectory" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(within(toggle).getByRole("button", { name: "Timeline" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(await screen.findByText("Current handoff")).toBeInTheDocument();
    expect(document.querySelector(".project-trajectory .traj-stage")).toBeInTheDocument();
    expect(screen.getByText("2 nodes")).toBeInTheDocument();
    expect(getProjectTrajectory).toHaveBeenCalledWith("sba-key");
  });

  it("selects trajectory nodes into the detail card and clears only on unhandled Escape", async () => {
    vi.mocked(getProjectTrajectory).mockReset().mockResolvedValue(trajectoryResponseWithTask());
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const rail = document.querySelector(".project-context-rail") as HTMLElement;
    const headNode = await waitFor(() => {
      const node = document.querySelector('[data-node-kind="head"]');
      expect(node).toBeInTheDocument();
      return node as Element;
    });

    fireEvent.click(headNode);
    const captureDetail = within(rail).getByRole("region", { name: "Trajectory detail" });
    expect(rail.firstElementChild).toBe(captureDetail);
    expect(captureDetail).toHaveTextContent("Current project state");
    expect(
      within(captureDetail).getByRole("link", { name: "Session Finish project integration" }),
    ).toHaveAttribute("href", "/?view=browse&project=sba-key&session=session-1");

    const preventedEscape = new KeyboardEvent("keydown", {
      key: "Escape",
      bubbles: true,
      cancelable: true,
    });
    preventedEscape.preventDefault();
    window.dispatchEvent(preventedEscape);
    expect(within(rail).getByRole("region", { name: "Trajectory detail" })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(
      within(rail).queryByRole("region", { name: "Trajectory detail" }),
    ).not.toBeInTheDocument();

    const taskNode = await waitFor(() => {
      const node = document.querySelector('[data-node-kind="future-task"]');
      expect(node).toBeInTheDocument();
      return node as Element;
    });
    fireEvent.click(taskNode);
    const taskDetail = within(rail).getByRole("region", { name: "Trajectory detail" });
    expect(rail.firstElementChild).toBe(taskDetail);
    expect(taskDetail).toHaveTextContent("Prepare task packet");
    expect(
      within(taskDetail).getByRole("link", { name: "Task open: Prepare task packet" }),
    ).toHaveAttribute("href", "/board?task=task-1");
  });

  it("switches the center pane to the timeline view", async () => {
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));

    const toggle = screen.getByRole("group", { name: "Project storyline view" });
    expect(within(toggle).getByRole("button", { name: "Trajectory" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(within(toggle).getByRole("button", { name: "Timeline" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByText("Hybrid storyline")).toBeInTheDocument();
    expect(
      await screen.findByText("Newest project observation", {
        selector: ".event-card--observation strong",
      }),
    ).toBeInTheDocument();
  });

  it("renders projection timeline blocks with a projection badge and paths", async () => {
    vi.mocked(getProjectTimeline)
      .mockReset()
      .mockResolvedValue(
        timelineResponse(1, [
          {
            id: "projection",
            text: "Projected futures:\n1. Polish graph e2e",
            observedAt: "2026-07-15T12:00:00Z",
            blockType: "projection",
            eventType: "Projection",
            headline: "Polish graph e2e",
            metadata: {
              kind: "projection",
              basis: "Trajectory coverage needs deterministic ghost futures.",
              paths: [
                {
                  title: "Polish graph e2e",
                  description: "Assert seeded ghosts in the project graph.",
                  confidence: 0.68,
                },
                {
                  title: "Document projection review",
                  confidence: 0.42,
                },
              ],
            },
          },
        ]),
      );
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));

    expect(
      await screen.findByText("Projection", { selector: ".timeline-block-label span" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Projection", { selector: ".kind-badge" })).toBeInTheDocument();
    expect(
      screen.getByText("Trajectory coverage needs deterministic ghost futures."),
    ).toBeInTheDocument();
    expect(screen.getAllByText("Polish graph e2e")).not.toHaveLength(0);
    expect(screen.getByText(/Assert seeded ghosts in the project graph/)).toBeInTheDocument();
    expect(screen.getByText(/68%/)).toBeInTheDocument();
    expect(screen.getByText("Document projection review")).toBeInTheDocument();
    expect(screen.getByText(/42%/)).toBeInTheDocument();
  });

  it("persists the storyline view choice to localStorage", async () => {
    const first = render(() => <ProjectsPage />);
    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));
    expect(localStorage.getItem("bb.projectStoryView")).toBe("timeline");
    first.unmount();

    render(() => <ProjectsPage />);
    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Timeline" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    fireEvent.click(screen.getByRole("button", { name: "Trajectory" }));
    expect(localStorage.getItem("bb.projectStoryView")).toBe("trajectory");
  });

  it("renders grouped project evidence and fetches the true latest timeline window", async () => {
    render(() => <ProjectsPage />);

    expect(screen.getByRole("heading", { name: "Projects" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    expect(screen.getByText("Project catalog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));
    expect(screen.getByText("Hybrid storyline")).toBeInTheDocument();
    expect(screen.getByText("Recent sessions")).toBeInTheDocument();
    expect(screen.getByText("Saved melds")).toBeInTheDocument();
    expect(document.querySelector(".project-stat-strip")).toHaveTextContent("1 meld");
    expect(screen.getByText("/Users/nathan/.codex/worktrees/abc/sba-agentic")).toBeInTheDocument();
    expect(screen.getByText("Automatic · nested worktree")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Undo merge for/ })).toHaveLength(1);

    await waitFor(() => expect(getProjectTimeline).toHaveBeenCalledWith("sba-key", 250, 150));
    await screen.findByText("Newest project observation", {
      selector: ".event-card--observation strong",
    });
    const timeline = document.querySelector(".project-timeline") as HTMLElement;
    const rows = Array.from(timeline.querySelectorAll(".project-timeline-row"));
    expect(rows[0]).toHaveTextContent("Newest project observation");
    expect(rows[1]).toHaveTextContent("Older project observation");

    expect(screen.getByRole("link", { name: "Activity Browse" })).toHaveAttribute(
      "href",
      "/?view=browse&project=sba-key",
    );
    expect(screen.getByRole("link", { name: "Board" })).toHaveAttribute(
      "href",
      "/board?project=%2FUsers%2Fnathan%2FDeveloper%2Fproj%2Fsba-agentic",
    );
    expect(screen.getByRole("link", { name: "Recall" })).toHaveAttribute(
      "href",
      "/recall?scope=%2FUsers%2Fnathan%2FDeveloper%2Fproj%2Fsba-agentic",
    );
    expect(await screen.findByText("Project integration synthesis")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Finish project integration" })[0]).toHaveAttribute(
      "href",
      "/?view=browse&project=sba-key&session=session-1",
    );
    expect(screen.queryByRole("button", { name: /Preview meld/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Save meld/i })).not.toBeInTheDocument();

    fireEvent.click(
      document.querySelector(".project-catalog-pane .project-picker-button") as HTMLButtonElement,
    );
    expect(screen.queryByRole("button", { name: "All projects" })).not.toBeInTheDocument();
  });

  it("merges another catalog entry without offering meld creation", async () => {
    render(() => <ProjectsPage />);
    await screen.findByRole("heading", { name: "sba-agentic" });

    fireEvent.click(screen.getByText("Merge another catalog entry"));
    const identityPanel = screen.getByRole("region", { name: "Identity & scopes" });
    fireEvent.click(
      within(identityPanel).getByRole("button", { name: /Choose a project to merge/ }),
    );
    fireEvent.input(within(identityPanel).getByLabelText("Search projects"), {
      target: { value: "__no_project__" },
    });
    expect(within(identityPanel).getByText("No projects match.")).toBeInTheDocument();
    fireEvent.input(within(identityPanel).getByLabelText("Search projects"), {
      target: { value: "cockpit" },
    });
    fireEvent.click(await within(identityPanel).findByRole("option", { name: /cockpit/ }));
    expect(screen.getByText(/raw sessions and event history stay untouched/i)).toBeInTheDocument();
    fireEvent.click(within(identityPanel).getByRole("button", { name: "Merge into this project" }));

    await waitFor(() =>
      expect(mergeProjectAlias).toHaveBeenCalledWith(
        "/Users/nathan/Developer/proj/cockpit",
        "/Users/nathan/Developer/proj/sba-agentic",
      ),
    );
    await waitFor(() => expect(getProjects).toHaveBeenCalledTimes(2));
    await waitFor(() => {
      expect(getProjectSessions).toHaveBeenCalledTimes(2);
      expect(getProjectMelds).toHaveBeenCalledTimes(2);
      expect(getProjectTimeline).toHaveBeenCalledTimes(4);
    });
  });

  it("keeps catalog and detail failures inside their explicit error states", async () => {
    vi.mocked(getProjects).mockReset().mockRejectedValue(new Error("catalog offline"));
    const catalogView = render(() => <ProjectsPage />);

    expect(await screen.findByText("Project catalog unavailable")).toBeInTheDocument();
    expect(screen.getByText("catalog offline")).toBeInTheDocument();
    expect(getProjectSessions).not.toHaveBeenCalled();
    catalogView.unmount();

    vi.mocked(getProjects).mockReset().mockResolvedValue([groupedProject]);
    vi.mocked(getProjectSessions).mockReset().mockRejectedValue(new Error("sessions offline"));
    vi.mocked(getProjectTimeline).mockReset().mockRejectedValue(new Error("timeline offline"));
    vi.mocked(getProjectTrajectory).mockReset().mockResolvedValue(trajectoryResponse());
    vi.mocked(getProjectMelds).mockReset().mockRejectedValue(new Error("melds offline"));
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));
    expect(await screen.findByText("Storyline unavailable")).toBeInTheDocument();
    expect(screen.getByText("sessions offline")).toBeInTheDocument();
    expect(screen.getByText("melds offline")).toBeInTheDocument();
  });

  it("retries the latest timeline window when the count changes between requests", async () => {
    vi.mocked(getProjectTimeline)
      .mockReset()
      .mockResolvedValueOnce(
        timelineResponse(400, [{ id: "probe", text: "Probe", observedAt: "2025-01-01T00:00:00Z" }]),
      )
      .mockResolvedValueOnce(
        timelineResponse(401, [
          { id: "shifted", text: "Shifted window", observedAt: "2026-07-15T10:00:00Z" },
        ]),
      )
      .mockResolvedValueOnce(
        timelineResponse(401, [
          { id: "latest", text: "Newest after retry", observedAt: "2026-07-15T12:00:00Z" },
        ]),
      );

    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Timeline" }));
    expect(
      await screen.findByText("Newest after retry", {
        selector: ".event-card--observation strong",
      }),
    ).toBeInTheDocument();
    expect(getProjectTimeline).toHaveBeenNthCalledWith(2, "sba-key", 250, 150);
    expect(getProjectTimeline).toHaveBeenNthCalledWith(3, "sba-key", 250, 151);
  });

  it("writes trajectory selection to ?focus= and follows external focus changes (back button)", async () => {
    render(() => <ProjectsPage />);
    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const rail = document.querySelector(".project-context-rail") as HTMLElement;
    const headNode = await waitFor(() => {
      const node = document.querySelector('[data-node-kind="head"]');
      expect(node).toBeInTheDocument();
      return node as Element;
    });

    fireEvent.click(headNode);
    expect(searchParams.focus).toBe("head:trajectory-handoff");
    expect(within(rail).getByRole("region", { name: "Trajectory detail" })).toBeInTheDocument();

    // The URL is the selection: an external param change (what the back button does) drives it.
    setSearchParamsStore({ focus: undefined });
    expect(
      within(rail).queryByRole("region", { name: "Trajectory detail" }),
    ).not.toBeInTheDocument();

    setSearchParamsStore({ focus: "head:trajectory-handoff" });
    expect(within(rail).getByRole("region", { name: "Trajectory detail" })).toHaveTextContent(
      "Current project state",
    );

    // Escape clears the selection by clearing the param.
    fireEvent.keyDown(window, { key: "Escape" });
    expect(searchParams.focus).toBeUndefined();
    expect(
      within(rail).queryByRole("region", { name: "Trajectory detail" }),
    ).not.toBeInTheDocument();
  });

  it("restores a deep-linked ?focus= selection on load so it survives reload", async () => {
    [searchParams, setSearchParamsStore] = createStore<{ focus?: string }>({
      focus: "head:trajectory-handoff",
    });
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const rail = document.querySelector(".project-context-rail") as HTMLElement;
    const detail = await within(rail).findByRole("region", { name: "Trajectory detail" });
    expect(detail).toHaveTextContent("Current project state");
    expect(document.querySelector(".traj-node--selected")).toHaveAttribute(
      "data-node-id",
      "head:trajectory-handoff",
    );
    // The deep-linked focus was never cleared by the initial catalog resolve.
    expect(searchParams.focus).toBe("head:trajectory-handoff");
  });

  it("resolves focus=capture:<eventId> to the node containing that capture", async () => {
    vi.mocked(getProjectTrajectory).mockReset().mockResolvedValue(twoBurstTrajectoryResponse());
    [searchParams, setSearchParamsStore] = createStore<{ focus?: string }>({
      focus: "capture:old-observation",
    });
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const rail = document.querySelector(".project-context-rail") as HTMLElement;
    const detail = await within(rail).findByRole("region", { name: "Trajectory detail" });
    expect(detail).toHaveTextContent("2 captures");
    expect(detail).toHaveTextContent("Old burst observation");
    expect(document.querySelector(".traj-node--selected")).toHaveAttribute(
      "data-node-id",
      "burst:0:old-observation",
    );

    // A capture in the newest burst resolves to the head node.
    setSearchParamsStore({ focus: "capture:trajectory-handoff" });
    expect(within(rail).getByRole("region", { name: "Trajectory detail" })).toHaveTextContent(
      "Current project state",
    );
    expect(document.querySelector(".traj-node--selected")).toHaveAttribute(
      "data-node-id",
      "head:trajectory-handoff",
    );
  });

  it("links burst and head nodes into the Stream with absolute ISO bounds and the project param", async () => {
    vi.mocked(getProjectTrajectory).mockReset().mockResolvedValue(twoBurstTrajectoryResponse());
    [searchParams, setSearchParamsStore] = createStore<{ focus?: string }>({
      focus: "burst:0:old-observation",
    });
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    const rail = document.querySelector(".project-context-rail") as HTMLElement;
    const burstDetail = await within(rail).findByRole("region", { name: "Trajectory detail" });
    const burstQuery = new URLSearchParams({
      q: "since:2026-06-01T12:00:00.000Z until:2026-06-01T13:00:00.000Z",
      project: "sba-key",
    });
    expect(within(burstDetail).getByRole("link", { name: "View in Stream" })).toHaveAttribute(
      "href",
      `/stream?${burstQuery.toString()}`,
    );

    setSearchParamsStore({ focus: "head:trajectory-handoff" });
    const headDetail = within(rail).getByRole("region", { name: "Trajectory detail" });
    const headQuery = new URLSearchParams({
      q: "since:2026-07-15T16:00:00.000Z until:2026-07-15T16:00:00.000Z",
      project: "sba-key",
    });
    expect(within(headDetail).getByRole("link", { name: "View in Stream" })).toHaveAttribute(
      "href",
      `/stream?${headQuery.toString()}`,
    );

    // Future nodes are not burst-shaped: no Stream span, no link.
    setSearchParamsStore({ focus: "future:next:trajectory-handoff" });
    const futureDetail = within(rail).getByRole("region", { name: "Trajectory detail" });
    expect(
      within(futureDetail).queryByRole("link", { name: "View in Stream" }),
    ).not.toBeInTheDocument();
  });

  it("shows an explicit invalid project state", async () => {
    routeParams = { projectKey: "missing-project" };
    render(() => <ProjectsPage />);

    expect(await screen.findByText("Unknown project identity")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open project catalog" })).toHaveAttribute(
      "href",
      "/projects",
    );
    expect(getProjectSessions).not.toHaveBeenCalled();
  });
});

function timelineResponse(
  count: number,
  items: Array<{
    id: string;
    text: string;
    observedAt: string;
    blockType?: string;
    eventType?: string;
    headline?: string;
    metadata?: unknown;
  }>,
): ProjectTimelineResponse {
  return {
    projectKey: "sba-key",
    canonicalKey: groupedProject.canonicalKey,
    label: groupedProject.label,
    limit: 250,
    offset: 0,
    count,
    items: items.map((item) => ({
      ...item,
      source: "codex",
      sourceType: "event",
      blockType: item.blockType ?? "Observation",
      eventType: item.eventType ?? "Observation",
      headline: item.headline ?? item.text,
    })),
  };
}

function trajectoryResponse(): ProjectTrajectoryResponse {
  return {
    projectKey: "sba-key",
    canonicalKey: groupedProject.canonicalKey,
    label: groupedProject.label,
    generatedAt: "2026-07-15T16:00:00Z",
    totalCaptures: 1,
    captures: [
      {
        id: "trajectory-handoff",
        kind: "handoff",
        sessionId: "session-1",
        sessionTitle: "Finish project integration",
        clientSessionId: "client-1",
        headline: "Current handoff",
        text: "Current project state",
        nextAction: "Write trajectory tests",
        observedAt: "2026-07-15T16:00:00Z",
      },
    ],
    tasks: [],
  };
}

// Two bursts more than GAP_HOURS apart: an older two-capture burst (June 1, 12:00–13:00 UTC)
// and the newest single-handoff burst that becomes the head (July 15, 16:00 UTC).
function twoBurstTrajectoryResponse(): ProjectTrajectoryResponse {
  const base = trajectoryResponse();
  return {
    ...base,
    totalCaptures: 3,
    captures: [
      {
        id: "old-observation",
        kind: "observation",
        sessionId: "session-old",
        headline: "Old burst observation",
        text: "An observation from the earlier burst.",
        observedAt: "2026-06-01T12:00:00Z",
      },
      {
        id: "old-decision",
        kind: "decision",
        sessionId: "session-old",
        headline: "Old burst decision",
        text: "A decision closing the earlier burst.",
        observedAt: "2026-06-01T13:00:00Z",
      },
      ...base.captures,
    ],
  };
}

function trajectoryResponseWithTask(): ProjectTrajectoryResponse {
  return {
    ...trajectoryResponse(),
    tasks: [
      {
        id: "task-1",
        title: "Prepare task packet",
        status: "open",
        priority: 50,
        updatedAt: "2026-07-15T15:30:00Z",
      },
    ],
  };
}
