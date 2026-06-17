import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import { getRecall } from "../../lib/api";
import RecallPage from "../RecallPage";

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getRecall: vi.fn(async () => ({
      scope: "sba-agentic",
      withinHours: 168,
      kinds: ["decision", "handoff"],
      count: 1,
      items: [
        {
          eventId: "evt-1",
          kind: "decision",
          source: "codex",
          clientSessionId: "client-1",
          repo: "/Users/nathan/Developer/proj/sba-agentic",
          observedAt: "2026-06-16T20:00:00Z",
          headline: "Use the Hybrid Storyline timeline",
          rationale: "It keeps meaningful project blocks first while preserving raw trace archaeology.",
          alternatives: ["Raw chronological feed", "Summary-only timeline"],
          confidence: 0.82,
          openLoops: ["Alias merge seam"],
          nextAction: "Build the Phase 2 Projects view",
          toAgent: "codex",
        },
      ],
    })),
  };
});

describe("RecallPage", () => {
  it("submits the default structured recall query and renders projected cards", async () => {
    render(() => <RecallPage />);

    fireEvent.input(screen.getByLabelText("Scope"), { target: { value: "sba-agentic" } });
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));

    expect(getRecall).toHaveBeenCalledWith("sba-agentic", 168, ["decision", "handoff"]);
    expect(await screen.findByText("Use the Hybrid Storyline timeline")).toBeInTheDocument();
    expect(screen.getByText("It keeps meaningful project blocks first while preserving raw trace archaeology.")).toBeInTheDocument();
    expect(screen.getByText("Raw chronological feed")).toBeInTheDocument();
    expect(screen.getByText("82%")).toBeInTheDocument();
    expect(screen.queryByText(/eventId/)).not.toBeInTheDocument();
  });
});
