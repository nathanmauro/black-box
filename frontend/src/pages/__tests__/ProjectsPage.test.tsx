import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { previewProjectMeld, saveProjectMeld } from "../../lib/api";
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
          {
            id: "meld-1",
            sourceType: "saved_meld",
            blockType: "synthesis",
            headline: "Saved durable meld",
            text: "Saved synthesis belongs beside raw storyline evidence.",
            eventType: "SavedMeld",
            role: "synthesis",
            source: "meld",
            metadata: { provider: "local", model: "context-bundle", executionMode: "export_bundle" },
            observedAt: "2026-06-16T21:00:00Z",
            sourceSessions: [
              {
                id: "s1",
                source: "codex",
                clientSessionId: "client-1",
                title: "Session A",
                cwd: "/Users/nathan/Developer/proj/sba-agentic",
                eventCount: 24,
                startedAt: "2026-06-16T19:00:00Z",
                lastSeenAt: "2026-06-16T20:00:00Z",
              },
            ],
          },
        ],
      })),
    getProjectMelds: vi.fn(async () => [
      {
        id: "meld-1",
        projectKey: "proj-key",
        canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
        title: "Saved durable meld",
        body: "Saved synthesis belongs beside raw storyline evidence.",
        provider: "local",
        model: "context-bundle",
        promptVersion: "project-meld-v1",
        executionMode: "export_bundle",
        savedFromPreview: true,
        metadata: {},
        createdAt: "2026-06-16T21:00:00Z",
        sessions: [
          {
            id: "s1",
            source: "codex",
            clientSessionId: "client-1",
            title: "Session A",
            cwd: "/Users/nathan/Developer/proj/sba-agentic",
            eventCount: 24,
            startedAt: "2026-06-16T19:00:00Z",
            lastSeenAt: "2026-06-16T20:00:00Z",
          },
        ],
      },
    ]),
    previewProjectMeld: vi.fn(async () => ({
      status: "preview",
      executionMode: "export_bundle",
      provider: "codex",
      model: "default",
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      title: "Meld preview",
      preview: "Preview summary",
      bundle: "Bundle text for selected sessions",
      sessions: [
        {
          id: "s1",
          source: "codex",
          clientSessionId: "client-1",
          title: "Session A",
          cwd: "/Users/nathan/Developer/proj/sba-agentic",
          eventCount: 24,
          startedAt: "2026-06-16T19:00:00Z",
          lastSeenAt: "2026-06-16T20:00:00Z",
        },
      ],
      sessionCount: 1,
      evidenceCount: 3,
      bundleChars: 34,
      degradationNotes: [],
    })),
    saveProjectMeld: vi.fn(async () => ({
      id: "meld-2",
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      title: "Meld preview",
      body: "Preview summary",
      provider: "codex",
      model: "default",
      promptVersion: "project-meld-v1",
      executionMode: "export_bundle",
      savedFromPreview: true,
      metadata: {},
      createdAt: "2026-06-16T21:05:00Z",
      sessions: [],
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
    expect(await screen.findByText("Preview summary")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save meld" }));

    expect(saveProjectMeld).toHaveBeenCalledWith(
      expect.objectContaining({
        projectKey: "proj-key",
        title: "Meld preview",
        body: "Preview summary",
        provider: "codex",
        model: "default",
        executionMode: "export_bundle",
        savedFromPreview: true,
        sessionIds: ["s1"],
      }),
    );
    expect(await screen.findByText("Saved meld.")).toBeInTheDocument();
    expect(await screen.findByText("Saved durable meld")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Session A" }).length).toBeGreaterThan(0);
  });
});
