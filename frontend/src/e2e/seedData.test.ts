import { describe, expect, it, vi } from "vitest";
import { E2E_SEED_EVENTS, assertSafeSeedBaseUrl, seedBlackBoxE2e } from "./seedData";

describe("e2e seed data", () => {
  it("contains the exact deterministic records asserted by smoke.spec.ts", () => {
    expect(E2E_SEED_EVENTS).toHaveLength(3);
    expect(E2E_SEED_EVENTS.map((event) => event.metadata?.title)).toEqual([
      "UI rewrite kickoff",
      "Frontend build",
      "Claude design prompt",
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
  });

  it("refuses to seed the production service port", () => {
    expect(() => assertSafeSeedBaseUrl("http://127.0.0.1:8766")).toThrow(/Refusing/);
    expect(() => assertSafeSeedBaseUrl("http://127.0.0.1:8799")).not.toThrow();
  });

  it("posts each record to the real ingest endpoint", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ id: "event-id" }), { status: 200 }));

    await seedBlackBoxE2e("http://127.0.0.1:8799", fetchMock);

    expect(fetchMock).toHaveBeenCalledTimes(E2E_SEED_EVENTS.length);
    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8799/api/events",
      expect.objectContaining({
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(E2E_SEED_EVENTS[0]),
      }),
    );
  });
});
