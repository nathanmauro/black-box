import { fireEvent, render, screen } from "@solidjs/testing-library";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../../lib/api";
import TurnFollowup from "./TurnFollowup";

function event(overrides: Partial<api.AgentEvent>): api.AgentEvent {
  return {
    id: "event-2",
    sessionId: "session-1",
    source: "claude",
    clientSessionId: "client-1",
    eventType: "PostToolUse",
    observedAt: "2026-08-20T12:00:01Z",
    ...overrides,
  };
}

describe("TurnFollowup", () => {
  afterEach(() => vi.restoreAllMocks());

  it("lists what the agent did next and expands one event in place", async () => {
    vi.spyOn(api, "getTurnFollowup").mockResolvedValue({
      eventId: "event-1",
      complete: true,
      events: [
        event({ toolName: "Bash", toolInputJson: '{"command":"npm test"}' }),
        event({ id: "event-3", eventType: "Stop", text: "All green." }),
      ],
    });

    render(() => <TurnFollowup eventId="event-1" />);

    expect(await screen.findByText("Then · 2 events")).toBeInTheDocument();
    expect(screen.getByText("npm test")).toBeInTheDocument();
    expect(api.getTurnFollowup).toHaveBeenCalledWith("event-1");

    const rows = document.querySelectorAll(".turn-followup-row");
    fireEvent.click(rows[1]);
    expect(rows[1]).toHaveAttribute("aria-expanded", "true");
    expect(document.querySelector(".turn-followup-detail")).toHaveTextContent("All green.");
  });

  it("says when the cap cut the turn short or nothing followed", async () => {
    vi.spyOn(api, "getTurnFollowup").mockResolvedValue({
      eventId: "event-1",
      complete: false,
      events: [event({})],
    });
    render(() => <TurnFollowup eventId="event-1" />);
    expect(await screen.findByText("Then · 1+ events")).toBeInTheDocument();
    expect(screen.getByText(/open the session to see the rest/)).toBeInTheDocument();
  });

  it("handles a turn with nothing after it", async () => {
    vi.spyOn(api, "getTurnFollowup").mockResolvedValue({
      eventId: "event-1",
      complete: true,
      events: [],
    });
    render(() => <TurnFollowup eventId="event-1" />);
    expect(await screen.findByText("Nothing recorded after this turn yet.")).toBeInTheDocument();
  });
});
