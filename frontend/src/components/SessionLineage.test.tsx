import { fireEvent, render, screen } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DagResponse } from "../lib/api";

const navigateSpy = vi.fn();
vi.mock("@solidjs/router", () => ({
  useNavigate: () => navigateSpy,
}));

const {
  default: SessionLineage,
  buildLineageRail,
  visibleLineageRailItems,
} = await import("./SessionLineage");

const lineageFixture: DagResponse = {
  nodes: [
    { id: "session:parent", type: "session", label: "Coordinator", ref: "parent" },
    { id: "session:reviewer", type: "session", label: "Reviewer", ref: "reviewer" },
    { id: "session:tester", type: "session", label: "Tester", ref: "tester" },
    { id: "session:verifier", type: "session", label: "Verifier", ref: "verifier" },
  ],
  edges: [
    { from: "session:parent", to: "session:reviewer", type: "spawned" },
    { from: "session:parent", to: "session:tester", type: "spawned" },
    { from: "session:parent", to: "session:verifier", type: "continued" },
  ],
};

describe("lineage rail model", () => {
  it("orders parents before subagents and keeps the selected agent in a bounded rail", () => {
    const overflowDag: DagResponse = {
      nodes: [
        { id: "session:parent", type: "session", label: "Coordinator", ref: "parent" },
        ...Array.from({ length: 7 }, (_, index) => ({
          id: `session:child-${index + 1}`,
          type: "session" as const,
          label: `Worker ${index + 1}`,
          ref: `child-${index + 1}`,
        })),
      ],
      edges: Array.from({ length: 7 }, (_, index) => ({
        from: "session:parent",
        to: `session:child-${index + 1}`,
        type: "spawned" as const,
      })),
    };

    const items = buildLineageRail(overflowDag, "child-7");
    const visible = visibleLineageRailItems(items, 5);

    expect(items[0]).toMatchObject({ sessionId: "parent", root: true, depth: 0 });
    expect(items.slice(1).every((item) => !item.root && item.depth === 1)).toBe(true);
    expect(visible).toHaveLength(5);
    expect(visible.some((item) => item.sessionId === "child-7" && item.current)).toBe(true);
  });
});

describe("SessionLineage", () => {
  beforeEach(() => {
    navigateSpy.mockReset();
  });

  it("renders a compact navigation rail and opens the relationship map as an anchored lens", () => {
    const selectSession = vi.fn();
    const { container } = render(() => (
      <SessionLineage
        dag={lineageFixture}
        currentSessionId="parent"
        onSelectSession={selectSession}
      />
    ));

    expect(screen.getByRole("navigation", { name: "Agent lineage" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Current agent: Coordinator" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Subagent: Reviewer" })).toHaveClass("lineage-agent-chip--subagent");
    expect(screen.getByRole("button", { name: "Continued agent: Verifier" })).toHaveClass("lineage-agent-chip--continued");

    fireEvent.click(screen.getByRole("button", { name: "Subagent: Reviewer" }));
    expect(selectSession).toHaveBeenCalledWith("reviewer");

    const toggle = screen.getByRole("button", { name: "Expand lineage map" });
    const lens = container.querySelector(".lineage-lens") as HTMLElement;
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(lens).toHaveAttribute("aria-hidden", "true");
    expect(lens).toHaveAttribute("inert");

    fireEvent.click(toggle);
    expect(toggle).toHaveAccessibleName("Collapse lineage map");
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("region", { name: "Agent lineage map" })).toHaveClass("lineage-lens--open");
    expect(lens).not.toHaveAttribute("inert");
    expect(container.querySelector(".dag-stage--lineage")).toBeInTheDocument();
    expect(container.querySelectorAll(".dag-edges path")).toHaveLength(3);

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.getByRole("button", { name: "Expand lineage map" })).toHaveAttribute("aria-expanded", "false");
    expect(lens).toHaveAttribute("aria-hidden", "true");
  });

  it("closes the lens when the user clicks outside it", () => {
    render(() => (
      <SessionLineage
        dag={lineageFixture}
        currentSessionId="parent"
        onSelectSession={vi.fn()}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Expand lineage map" }));
    expect(screen.getByRole("region", { name: "Agent lineage map" })).toBeInTheDocument();
    fireEvent.pointerDown(document.body);
    expect(screen.getByRole("button", { name: "Expand lineage map" })).toHaveAttribute("aria-expanded", "false");
  });
});
