import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { previewProjectMeld } from "../../lib/api";
import ProjectsPage from "../ProjectsPage";

const navigate = vi.fn();

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    useNavigate: () => navigate,
    useParams: () => ({ projectKey: "proj-key" }),
  };
});

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getProjects: vi.fn(async () => [
      {
        projectKey: "proj-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        label: "sba-agentic",
        sessionCount: 2,
        eventCount: 42,
        savedMeldCount: 0,
        firstSeenAt: "2026-06-15T20:00:00Z",
        lastSeenAt: "2026-06-16T20:00:00Z",
      },
    ]),
    getProjectSessions: vi.fn(async () => [
      {
        id: "s1",
        source: "codex",
        clientSessionId: "client-1",
        title: "Session A",
        cwd: "/Users/nathan/Developer/proj/sba-agentic",
        summary: "Built the API slice.",
        startedAt: "2026-06-16T19:00:00Z",
        lastSeenAt: "2026-06-16T20:00:00Z",
        eventCount: 24,
      },
    ]),
    getProjectTimeline: vi.fn(async () => ({
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "sba-agentic",
      limit: 250,
      offset: 0,
      count: 1,
      items: [
        {
          id: "evt-1",
          sourceType: "event",
          blockType: "decision",
          headline: "Keep Projects derived from cwd",
          text: "Preserve runway for richer project management.",
          eventType: "Decision",
          source: "codex",
          clientSessionId: "client-1",
          sessionId: "s1",
          sessionTitle: "Session A",
          cwd: "/Users/nathan/Developer/proj/sba-agentic",
          metadata: { decision: "Keep Projects derived from cwd", confidence: 0.9 },
          observedAt: "2026-06-16T20:00:00Z",
        },
      ],
    })),
    getProjectMelds: vi.fn(async () => []),
    previewProjectMeld: vi.fn(async () => ({
      status: "preview",
      executionMode: "bundle",
      provider: "codex",
      model: "default",
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      title: "Meld preview",
      preview: "Preview summary",
      bundle: "Bundle text for selected sessions",
      sessions: [],
      sessionCount: 1,
      evidenceCount: 3,
      bundleChars: 34,
      degradationNotes: [],
    })),
  };
});

describe("ProjectsPage", () => {
  it("renders project storyline and previews a meld from selected sessions", async () => {
    render(() => <ProjectsPage />);

    expect(await screen.findByRole("heading", { name: "sba-agentic" })).toBeInTheDocument();
    expect(await screen.findByText("Keep Projects derived from cwd")).toBeInTheDocument();

    fireEvent.click(await screen.findByLabelText("Select Session A"));
    fireEvent.click(screen.getByRole("button", { name: "Preview meld" }));

    expect(previewProjectMeld).toHaveBeenCalledWith("proj-key", ["s1"]);
    expect(await screen.findByText("Bundle text for selected sessions")).toBeInTheDocument();
  });
});
