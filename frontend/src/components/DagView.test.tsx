import { fireEvent, render, screen } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DagResponse } from "../lib/api";

const navigateSpy = vi.fn();
vi.mock("@solidjs/router", () => ({
  useNavigate: () => navigateSpy,
}));

const { default: DagView, layoutDag } = await import("./DagView");

const fixture: DagResponse = {
  nodes: [
    { id: "spec-1", type: "spec", label: "Ship the full-auto runner", status: "active", ref: "spec-ref-1" },
    { id: "task-1", type: "task", label: "Validate the story", status: "in_progress", ref: "task-ref-1" },
    { id: "task-2", type: "task", label: "Run the implementation", status: "open", ref: "task-ref-2" },
    { id: "session-1", type: "session", label: "Gate worker", ref: "session-ref-1" },
    { id: "session-2", type: "session", label: "Implementation worker", ref: "session-ref-2" },
    { id: "session-3", type: "session", label: "Continued verification", ref: "session-ref-3" },
  ],
  edges: [
    { from: "spec-1", to: "task-1", type: "has_task" },
    { from: "spec-1", to: "task-2", type: "has_task" },
    { from: "task-1", to: "session-1", type: "worker_session" },
    { from: "task-2", to: "session-2", type: "worker_session" },
    { from: "session-2", to: "session-3", type: "continued" },
    { from: "missing-task", to: "session-3", type: "worker_session" },
  ],
};

describe("layoutDag", () => {
  it("lays out stable type columns and resolves valid edges", () => {
    const layout = layoutDag(fixture);

    expect(layout.nodes).toHaveLength(6);
    expect(layout.edges).toHaveLength(5);
    expect(layout.nodes.filter((node) => node.type === "spec").every((node) => node.column === 0)).toBe(true);
    expect(layout.nodes.filter((node) => node.type === "task").every((node) => node.column === 1)).toBe(true);
    expect(layout.nodes.filter((node) => node.type === "session").every((node) => node.column === 2)).toBe(true);

    for (const type of ["task", "session"] as const) {
      const yPositions = layout.nodes.filter((node) => node.type === type).map((node) => node.y);
      expect(new Set(yPositions).size).toBe(yPositions.length);
      expect(yPositions).toEqual([...yPositions].sort((a, b) => a - b));
    }

    const continued = layout.edges.find((edge) => edge.type === "continued");
    expect(continued?.from).toBe(layout.nodes.find((node) => node.id === "session-2"));
    expect(continued?.to).toBe(layout.nodes.find((node) => node.id === "session-3"));

    const worker = layout.edges.find((edge) => edge.type === "worker_session" && edge.from.id === "task-1");
    expect(worker?.from.id).toBe("task-1");
    expect(worker?.to.id).toBe("session-1");
    expect(layout.edges.some((edge) => edge.from.id === "missing-task")).toBe(false);
  });

  it("returns non-zero fallback bounds for an empty DAG", () => {
    const layout = layoutDag({ nodes: [], edges: [] });

    expect(layout.nodes).toEqual([]);
    expect(layout.edges).toEqual([]);
    expect(layout.width).toBeGreaterThan(0);
    expect(layout.height).toBeGreaterThan(0);
  });
});

describe("DagView", () => {
  beforeEach(() => {
    navigateSpy.mockClear();
  });

  it("renders linked task and session nodes with a current-node marker", () => {
    const { container } = render(() => <DagView dag={fixture} currentTaskId="task-1" />);

    // Nodes navigate from the <g> itself: an <a> would compile to an HTML-namespace
    // anchor inside the SVG and collapse its children to zero size in real browsers.
    const taskNode = screen.getByRole("link", { name: "Open task: Validate the story" });
    expect(taskNode).toHaveAttribute("data-node-href", "/board?task=task-1");
    expect(taskNode.namespaceURI).toBe("http://www.w3.org/2000/svg");
    expect(container.querySelector("svg a")).not.toBeInTheDocument();
    const sessionNode = screen.getByRole("link", { name: "Open session: Gate worker" });
    expect(sessionNode).toHaveAttribute("data-node-href", "/sessions/session-1");
    expect(screen.getByText("Ship the full-auto runner")).toBeInTheDocument();
    expect(container.querySelector('[data-node-id="task-1"]')).toHaveClass("dag-node--current");

    fireEvent.click(taskNode);
    expect(navigateSpy).toHaveBeenCalledWith("/board?task=task-1");
    fireEvent.keyDown(sessionNode, { key: "Enter" });
    expect(navigateSpy).toHaveBeenCalledWith("/sessions/session-1");
  });

  it("renders an empty state instead of an SVG", () => {
    const { container } = render(() => <DagView dag={{ nodes: [], edges: [] }} />);

    expect(screen.getByText("No DAG data yet.")).toBeInTheDocument();
    expect(container.querySelector("svg")).not.toBeInTheDocument();
  });
});
