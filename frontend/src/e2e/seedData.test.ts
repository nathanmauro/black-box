import { describe, expect, it, vi } from "vitest";
import {
  E2E_SEED_EVENTS,
  E2E_SEED_IDEAS,
  E2E_SEED_PROJECTION,
  assertSafeSeedBaseUrl,
  seedBlackBoxE2e,
  seedE2eIdeas,
} from "./seedData";

describe("e2e seed data", () => {
  it("contains the exact deterministic records asserted by smoke.spec.ts", () => {
    expect(E2E_SEED_EVENTS).toHaveLength(7);
    expect(E2E_SEED_EVENTS.map((event) => event.metadata?.title)).toEqual([
      "UI rewrite kickoff",
      "Frontend build",
      "Claude design prompt",
      "Release worktree handoff",
      "Human aside",
      "Machine notification",
      "Human turns tool event",
    ]);

    const codexDecision = E2E_SEED_EVENTS.find((event) => event.eventType === "Decision");
    expect(codexDecision).toMatchObject({
      source: "codex",
      clientSessionId: "black-box-e2e-codex-ui-rewrite",
      cwd: "/tmp/black-box-e2e",
      metadata: {
        title: "UI rewrite kickoff",
        kind: "decision",
        decision: "Use SolidJS + Vite for the UI rewrite",
        rationale: "Matches agent-observatory; stays self-contained in the jar at runtime",
        repo: "/tmp/black-box-e2e",
      },
    });
    expect(codexDecision?.metadata?.openLoops).toContain("keep the reproducible gate documented");
    expect(codexDecision?.metadata?.openLoops?.join(" ")).not.toMatch(/\bopen loops\b/i);

    const claudePrompt = E2E_SEED_EVENTS.find((event) => event.source === "claude");
    expect(claudePrompt?.text).toContain("Rewrite the UI to match agent-observatory");

    const worktreeHandoff = E2E_SEED_EVENTS.find(
      (event) => event.metadata.title === "Release worktree handoff",
    );
    expect(worktreeHandoff).toMatchObject({
      eventType: "Handoff",
      cwd: "/tmp/black-box-e2e/.worktrees/release",
      metadata: {
        nextAction: "Review the catalog-backed workspace",
        repo: "/tmp/black-box-e2e/.worktrees/release",
      },
    });

    expect(E2E_SEED_PROJECTION).toMatchObject({
      source: "codex",
      clientSessionId: "black-box-e2e-codex-projection",
      repo: "/tmp/black-box-e2e",
      paths: [
        { title: "Polish trajectory graph", confidence: 0.74 },
        { title: "Expand projection recall", confidence: 0.58 },
        { title: "Retire parked graph page", confidence: 0.31 },
      ],
    });

    expect(E2E_SEED_IDEAS).toHaveLength(2);
    expect(E2E_SEED_IDEAS[0]).toMatchObject({
      title: "Tangent router for human asides",
      origin: "agent-proposed",
      status: "untouched",
      legs: 7,
      repo: "/tmp/black-box-e2e",
    });
    expect(E2E_SEED_IDEAS[0].quote).toBeTruthy();
    expect(E2E_SEED_IDEAS[1]).toMatchObject({
      title: "Evidence capture kind",
      origin: "human-aside",
      status: "tracked",
    });
  });

  it("refuses to seed the production service port", () => {
    expect(() => assertSafeSeedBaseUrl("http://127.0.0.1:8766")).toThrow(/Refusing/);
    expect(() => assertSafeSeedBaseUrl("http://127.0.0.1:8799")).not.toThrow();
    expect(() => assertSafeSeedBaseUrl("http://127.0.0.1:8800")).toThrow(/port 8799/);
    expect(() => assertSafeSeedBaseUrl("http://example.com:8799")).toThrow(/non-local/);
  });

  it("posts each record to the real ingest endpoint", async () => {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname === "/api/projects") {
        return new Response(
          JSON.stringify([{ projectKey: "project-key", canonicalKey: "/tmp/black-box-e2e" }]),
          { status: 200 },
        );
      }
      if (url.pathname === "/api/projects/project-key/sessions") {
        return new Response(JSON.stringify([{ id: "session-root" }, { id: "session-worktree" }]), {
          status: 200,
        });
      }
      if (url.pathname === "/api/melds" && init?.method === "POST") {
        return new Response(JSON.stringify({ id: "meld-id" }), { status: 200 });
      }
      return new Response(JSON.stringify({ id: "event-id" }), { status: 200 });
    });

    await seedBlackBoxE2e("http://127.0.0.1:8799", fetchMock);

    expect(fetchMock).toHaveBeenCalledTimes(E2E_SEED_EVENTS.length + 4);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/events",
      expect.objectContaining({
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(E2E_SEED_EVENTS[0]),
      }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/projections",
      expect.objectContaining({
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(E2E_SEED_PROJECTION),
      }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/melds",
      expect.objectContaining({
        method: "POST",
        body: expect.stringContaining('"title":"Release workspace synthesis"'),
      }),
    );
    // The global seed never needs POST /api/ideas: ideas.spec.ts seeds its own ideas.
    const paths = fetchMock.mock.calls.map(([input]) => new URL(input).pathname);
    expect(paths).not.toContain("/api/ideas");
  });

  it("seeds ideas separately so a missing ideas endpoint cannot abort global setup", async () => {
    const fetchMock = vi.fn(
      async (_input: string, _init?: RequestInit) =>
        new Response(JSON.stringify({ id: "event-id" }), { status: 200 }),
    );

    await seedE2eIdeas("http://127.0.0.1:8799", fetchMock);

    expect(fetchMock).toHaveBeenCalledTimes(E2E_SEED_IDEAS.length);
    for (const idea of E2E_SEED_IDEAS) {
      expect(fetchMock).toHaveBeenCalledWith(
        "http://127.0.0.1:8799/api/ideas",
        expect.objectContaining({
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(idea),
        }),
      );
    }

    const missing = vi.fn(
      async (_input: string, _init?: RequestInit) =>
        new Response("", { status: 404, statusText: "Not Found" }),
    );
    await expect(seedE2eIdeas("http://127.0.0.1:8799", missing)).rejects.toThrow(
      'Failed to seed the idea "Tangent router for human asides": HTTP 404 Not Found',
    );
    await expect(seedE2eIdeas("http://127.0.0.1:8766", fetchMock)).rejects.toThrow(/Refusing/);
  });
});
