import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getProjectSessions,
  getProjectTimeline,
  getRecall,
  previewProjectMeld,
  saveProjectMeld,
  type ProjectMeldSaveRequest,
  type ProjectMeldPreviewResponse,
  type ProjectSavedMeld,
  type ProjectTimelineResponse,
  type RecallResult,
  type AgentSession,
} from "./api";

function stubJson<T>(payload: T) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify(payload), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Phase 2 API helpers", () => {
  it("builds the recall query from scope, window, and selected kinds", async () => {
    const scope = "/Users/nathan/Developer/proj/sba-agentic";
    const payload: RecallResult = {
      scope,
      withinHours: 168,
      kinds: ["decision", "handoff"],
      count: 0,
      items: [],
    };
    const fetchMock = stubJson(payload);

    await expect(getRecall(scope, 168, ["decision", "handoff"])).resolves.toEqual(payload);

    const requestPath = String(fetchMock.mock.calls[0]?.[0]);
    const url = new URL(requestPath, "http://blackbox.test");
    expect(url.pathname).toBe("/api/recall");
    expect(url.searchParams.get("scope")).toBe(scope);
    expect(url.searchParams.get("withinHours")).toBe("168");
    expect(url.searchParams.get("kinds")).toBe("decision,handoff");
  });

  it("passes limit and offset to the project timeline endpoint", async () => {
    const payload: ProjectTimelineResponse = {
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "sba-agentic",
      limit: 125,
      offset: 25,
      count: 0,
      items: [],
    };
    const fetchMock = stubJson(payload);

    await expect(getProjectTimeline("proj/key", 125, 25)).resolves.toEqual(payload);

    const requestPath = String(fetchMock.mock.calls[0]?.[0]);
    const url = new URL(requestPath, "http://blackbox.test");
    expect(url.pathname).toBe("/api/projects/proj%2Fkey/timeline");
    expect(url.searchParams.get("limit")).toBe("125");
    expect(url.searchParams.get("offset")).toBe("25");
  });

  it("passes limit to the project sessions endpoint", async () => {
    const payload: AgentSession[] = [];
    const fetchMock = stubJson(payload);

    await expect(getProjectSessions("proj/key", 2_000)).resolves.toEqual(payload);

    const requestPath = String(fetchMock.mock.calls[0]?.[0]);
    const url = new URL(requestPath, "http://blackbox.test");
    expect(url.pathname).toBe("/api/projects/proj%2Fkey/sessions");
    expect(url.searchParams.get("limit")).toBe("2000");
  });

  it("posts selected session ids to the project meld preview endpoint", async () => {
    const payload: ProjectMeldPreviewResponse = {
      status: "preview",
      executionMode: "bundle",
      provider: "codex",
      model: "default",
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      title: "Meld preview",
      preview: "Preview text",
      bundle: "Bundle text",
      sessions: [],
      sessionCount: 2,
      evidenceCount: 4,
      bundleChars: 120,
      degradationNotes: [],
    };
    const fetchMock = stubJson(payload);

    await expect(previewProjectMeld("proj-key", ["s1", "s2"])).resolves.toEqual(payload);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/projects/proj-key/melds/preview",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ sessionIds: ["s1", "s2"] }),
      }),
    );
  });

  it("posts durable meld saves to the top-level meld endpoint", async () => {
    const request: ProjectMeldSaveRequest = {
      projectKey: "proj-key",
      title: "Saved meld",
      body: "Saved synthesis",
      provider: "local",
      model: "context-bundle",
      executionMode: "export_bundle",
      savedFromPreview: true,
      sessionIds: ["s1", "s2"],
      metadata: { bundleChars: 42 },
    };
    const payload: ProjectSavedMeld = {
      id: "meld-1",
      projectKey: "proj-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      title: "Saved meld",
      body: "Saved synthesis",
      provider: "local",
      model: "context-bundle",
      promptVersion: "project-meld-v1",
      executionMode: "export_bundle",
      savedFromPreview: true,
      metadata: { bundleChars: 42 },
      createdAt: "2026-06-19T15:00:00Z",
      sessions: [],
    };
    const fetchMock = stubJson(payload);

    await expect(saveProjectMeld(request)).resolves.toEqual(payload);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/melds",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify(request),
      }),
    );
  });
});
