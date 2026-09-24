import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { search, type AgentEvent } from "../lib/api";
import CommandPalette from "./CommandPalette";

const navigate = vi.fn();

vi.mock("@solidjs/router", () => ({
  useNavigate: () => navigate,
}));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return {
    ...actual,
    getSessions: vi.fn(async () => [
      {
        id: "session-1",
        source: "codex",
        clientSessionId: "client-1",
        title: "Focused session",
        cwd: "/Users/nathan/Developer/proj/sba-agentic",
        summary: null,
        startedAt: "2026-07-01T12:00:00Z",
        lastSeenAt: "2026-07-01T12:05:00Z",
        eventCount: 3,
      },
    ]),
    search: vi.fn(async (query: string) => ({
      query,
      local: [],
      elastic: [],
      elasticHealth: {},
    })),
  };
});

beforeEach(() => {
  navigate.mockReset();
});

describe("CommandPalette", () => {
  it("offers Projects as a first-class navigation command", async () => {
    const onClose = vi.fn();
    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "projects" },
    });
    fireEvent.click(await screen.findByRole("option", { name: /Projects/ }));

    expect(navigate).toHaveBeenCalledWith("/projects");
    expect(onClose).toHaveBeenCalled();
  });

  it("offers the coordination Board as a first-class navigation command", async () => {
    const onClose = vi.fn();
    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "board" },
    });
    fireEvent.click(await screen.findByRole("option", { name: /Board/ }));

    expect(navigate).toHaveBeenCalledWith("/board");
    expect(onClose).toHaveBeenCalled();
  });

  it("opens session picks in the Activity browse view", async () => {
    const onClose = vi.fn();
    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.click(await screen.findByRole("option", { name: /Focused session/ }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/?view=browse&session=session-1"));
    expect(onClose).toHaveBeenCalled();
  });

  it("sends free-form queries to the Activity Stream", async () => {
    const onClose = vi.fn();
    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "kind:PostToolUse" },
    });
    fireEvent.click(await screen.findByRole("option", { name: /Filter Stream for/ }));

    expect(navigate).toHaveBeenCalledWith("/?q=kind%3APostToolUse");
    expect(onClose).toHaveBeenCalled();
  });

  it("labels long-text events with a trimmed excerpt instead of the bare kind", async () => {
    const longText = `Use JWT access tokens with refresh-token rotation.\n\nWhy: ${"stateless and horizontally scalable; ".repeat(6)}`;
    vi.mocked(search).mockResolvedValueOnce({
      query: "jwt",
      local: [
        {
          id: "evt-1",
          sessionId: "session-1",
          source: "codex",
          clientSessionId: "client-1",
          eventType: "Decision",
          role: "assistant",
          text: longText,
          observedAt: "2026-07-01T12:00:00Z",
        } as AgentEvent,
      ],
      elastic: [],
      elasticHealth: {},
    });
    render(() => <CommandPalette open onClose={vi.fn()} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "jwt" },
    });

    const option = await screen.findByRole("option", { name: /Use JWT access tokens/ });
    const label = option.querySelector("strong") as HTMLElement;
    expect(label.textContent).toMatch(/…$/);
    expect(label.textContent!.length).toBeLessThanOrEqual(120);
    expect(label.textContent).not.toContain("\n");
  });
});
