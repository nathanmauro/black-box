import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  deleteProjectAlias,
  getProjectMelds,
  getProjects,
  getProjectSessions,
  getProjectTimeline,
  mergeProjectAlias,
  type ProjectSummary,
  type ProjectTimelineResponse,
} from "../../lib/api";
import ProjectsPage from "../ProjectsPage";

let routeParams: { projectKey?: string };
const navigate = vi.fn();

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; class?: string; children: JSX.Element }) => (
    <a href={props.href} class={props.class}>{props.children}</a>
  ),
  useNavigate: () => navigate,
  useParams: () => routeParams,
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
  navigate.mockReset();
  vi.mocked(getProjects).mockReset().mockResolvedValue([groupedProject, otherProject, ...protectedProjects]);
  vi.mocked(getProjectSessions).mockReset().mockResolvedValue([
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
  vi.mocked(getProjectTimeline).mockReset().mockImplementation(async (_key, limit, offset) => {
    if (limit === 1) return timelineResponse(400, [{ id: "probe", text: "Oldest probe", observedAt: "2025-01-01T00:00:00Z" }]);
    expect(offset).toBe(150);
    return timelineResponse(400, [
      { id: "older", text: "Older project observation", observedAt: "2026-07-14T12:00:00Z" },
      { id: "newest", text: "Newest project observation", observedAt: "2026-07-15T12:00:00Z" },
    ]);
  });
  vi.mocked(getProjectMelds).mockReset().mockResolvedValue([
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
  it("renders grouped project evidence and fetches the true latest timeline window", async () => {
    render(() => <ProjectsPage />);

    expect(screen.getByRole("heading", { name: "Projects" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    expect(screen.getByText("Project catalog")).toBeInTheDocument();
    expect(screen.getByText("Hybrid storyline")).toBeInTheDocument();
    expect(screen.getByText("Recent sessions")).toBeInTheDocument();
    expect(screen.getByText("Saved melds")).toBeInTheDocument();
    expect(document.querySelector(".project-stat-strip")).toHaveTextContent("1 meld");
    expect(screen.getByText("/Users/nathan/.codex/worktrees/abc/sba-agentic")).toBeInTheDocument();
    expect(screen.getByText("Automatic · nested worktree")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Undo merge for/ })).toHaveLength(1);

    await waitFor(() => expect(getProjectTimeline).toHaveBeenCalledWith("sba-key", 250, 150));
    await screen.findByText("Newest project observation", { selector: ".event-card--observation strong" });
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

    fireEvent.click(document.querySelector(".project-catalog-pane .project-picker-button") as HTMLButtonElement);
    expect(screen.queryByRole("button", { name: "All projects" })).not.toBeInTheDocument();
  });

  it("merges another catalog entry without offering meld creation", async () => {
    render(() => <ProjectsPage />);
    await screen.findByRole("heading", { name: "sba-agentic" });

    fireEvent.click(screen.getByText("Merge another catalog entry"));
    const identityPanel = screen.getByRole("region", { name: "Identity & scopes" });
    fireEvent.click(within(identityPanel).getByRole("button", { name: /Choose a project to merge/ }));
    fireEvent.input(within(identityPanel).getByLabelText("Search projects"), { target: { value: "__no_project__" } });
    expect(within(identityPanel).getByText("No projects match.")).toBeInTheDocument();
    fireEvent.input(within(identityPanel).getByLabelText("Search projects"), { target: { value: "cockpit" } });
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
    vi.mocked(getProjectMelds).mockReset().mockRejectedValue(new Error("melds offline"));
    render(() => <ProjectsPage />);

    expect(await screen.findByText("Storyline unavailable")).toBeInTheDocument();
    expect(screen.getByText("sessions offline")).toBeInTheDocument();
    expect(screen.getByText("melds offline")).toBeInTheDocument();
  });

  it("retries the latest timeline window when the count changes between requests", async () => {
    vi.mocked(getProjectTimeline).mockReset()
      .mockResolvedValueOnce(timelineResponse(400, [{ id: "probe", text: "Probe", observedAt: "2025-01-01T00:00:00Z" }]))
      .mockResolvedValueOnce(timelineResponse(401, [{ id: "shifted", text: "Shifted window", observedAt: "2026-07-15T10:00:00Z" }]))
      .mockResolvedValueOnce(timelineResponse(401, [{ id: "latest", text: "Newest after retry", observedAt: "2026-07-15T12:00:00Z" }]));

    render(() => <ProjectsPage />);

    expect(await screen.findByText("Newest after retry", { selector: ".event-card--observation strong" })).toBeInTheDocument();
    expect(getProjectTimeline).toHaveBeenNthCalledWith(2, "sba-key", 250, 150);
    expect(getProjectTimeline).toHaveBeenNthCalledWith(3, "sba-key", 250, 151);
  });

  it("shows an explicit invalid project state", async () => {
    routeParams = { projectKey: "missing-project" };
    render(() => <ProjectsPage />);

    expect(await screen.findByText("Unknown project identity")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open project catalog" })).toHaveAttribute("href", "/projects");
    expect(getProjectSessions).not.toHaveBeenCalled();
  });
});

function timelineResponse(
  count: number,
  items: Array<{ id: string; text: string; observedAt: string }>,
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
      blockType: "Observation",
      eventType: "Observation",
      headline: item.text,
    })),
  };
}
