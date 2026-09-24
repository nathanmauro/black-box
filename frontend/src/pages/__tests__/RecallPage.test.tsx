import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getRecall } from "../../lib/api";
import RecallPage from "../RecallPage";

let params: { scope?: string };
let updateParams: SetStoreFunction<{ scope?: string }>;
const setParams = vi.fn();

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element; class?: string; "aria-label"?: string }) => (
    <a href={props.href} class={props.class} aria-label={props["aria-label"]}>
      {props.children}
    </a>
  ),
  useSearchParams: () => [params, setParams],
}));

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
          sessionId: "session-1",
          kind: "decision",
          source: "codex",
          clientSessionId: "client-1",
          repo: null,
          observedAt: "2026-06-16T20:00:00Z",
          headline: "Use the Hybrid Storyline timeline",
          rationale:
            "It keeps meaningful project blocks first while preserving raw trace archaeology.",
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

beforeEach(() => {
  [params, updateParams] = createStore<{ scope?: string }>({});
  setParams.mockReset();
  setParams.mockImplementation((next: { scope?: string }) => updateParams(next));
  vi.mocked(getRecall).mockClear();
});

describe("RecallPage", () => {
  it.each([
    ["Three months · 90d", 2160],
    ["Six months · 180d", 4320],
  ])(
    "submits the %s rolling window without changing the selected scope or kinds",
    async (label, hours) => {
      render(() => <RecallPage />);
      fireEvent.input(screen.getByLabelText("Scope"), {
        target: { value: "/workspace/example-app" },
      });
      fireEvent.click(screen.getByRole("radio", { name: label }));
      fireEvent.click(screen.getByRole("checkbox", { name: "Observation" }));
      fireEvent.click(screen.getByRole("button", { name: "Run recall" }));

      expect(getRecall).toHaveBeenCalledWith("/workspace/example-app", hours, [
        "decision",
        "handoff",
        "observation",
      ]);
      expect(await screen.findByText("Use the Hybrid Storyline timeline")).toBeInTheDocument();
    },
  );

  it("offers named help disclosures that close with Escape without submitting recall", () => {
    render(() => <RecallPage />);
    for (const label of ["Help with scope", "Help with time windows", "Help with filters"]) {
      const trigger = screen.getByLabelText(label);
      const disclosure = trigger.closest("details")!;
      fireEvent.click(trigger);
      expect(disclosure).toHaveAttribute("open");
      fireEvent.keyDown(trigger, { key: "Escape" });
      expect(disclosure).not.toHaveAttribute("open");
      expect(trigger).toHaveFocus();
    }
    expect(getRecall).not.toHaveBeenCalled();
  });

  it("submits the default structured recall query and renders projected cards", async () => {
    render(() => <RecallPage />);

    fireEvent.input(screen.getByLabelText("Scope"), { target: { value: "sba-agentic" } });
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));

    expect(getRecall).toHaveBeenCalledWith("sba-agentic", 168, ["decision", "handoff"]);
    expect(await screen.findByText("Use the Hybrid Storyline timeline")).toBeInTheDocument();
    expect(
      screen.getByText(
        "It keeps meaningful project blocks first while preserving raw trace archaeology.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Raw chronological feed")).toBeInTheDocument();
    expect(screen.getByText("82%")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Open Use the Hybrid Storyline timeline in Browse" }),
    ).toHaveAttribute("href", "/?view=browse&session=session-1&event=evt-1&project=");
    expect(screen.queryByText(/eventId/)).not.toBeInTheDocument();
    expect(setParams).toHaveBeenCalledWith({ scope: "sba-agentic" });
  });

  it("initializes scope from a project action URL", () => {
    updateParams({ scope: "/Users/nathan/Developer/proj/sba-agentic" });
    render(() => <RecallPage />);

    expect(screen.getByLabelText("Scope")).toHaveValue("/Users/nathan/Developer/proj/sba-agentic");
  });

  it("synchronizes query-only navigation and clears stale results", async () => {
    updateParams({ scope: "/tmp/first-project" });
    render(() => <RecallPage />);
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    expect(await screen.findByText("Use the Hybrid Storyline timeline")).toBeInTheDocument();

    updateParams({ scope: "/tmp/second-project" });

    await waitFor(() => expect(screen.getByLabelText("Scope")).toHaveValue("/tmp/second-project"));
    expect(screen.queryByText("Use the Hybrid Storyline timeline")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Run a recall query" })).toBeInTheDocument();
  });
});
