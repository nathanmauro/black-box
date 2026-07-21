import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
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

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), { target: { value: "projects" } });
    fireEvent.click(await screen.findByRole("option", { name: /Projects/ }));

    expect(navigate).toHaveBeenCalledWith("/projects");
    expect(onClose).toHaveBeenCalled();
  });

  it("offers the coordination Board as a first-class navigation command", async () => {
    const onClose = vi.fn();
    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), { target: { value: "board" } });
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
});
