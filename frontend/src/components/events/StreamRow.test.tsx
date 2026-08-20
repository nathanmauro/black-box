import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { EventFeedItem } from "../../lib/api";
import StreamRow from "./StreamRow";

function feedItem(overrides: Partial<EventFeedItem>): EventFeedItem {
  return {
    id: "event-1",
    sessionId: "session-1",
    source: "claude",
    clientSessionId: "client-1",
    eventType: "PostToolUse",
    observedAt: "2026-08-20T12:00:00Z",
    cwd: "/Users/nathan/Developer/proj/sba-agentic",
    sessionTitle: "Ink slice work",
    ...overrides,
  };
}

function renderRow(item: EventFeedItem, expanded = false) {
  return render(() => (
    <StreamRow item={item} expanded={expanded} sessionHref="/session" onToggle={vi.fn()} />
  ));
}

describe("StreamRow", () => {
  it("renders a chatter row with an ink kind-mark instead of a badge", () => {
    renderRow(feedItem({ toolName: "Bash", toolInputJson: '{"command":"npm test"}' }));

    const mark = document.querySelector(".kind-mark") as HTMLElement;
    expect(mark).toHaveTextContent("run");
    expect(mark.classList.contains("kind-mark--error")).toBe(false);
    expect(document.querySelector(".kind-badge")).not.toBeInTheDocument();
    expect(document.querySelector(".stream-row--landmark")).not.toBeInTheDocument();
  });

  it("wraps the machine-literal argument in a mono headline-arg span", () => {
    renderRow(feedItem({ toolName: "Bash", toolInputJson: '{"command":"npm test"}' }));

    const arg = document.querySelector(".headline-arg") as HTMLElement;
    expect(arg).toHaveTextContent("npm test");
    expect(arg.closest(".stream-row-headline")).not.toBeNull();
  });

  it("colors only the failed chatter mark red", () => {
    renderRow(
      feedItem({ toolName: "Bash", toolInputJson: '{"command":"false"}', toolOutputJson: '{"exit_code":1,"output":"boom"}' }),
    );

    expect(document.querySelector(".kind-mark--error")).toHaveTextContent("run");
  });

  it("renders the generic dot for unknown tools", () => {
    renderRow(feedItem({ toolName: "SendMessage", toolInputJson: null }));

    expect(document.querySelector(".kind-mark")).toHaveTextContent("·");
  });

  it("renders landmark rows with the colored badge and the kind rail", () => {
    renderRow(feedItem({ eventType: "Decision", text: "Chose SQLite as the source of truth" }));

    const row = document.querySelector(".stream-row") as HTMLElement;
    expect(row.classList.contains("stream-row--landmark")).toBe(true);
    expect(row.classList.contains("stream-row--landmark-decision")).toBe(true);
    expect(document.querySelector(".kind-badge--decision")).toHaveTextContent("Decision");
    expect(document.querySelector(".kind-mark")).not.toBeInTheDocument();
  });

  it("shortens the UserPromptSubmit badge to Prompt while keeping its color class", () => {
    renderRow(feedItem({ eventType: "UserPromptSubmit", text: "please fix the stream" }));

    const row = document.querySelector(".stream-row") as HTMLElement;
    expect(row.classList.contains("stream-row--landmark-userpromptsubmit")).toBe(true);
    expect(document.querySelector(".kind-badge--prompt")).toHaveTextContent("Prompt");
  });

  it("keeps the whole row a button with aria-expanded and a quiet time element", () => {
    renderRow(feedItem({ toolName: "Bash", toolInputJson: '{"command":"ls"}' }));

    const row = screen.getByRole("button", { name: /ls in/ });
    expect(row).toHaveAttribute("aria-expanded", "false");
    const time = document.querySelector(".stream-row time") as HTMLElement;
    expect(time).toHaveAttribute("datetime", "2026-08-20T12:00:00Z");
    expect(time).toHaveAttribute("title", "2026-08-20T12:00:00Z");
  });
});
