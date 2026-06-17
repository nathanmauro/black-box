import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../../lib/api";
import DecisionCard from "./DecisionCard";

describe("DecisionCard", () => {
  it("renders structured decision fields without using raw JSON as the headline", () => {
    const event: AgentEvent = {
      id: "evt-1",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "Decision",
      text: '{"decision":"Use Solid"}',
      metadata: {
        decision: "Use Solid for the UI rewrite",
        rationale: "Signals keep dense UI updates cheap.",
        confidence: 0.82,
        alternatives: ["React", "Vanilla JS"],
        openLoops: ["Backend SSE verification"],
      },
      observedAt: "2026-06-16T20:00:00Z",
    };

    render(() => <DecisionCard event={event} />);

    expect(screen.getByText("Signals keep dense UI updates cheap.")).toBeInTheDocument();
    expect(screen.getByRole("meter")).toBeInTheDocument();
    expect(screen.getByText("Use Solid for the UI rewrite")).toBeInTheDocument();
    expect(screen.queryByText(/^\{/)).not.toBeInTheDocument();
  });
});
