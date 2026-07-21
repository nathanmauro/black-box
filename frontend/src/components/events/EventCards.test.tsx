import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../../lib/api";
import DecisionCard from "./DecisionCard";
import EventRow from "./EventRow";

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

describe("EventRow", () => {
  it("renders command payloads as readable fields with decoded multiline output", () => {
    const event: AgentEvent = {
      id: "evt-command",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "Bash",
      toolInputJson: JSON.stringify({
        command: "npm test\nnpm run build",
        cwd: "/Users/nathan/Developer/proj/sba-agentic/frontend",
        timeout: 30_000,
      }),
      toolOutputJson: JSON.stringify("Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed"),
      text: "Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed",
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => <EventRow event={event} />);

    expect(screen.getByRole("region", { name: "Input" })).toBeInTheDocument();
    expect(screen.getByText("Command")).toBeInTheDocument();
    expect(screen.getByText("Cwd")).toBeInTheDocument();
    expect(screen.getByText("Timeout")).toBeInTheDocument();
    expect(screen.getByText("Exit code")).toBeInTheDocument();
    expect(screen.getByText("Wall time")).toBeInTheDocument();
    expect(screen.getByText("Output")).toBeInTheDocument();
    const blocks = Array.from(container.querySelectorAll(".tool-payload-block"));
    expect(blocks.some((block) => block.textContent === "npm test\nnpm run build")).toBe(true);
    expect(screen.getAllByText("42 tests passed")).toHaveLength(1);
    expect(container.textContent).not.toContain("\\nWall time");
  });

  it("summarizes patch commands by their target file", () => {
    const event: AgentEvent = {
      id: "evt-patch",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "apply_patch",
      toolInputJson: JSON.stringify({ command: "*** Begin Patch\n*** Update File: /Users/nathan/Developer/proj/sba-agentic/README.md\n*** End Patch" }),
      observedAt: "2026-06-16T20:00:00Z",
    };

    render(() => <EventRow event={event} />);

    expect(screen.getByText("Patch ~/Developer/proj/sba-agentic/README.md")).toBeInTheDocument();
  });

  it("collapses long primary reader messages until the user expands them", async () => {
    const longPrompt = Array.from({ length: 18 }, (_, index) => `Requirement ${index + 1}: keep the reader focused on prompts and responses.`)
      .join("\n\n");
    const event: AgentEvent = {
      id: "evt-long",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "UserPromptSubmit",
      role: "user",
      text: longPrompt,
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => <EventRow event={event} />);

    expect(container.querySelector(".reader-text")).toHaveClass("reader-text--collapsed");
    const toggle = screen.getByRole("button", { name: "Show full message" });
    expect(toggle).toBeInTheDocument();

    fireEvent.click(toggle);
    expect(container.querySelector(".reader-text")).not.toHaveClass("reader-text--collapsed");
    expect(screen.getByRole("button", { name: "Collapse message" })).toBeInTheDocument();
  });

  it("renders short primary reader messages without compaction controls", () => {
    const event: AgentEvent = {
      id: "evt-short",
      sessionId: "ses-1",
      source: "codex",
      clientSessionId: "client-1",
      eventType: "AssistantMessage",
      role: "assistant",
      text: "Short response stays direct.",
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => <EventRow event={event} />);

    expect(container.querySelector(".reader-text")).not.toHaveClass("reader-text--collapsed");
    expect(screen.queryByRole("button", { name: "Show full message" })).not.toBeInTheDocument();
  });
});
