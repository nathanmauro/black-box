import { fireEvent, render, screen } from "@solidjs/testing-library";
import { describe, expect, it } from "vitest";
import type { AgentEvent } from "../../lib/api";
import { CodeNavigationContext } from "../../lib/codeNavigation";
import DecisionCard from "./DecisionCard";
import EventRow, { eventHeadline } from "./EventRow";

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
  it("renders bash events as a structured command block with lazy output", () => {
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
      toolOutputJson: JSON.stringify(
        "Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed",
      ),
      text: "Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed",
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => <EventRow event={event} />);

    expect(container.querySelector(".tone-pill")?.textContent).toBe("Bash");
    expect(container.querySelector(".bash-command")?.textContent).toBe("npm test\nnpm run build");
    expect(screen.getByText("exit 0")).toBeInTheDocument();
    expect(screen.getByText("Output (15 chars)")).toBeInTheDocument();
    expect(screen.queryByText("42 tests passed")).toBeNull(); // lazy until opened

    const details = container.querySelector(".detail-block--output") as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event("toggle"));
    expect(screen.getByText("42 tests passed")).toBeInTheDocument();
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
      toolInputJson: JSON.stringify({
        command:
          "*** Begin Patch\n*** Update File: /Users/nathan/Developer/proj/sba-agentic/README.md\n*** End Patch",
      }),
      observedAt: "2026-06-16T20:00:00Z",
    };

    const { container } = render(() => (
      <CodeNavigationContext.Provider
        value={{
          scopes: () => [
            {
              projectKey: "sba-key",
              root: "/Users/nathan/Developer/proj/sba-agentic",
            },
          ],
          catalogStatus: () => "ready",
          catalogError: () => null,
          refreshCatalog: () => undefined,
        }}
      >
        <EventRow event={event} />
      </CodeNavigationContext.Provider>
    ));

    expect(container.querySelector(".tone-pill")?.textContent).toBe("Patch");
    expect(
      screen.getByRole("button", {
        name: "Open /Users/nathan/Developer/proj/sba-agentic/README.md in editor",
      }),
    ).toBeInTheDocument();
    expect(eventHeadline(event)).toBe("Patch ~/Developer/proj/sba-agentic/README.md");
  });

  it("keeps unknown tools on the generic ToolPayload path", () => {
    const event: AgentEvent = {
      id: "evt-unknown",
      sessionId: "ses-1",
      source: "claude",
      clientSessionId: "client-1",
      eventType: "PostToolUse",
      role: "tool",
      toolName: "SomeNewTool",
      toolInputJson: JSON.stringify({ query: "hello world" }),
      observedAt: "2026-06-16T20:00:00Z",
    };

    render(() => <EventRow event={event} />);

    expect(screen.getByRole("region", { name: "Input" })).toBeInTheDocument();
    expect(screen.getByText("Query")).toBeInTheDocument();
    expect(
      screen.getByText("hello world", { selector: ".tool-payload-inline" }),
    ).toBeInTheDocument();
  });

  it("collapses long primary reader messages until the user expands them", async () => {
    const longPrompt = Array.from(
      { length: 18 },
      (_, index) => `Requirement ${index + 1}: keep the reader focused on prompts and responses.`,
    ).join("\n\n");
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
