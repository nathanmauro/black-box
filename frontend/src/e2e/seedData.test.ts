import { describe, expect, it, vi } from "vitest";
import { E2E_SEED_EVENTS, assertSafeSeedBaseUrl, seedBlackBoxE2e } from "./seedData";

describe("e2e seed data", () => {
  it("contains the exact deterministic records asserted by smoke.spec.ts", () => {
    expect(E2E_SEED_EVENTS).toHaveLength(4);
    expect(E2E_SEED_EVENTS.map((event) => event.metadata?.title)).toEqual([
      "UI rewrite kickoff",
      "Frontend build",
      "Claude design prompt",
      "Release worktree handoff",
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

    const worktreeHandoff = E2E_SEED_EVENTS.find((event) => event.metadata.title === "Release worktree handoff");
    expect(worktreeHandoff).toMatchObject({
      eventType: "Handoff",
      cwd: "/tmp/black-box-e2e/.worktrees/release",
      metadata: { repo: "/tmp/black-box-e2e/.worktrees/release" },
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
        return new Response(JSON.stringify([{ projectKey: "project-key", canonicalKey: "/tmp/black-box-e2e" }]), { status: 200 });
      }
      if (url.pathname === "/api/projects/project-key/sessions") {
        return new Response(JSON.stringify([{ id: "session-root" }, { id: "session-worktree" }]), { status: 200 });
      }
      if (url.pathname === "/api/melds" && init?.method === "POST") {
        return new Response(JSON.stringify({ id: "meld-id" }), { status: 200 });
      }
      return new Response(JSON.stringify({ id: "event-id" }), { status: 200 });
    });

    await seedBlackBoxE2e("http://127.0.0.1:8799", fetchMock);

    expect(fetchMock).toHaveBeenCalledTimes(E2E_SEED_EVENTS.length + 3);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/events",
      expect.objectContaining({
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(E2E_SEED_EVENTS[0]),
      }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/melds",
      expect.objectContaining({
        method: "POST",
        body: expect.stringContaining('"title":"Release workspace synthesis"'),
      }),
    );
  });
});
