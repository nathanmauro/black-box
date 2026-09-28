import { afterEach, describe, expect, it, vi } from "vitest";
import {
  captureIdea,
  getIdeas,
  previewIdeaMigration,
  type CaptureIdeaRequest,
  type IdeaListResponse,
  type IdeaMigrationResult,
} from "./api";

function stubJson<T>(payload: T) {
  const fetchMock = vi.fn(
    async (_input: RequestInfo | URL, _init?: RequestInit) =>
      new Response(JSON.stringify(payload), { status: 200 }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
  vi.unstubAllGlobals();
});

describe("idea API clients", () => {
  it("lists ideas without a query string when no filter is set", async () => {
    const payload: IdeaListResponse = { items: [], count: 0 };
    const fetchMock = stubJson(payload);

    await expect(getIdeas()).resolves.toEqual(payload);

    expect(fetchMock).toHaveBeenCalledWith("/api/ideas", {
      headers: { Accept: "application/json" },
      signal: undefined,
    });
  });

  it("joins statuses, trims values, and omits empty params", async () => {
    const fetchMock = stubJson({ items: [], count: 0 });

    await getIdeas({
      status: ["untouched", " tracked ", ""],
      origin: "agent-proposed",
      project: " black-box ",
      q: "  tangent router ",
      limit: 50,
    });
    await getIdeas({ status: [], origin: " ", project: "", q: "   " });

    expect(fetchMock.mock.calls[0][0]).toBe(
      "/api/ideas?status=untouched%2Ctracked&origin=agent-proposed&project=black-box&q=tangent+router&limit=50",
    );
    expect(fetchMock.mock.calls[1][0]).toBe("/api/ideas");
  });

  it("captures an idea with the CSRF header and returns the ingest response", async () => {
    const fetchMock = stubJson({
      eventId: "evt-1",
      sessionId: "ses-1",
      source: "manual",
      clientSessionId: "client-1",
      eventType: "Idea",
    });
    document.cookie = "XSRF-TOKEN=idea-token; path=/";
    const request: CaptureIdeaRequest = {
      source: "manual",
      clientSessionId: "client-1",
      title: "Tangent router",
      oneLiner: "File human asides as ideas.",
      origin: "human-aside",
      legs: 6,
      connects: ["human-turn-first"],
    };

    const response = await captureIdea(request);

    expect(response.eventType).toBe("Idea");
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/ideas");
    expect(init?.method).toBe("POST");
    expect(init?.body).toBe(JSON.stringify(request));
    expect(init?.headers).toMatchObject({ "X-XSRF-TOKEN": "idea-token" });
  });

  it("previews the observation migration as a dry run only", async () => {
    const payload: IdeaMigrationResult = {
      apply: false,
      candidates: [
        {
          observationId: "obs-1",
          sessionId: "ses-1",
          idea: { title: "Tangent router", origin: "human-aside" },
          warnings: ["legs missing"],
          alreadyMigrated: false,
          createdEventId: null,
        },
      ],
      created: 0,
      skipped: 0,
    };
    const fetchMock = stubJson(payload);

    await expect(previewIdeaMigration()).resolves.toEqual(payload);

    expect(fetchMock.mock.calls[0][0]).toBe("/api/ideas/migrate-observations?apply=false");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("POST");
  });
});
