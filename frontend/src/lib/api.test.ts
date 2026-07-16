import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  claimNextTask,
  completeTask,
  createSessionLink,
  createSpec,
  createTaskAnnotation,
  deleteProjectAlias,
  enqueueTask,
  getSessionDag,
  getSessionLinks,
  getSpec,
  getTaskDag,
  getTaskEvents,
  listTasks,
  mergeProjectAlias,
  updateTaskStatus,
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
  type TaskChange,
  type CompleteTaskRequest,
  type CreateAnnotationRequest,
  type CreateSessionLinkRequest,
  type DagResponse,
  type SessionLink,
  type SessionLinksResponse,
  type TaskAnnotation,
  type TaskEvent,
  type UpdateTaskStatusRequest,
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

  it("puts explicit project aliases using canonical raw scopes", async () => {
    const payload = {
      id: "alias-1",
      aliasKey: "/tmp/worktree/black-box",
      canonicalKey: "/repos/black-box",
      source: "manual",
      createdAt: "2026-07-15T17:00:00Z",
    };
    const fetchMock = stubJson(payload);

    await expect(mergeProjectAlias(payload.aliasKey, payload.canonicalKey)).resolves.toEqual(payload);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/project-aliases",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ aliasKey: payload.aliasKey, canonicalKey: payload.canonicalKey }),
      }),
    );
  });

  it("deletes a manual project alias by its raw scope", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(deleteProjectAlias("/tmp/worktree/black box")).resolves.toBeUndefined();
    const requestPath = String(fetchMock.mock.calls[0]?.[0]);
    const url = new URL(requestPath, "http://blackbox.test");
    expect(url.pathname).toBe("/api/project-aliases");
    expect(url.searchParams.get("aliasKey")).toBe("/tmp/worktree/black box");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("DELETE");
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

describe("task API helpers", () => {
  const change: TaskChange = {
    snapshot: {
      task: {
        id: "task-1",
        specId: "spec-1",
        projectKey: "black-box",
        title: "Build the Board",
        lane: "codex",
        status: "open",
        priority: 7,
        createdBy: "planner",
        claimedBy: null,
        blockedReason: null,
        resultHandoffId: null,
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
      spec: {
        id: "spec-1",
        projectKey: "black-box",
        title: "Agent loop",
        body: "Frozen contract",
        specRef: null,
        status: "active",
        createdBy: "planner",
        createdAt: "2026-07-10T00:00:00Z",
        updatedAt: "2026-07-10T00:00:00Z",
      },
    },
    event: {
      id: "event-1",
      taskId: "task-1",
      type: "task.created",
      actor: "planner",
      fromStatus: null,
      toStatus: "open",
      detail: null,
      observedAt: "2026-07-10T00:00:00Z",
    },
  };

  it("encodes each present list filter once and omits absent values", async () => {
    const fetchMock = stubJson([change.snapshot]);

    await listTasks({ projectKey: "black box", lane: "codex", status: "open", limit: 40 });

    const url = new URL(String(fetchMock.mock.calls[0]?.[0]), "http://blackbox.test");
    expect(url.pathname).toBe("/api/tasks");
    expect(url.searchParams.getAll("projectKey")).toEqual(["black box"]);
    expect(url.searchParams.getAll("lane")).toEqual(["codex"]);
    expect(url.searchParams.getAll("status")).toEqual(["open"]);
    expect(url.searchParams.getAll("limit")).toEqual(["40"]);
    expect(url.search).not.toContain("undefined");

    await listTasks({ projectKey: undefined, lane: undefined, status: undefined, limit: undefined });
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe("/api/tasks");
  });

  it("maps an empty 204 claim to null without reading JSON", async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(claimNextTask({ lane: "codex", agent: "worker-1" })).resolves.toBeNull();
  });

  it("surfaces typed API error messages and HTTP status", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      error: {
        status: 409,
        type: "claimant_mismatch",
        message: "Task is owned by another agent",
      },
    }), {
      status: 409,
      statusText: "Conflict",
      headers: { "Content-Type": "application/json" },
    })));

    const error = await updateTaskStatus("task/1", { actor: "intruder", status: "blocked", blockedReason: "waiting" })
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      message: "Task is owned by another agent",
      status: 409,
      type: "claimant_mismatch",
    });
  });

  it("uses the seven REST task contract routes and typed bodies", async () => {
    const fetchMock = stubJson(change);

    await createSpec({ projectKey: "black-box", title: "Spec", body: "Frozen", specRef: null, actor: "planner" });
    await getSpec("spec/1");
    await enqueueTask({ specId: "spec-1", title: "Task", lane: "codex", priority: 7, actor: "planner" });
    await claimNextTask({ lane: "codex", agent: "worker" });
    await updateTaskStatus("task/1", { actor: "worker", status: "blocked", blockedReason: "dependency" });
    await completeTask("task/1", {
      actor: "worker",
      source: "codex",
      clientSessionId: "session-1",
      summary: "Done",
      openLoops: [],
      nextAction: "Review",
    });

    expect(fetchMock.mock.calls.map(([path]) => String(path))).toEqual([
      "/api/specs",
      "/api/specs/spec%2F1",
      "/api/tasks",
      "/api/tasks/claim",
      "/api/tasks/task%2F1",
      "/api/tasks/task%2F1/complete",
    ]);
    expect(fetchMock.mock.calls.map(([, init]) => init?.method)).toEqual([
      "POST",
      undefined,
      "POST",
      "POST",
      "PATCH",
      "POST",
    ]);
  });

  it("narrows manual updates and requires a completion next action", () => {
    expectTypeOf<UpdateTaskStatusRequest["status"]>()
      .toEqualTypeOf<"blocked" | "open" | "cancelled">();
    expectTypeOf<CompleteTaskRequest>()
      .toMatchTypeOf<{ nextAction: string }>();
  });

  it("posts task annotations to the encoded task route", async () => {
    const request: CreateAnnotationRequest = {
      actor: "worker-1",
      kind: "progress",
      text: "Queue wiring is complete",
      dataJson: { tests: 12 },
    };
    const payload: TaskAnnotation = {
      id: "annotation-1",
      taskId: "task/1",
      ...request,
      observedAt: "2026-07-15T18:00:00Z",
    };
    const fetchMock = stubJson(payload);

    await expect(createTaskAnnotation("task/1", request)).resolves.toEqual(payload);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/tasks/task%2F1/annotations",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify(request),
      }),
    );
  });

  it("gets the full event timeline from the encoded task route", async () => {
    const payload: TaskEvent[] = [{
      id: "event-1",
      taskId: "task/1",
      type: "task.note",
      actor: "worker-1",
      fromStatus: null,
      toStatus: null,
      detail: { kind: "note", text: "Checking in", dataJson: null },
      observedAt: "2026-07-15T18:00:00Z",
    }];
    const fetchMock = stubJson(payload);

    await expect(getTaskEvents("task/1")).resolves.toEqual(payload);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/tasks/task%2F1/events");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
  });

  it("posts session links with the typed request body", async () => {
    const request: CreateSessionLinkRequest = {
      parentSessionId: "session-parent",
      childSessionId: "session-child",
      linkType: "spawned",
      taskId: "task-1",
    };
    const payload: SessionLink = {
      linkId: "link-1",
      ...request,
      createdAt: "2026-07-15T18:00:00Z",
      session: { id: "session-child", title: "Child worker", source: "codex" },
    };
    const fetchMock = stubJson(payload);

    await expect(createSessionLink(request)).resolves.toEqual(payload);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/session-links",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify(request),
      }),
    );
  });

  it("gets session link parents and children from the encoded session route", async () => {
    const payload: SessionLinksResponse = { parents: [], children: [] };
    const fetchMock = stubJson(payload);

    await expect(getSessionLinks("session/1")).resolves.toEqual(payload);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/sessions/session%2F1/links");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
  });

  it("gets a task DAG from the encoded task route", async () => {
    const payload: DagResponse = {
      nodes: [{ id: "task:task/1", type: "task", label: "Build Board", status: "open", ref: "task/1" }],
      edges: [],
    };
    const fetchMock = stubJson(payload);

    await expect(getTaskDag("task/1")).resolves.toEqual(payload);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/tasks/task%2F1/dag");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
  });

  it("gets a session DAG with a properly encoded sessionId query", async () => {
    const payload: DagResponse = { nodes: [], edges: [] };
    const fetchMock = stubJson(payload);

    await expect(getSessionDag("session/with space")).resolves.toEqual(payload);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/dag?sessionId=session%2Fwith%20space");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBeUndefined();
  });
});
