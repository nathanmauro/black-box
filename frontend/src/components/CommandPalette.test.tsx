import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getSessions, search, type SearchResponse } from "../lib/api";
import { setHumanOnly } from "../lib/humanOnly";
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
  setHumanOnly(false);
  vi.mocked(getSessions).mockClear();
  vi.mocked(search)
    .mockReset()
    .mockImplementation(async (query) => ({
      query,
      local: [],
      elastic: [],
      elasticHealth: { enabled: false, available: false },
    }));
});

afterEach(() => vi.useRealTimers());

describe("CommandPalette", () => {
  it("searches remotely once after rapid typing while local commands update immediately", async () => {
    vi.useFakeTimers();
    render(() => <CommandPalette open onClose={vi.fn()} />);
    const input = screen.getByPlaceholderText("Jump to session or filter Stream...");

    fireEvent.input(input, { target: { value: "projects" } });
    expect(screen.getByRole("option", { name: /Projects/ })).toBeInTheDocument();
    expect(search).not.toHaveBeenCalled();

    for (let length = 1; length <= "performance".length; length += 1) {
      fireEvent.input(input, { target: { value: "performance".slice(0, length) } });
      await vi.advanceTimersByTimeAsync(10);
    }

    expect(search).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(200);
    expect(search).toHaveBeenCalledExactlyOnceWith("performance", 8, false);
  });

  it("cancels pending searches on close, short queries, and unmount", async () => {
    vi.useFakeTimers();
    const [open, setOpen] = createSignal(true);
    const view = render(() => <CommandPalette open={open()} onClose={() => setOpen(false)} />);

    fireEvent.input(screen.getByRole("textbox"), { target: { value: "closed" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Escape" });
    await vi.advanceTimersByTimeAsync(250);
    expect(search).not.toHaveBeenCalled();

    setOpen(true);
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "cleared" } });
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "c" } });
    await vi.advanceTimersByTimeAsync(250);
    expect(search).not.toHaveBeenCalled();

    fireEvent.input(screen.getByRole("textbox"), { target: { value: "reopened" } });
    await vi.advanceTimersByTimeAsync(200);
    expect(search).toHaveBeenCalledExactlyOnceWith("reopened", 8, false);
    fireEvent.input(screen.getByRole("textbox"), { target: { value: "unmounted" } });
    view.unmount();
    await vi.advanceTimersByTimeAsync(250);
    expect(search).toHaveBeenCalledTimes(1);
  });

  it("hides completed and late results as soon as the query or human mode changes", async () => {
    vi.useFakeTimers();
    const response: SearchResponse = {
      query: "earlier",
      local: [
        {
          id: "stale-event",
          sessionId: "stale-session",
          source: "claude",
          clientSessionId: "stale-client",
          eventType: "Observation",
          text: "An earlier search result",
          humanText: "An earlier human result",
          observedAt: "2026-07-01T12:00:00Z",
        },
      ],
      elastic: [],
      elasticHealth: { enabled: false, available: false },
    };
    let finishLateSearch!: (result: SearchResponse) => void;

    vi.mocked(search)
      .mockResolvedValueOnce(response)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishLateSearch = resolve;
          }),
      );
    render(() => <CommandPalette open onClose={vi.fn()} />);
    const input = screen.getByRole("textbox");

    fireEvent.input(input, { target: { value: "earlier" } });
    await vi.advanceTimersByTimeAsync(200);
    expect(screen.getByRole("option", { name: /An earlier search result/ })).toBeInTheDocument();
    setHumanOnly(true);
    expect(screen.queryByRole("option", { name: /An earlier/ })).not.toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(200);
    expect(search).toHaveBeenLastCalledWith("earlier", 8, true);
    fireEvent.input(input, { target: { value: "latest" } });
    finishLateSearch(response);
    await vi.advanceTimersByTimeAsync(0);
    expect(screen.queryByRole("option", { name: /An earlier/ })).not.toBeInTheDocument();
    await vi.advanceTimersByTimeAsync(200);
    expect(search).toHaveBeenLastCalledWith("latest", 8, true);
  });

  it.each([
    ["Long event rationale.\n\n" + "Readable details. ".repeat(12), "Long event rationale."],
    ["x".repeat(118) + "😀ending", "x".repeat(118)],
  ])("keeps a bounded readable event label and exact navigation", async (text, prefix) => {
    vi.mocked(search).mockResolvedValueOnce({
      query: "fixture",
      local: [
        {
          id: "event-long",
          sessionId: "session-long",
          source: "codex",
          clientSessionId: "long",
          eventType: "Observation",
          text,
          observedAt: "2026-07-01T12:00:00Z",
        },
      ],
      elastic: [],
      elasticHealth: { enabled: false, available: false },
    });
    render(() => <CommandPalette open onClose={vi.fn()} />);
    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "fixture" },
    });
    const option = await screen.findByRole("option", {
      name: new RegExp(prefix.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")),
    });
    const label = option.querySelector("strong")!.textContent!;

    expect(label.startsWith(prefix)).toBe(true);
    expect(label.length).toBeLessThanOrEqual(120);
    expect(label).toMatch(/…$/);
    expect(label).not.toContain("\n");
    expect(label).not.toMatch(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/u);
    fireEvent.click(option);
    expect(navigate).toHaveBeenCalledWith("/?view=browse&session=session-long&event=event-long");
  });

  it("clears fallback event results when the query becomes too short", async () => {
    setHumanOnly(true);
    vi.mocked(search).mockResolvedValueOnce({
      query: "aside",
      local: [
        {
          id: "event-aside",
          sessionId: "session-aside",
          source: "codex",
          clientSessionId: "client-aside",
          eventType: "UserPromptSubmit",
          text: "Raw aside",
          humanText: "Unique cleaned human aside",
          observedAt: "2026-07-01T12:00:00Z",
        },
      ],
      elastic: [],
      elasticHealth: { enabled: false, available: false },
    });
    render(() => <CommandPalette open onClose={() => {}} />);
    const input = screen.getByPlaceholderText("Jump to session or filter Stream...");

    fireEvent.input(input, { target: { value: "aside" } });
    await screen.findByRole("option", { name: /Unique cleaned human aside/ });
    fireEvent.input(input, { target: { value: "" } });
    await waitFor(() =>
      expect(
        screen.queryByRole("option", { name: /Unique cleaned human aside/ }),
      ).not.toBeInTheDocument(),
    );
  });

  it("refreshes session picks and fallback search when human mode changes", async () => {
    render(() => <CommandPalette open onClose={() => {}} />);
    await screen.findByRole("option", { name: /Focused session/ });
    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "aside" },
    });
    await waitFor(() => expect(search).toHaveBeenLastCalledWith("aside", 8, false));
    setHumanOnly(true);
    await waitFor(() => expect(getSessions).toHaveBeenLastCalledWith(120, false, true));
    await waitFor(() => expect(search).toHaveBeenLastCalledWith("aside", 8, true));
    expect(screen.queryByRole("option", { name: /Focused session/ })).not.toBeInTheDocument();
    setHumanOnly(false);
    await waitFor(() => expect(getSessions).toHaveBeenLastCalledWith(120, false, false));
  });

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

  it("offers the Ideas view for an 'open ideas' query", async () => {
    const onClose = vi.fn();

    render(() => <CommandPalette open onClose={onClose} />);

    fireEvent.input(screen.getByPlaceholderText("Jump to session or filter Stream..."), {
      target: { value: "open ideas" },
    });
    fireEvent.click(await screen.findByRole("option", { name: /Ideas.*open ideas/ }));

    expect(navigate).toHaveBeenCalledWith("/ideas");
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
