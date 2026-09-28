import { render, screen } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { EventFeedItem } from "../../lib/api";
import StreamRow from "./StreamRow";

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    A: (props: { href: string; class?: string; children?: Element }) => (
      <a href={props.href} class={props.class}>
        {props.children}
      </a>
    ),
  };
});

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
      feedItem({
        toolName: "Bash",
        toolInputJson: '{"command":"false"}',
        toolOutputJson: '{"exit_code":1,"output":"boom"}',
      }),
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

    // The aria-label is the headline alone — the enclosing run section names the session/cwd.
    const row = screen.getByRole("button", { name: /ls/ });
    expect(row).toHaveAttribute("aria-expanded", "false");
    expect(row.getAttribute("aria-label")).not.toMatch(/ in /);
    const time = document.querySelector(".stream-row time") as HTMLElement;
    expect(time).toHaveAttribute("datetime", "2026-08-20T12:00:00Z");
    expect(time).toHaveAttribute("title", "2026-08-20T12:00:00Z");
  });

  it("shows the row's own cwd inline only when flagged as a run exception", () => {
    const { unmount } = renderRow(
      feedItem({ toolName: "Bash", toolInputJson: '{"command":"ls"}' }),
    );
    expect(document.querySelector(".stream-row-cwd")).not.toBeInTheDocument();
    unmount();

    render(() => (
      <StreamRow
        item={feedItem({ toolName: "Bash", toolInputJson: '{"command":"ls"}' })}
        expanded={false}
        sessionHref="/session"
        onToggle={vi.fn()}
        cwdException
      />
    ));
    expect(document.querySelector(".stream-row-cwd")).toHaveTextContent(
      "~/Developer/proj/sba-agentic",
    );
  });

  it("keeps only the per-event position link on the expanded head", () => {
    renderRow(feedItem({ eventType: "Decision", text: "Chose SQLite" }), true);

    const head = document.querySelector(".stream-row-expanded-head") as HTMLElement;
    expect(screen.getByRole("link", { name: "Open at this event" })).toHaveAttribute(
      "href",
      "/session",
    );
    // Session title and context-zone actions moved to RunHeader (spec §4.3).
    expect(head.textContent).not.toContain("Ink slice work");
  });

  it("renders an Idea as a landmark with a title — oneLiner headline and a structured card", () => {
    renderRow(
      feedItem({
        eventType: "Idea",
        text: "[Idea] Tangent router — File human asides as ideas.\n- origin: agent-proposed\n- legs: 7",
        metadata: {
          kind: "idea",
          title: "Tangent router",
          oneLiner: "File human asides as ideas.",
          origin: "agent-proposed",
          status: "untouched",
          legs: 7,
          quote: "what if the stream led with what I said",
          connects: ["human-turn-first"],
          resumeStep: "Sketch the classifier",
        },
      }),
      true,
    );

    const row = screen.getByRole("button", {
      name: "Tangent router — File human asides as ideas.",
    });
    expect(row).toHaveClass("stream-row--landmark", "stream-row--landmark-idea");
    expect(row.querySelector(".kind-badge--idea")).toHaveTextContent("Idea");
    expect(row.textContent).not.toContain("[Idea]");

    const card = document.querySelector(".event-card--idea") as HTMLElement;
    expect(card).toBeInTheDocument();
    expect(card.textContent).not.toContain("{");
    expect(screen.getByText("agent proposed")).toHaveClass("idea-origin--agent-proposed");
    expect(screen.getByText("untouched")).toHaveClass("idea-status--untouched");
    expect(screen.getByRole("meter", { name: "legs 7/10" })).toBeInTheDocument();
    expect(screen.getByText("what if the stream led with what I said")).toHaveClass("idea-quote");
    expect(screen.getByText("human-turn-first")).toBeInTheDocument();
    expect(screen.getByText("Sketch the classifier")).toBeInTheDocument();
  });
});
