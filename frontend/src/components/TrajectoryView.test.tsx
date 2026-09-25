import { fireEvent, render } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { TrajectoryGraph } from "../lib/trajectory";
import TrajectoryView from "./TrajectoryView";

describe("TrajectoryView", () => {
  it("renders selectable SVG nodes with tooltips and no SVG anchors", () => {
    const onSelect = vi.fn();
    const { container } = render(() => (
      <TrajectoryView
        graph={graph}
        selectedNodeId="future:ghost:projection:0"
        onSelect={onSelect}
      />
    ));

    const head = container.querySelector('[data-node-id="head:handoff"]') as SVGGElement;
    const ghost = container.querySelector(
      '[data-node-id="future:ghost:projection:0"]',
    ) as SVGGElement;

    expect(head).toHaveAttribute("data-node-kind", "head");
    expect(ghost).toHaveAttribute("data-node-kind", "future-ghost");
    expect(ghost).toHaveClass("traj-node--future-ghost");
    expect(ghost).toHaveClass("traj-node--selected");

    fireEvent.click(head);
    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({ id: "head:handoff", kind: "head" }),
    );
    fireEvent.keyDown(ghost, { key: "Enter" });
    fireEvent.keyDown(ghost, { key: " " });
    expect(onSelect).toHaveBeenCalledTimes(3);
    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({
        id: "future:ghost:projection:0",
        kind: "future-ghost",
      }),
    );

    expect(
      Array.from(container.querySelectorAll("title")).map((title) => title.textContent),
    ).toEqual(
      expect.arrayContaining([
        "Current state - Head - NOW",
        "Speculative branch - Ghost - projection",
      ]),
    );
    expect(container.querySelector("svg a")).not.toBeInTheDocument();
  });
});

const graph: TrajectoryGraph = {
  stale: false,
  headId: "head:handoff",
  nodes: [
    {
      id: "head:handoff",
      kind: "head",
      label: "Current state",
      eyebrow: "NOW",
      fullText: "The current handoff",
      spineIndex: 0,
      sourceCapture: {
        id: "handoff",
        kind: "handoff",
        observedAt: "2026-08-01T12:00:00Z",
        headline: "Current state",
      },
    },
    {
      id: "future:ghost:projection:0",
      kind: "future-ghost",
      label: "Speculative branch",
      eyebrow: "projection",
      fullText: "A projected branch",
      rank: 0,
      confidence: 0.75,
      path: {
        title: "Speculative branch",
        description: "A projected branch",
        confidence: 0.75,
      },
    },
  ],
  edges: [
    {
      id: "projected:head:handoff:future:ghost:projection:0",
      from: "head:handoff",
      to: "future:ghost:projection:0",
      type: "projected",
    },
  ],
};
