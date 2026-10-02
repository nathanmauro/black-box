import { fireEvent, render, screen } from "@solidjs/testing-library";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DagResponse } from "../lib/api";

const navigateSpy = vi.fn();
vi.mock("@solidjs/router", () => ({
  useNavigate: () => navigateSpy,
}));

const { default: DagView, layoutLineageDag } = await import("./DagView");

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

describe("layoutLineageDag", () => {
  it("lays out session relationships from left to right by relationship depth", () => {
    const layout = layoutLineageDag(lineageFixture);
    const parent = layout.nodes.find((node) => node.id === "session:parent");
    const children = layout.nodes.filter((node) => node.id !== "session:parent");

    expect(layout.nodes).toHaveLength(4);
    expect(parent?.column).toBe(0);
    expect(children.every((node) => node.column === 1)).toBe(true);
    expect(children.every((node) => node.x > (parent?.x ?? 0))).toBe(true);
    expect(new Set(children.map((node) => node.y)).size).toBe(children.length);
    expect(layout.edges).toHaveLength(3);
    expect(layout.width).toBeGreaterThan(layout.height);
  });
});

describe("DagView", () => {
  beforeEach(() => {
    navigateSpy.mockClear();
  });

  it("keeps raw session IDs in links and supports keyboard navigation", () => {
    const { container } = render(() => <DagView dag={lineageFixture} currentSessionId="parent" />);
    const parent = screen.getByRole("link", { name: "Open session: Coordinator" });
    expect(parent).toHaveAttribute("data-node-href", "/sessions/parent");
    expect(parent).toHaveClass("dag-node--current");
    expect(parent.namespaceURI).toBe("http://www.w3.org/2000/svg");
    expect(container.querySelector("svg a")).not.toBeInTheDocument();
    fireEvent.keyDown(parent, { key: "Enter" });
    expect(navigateSpy).toHaveBeenCalledWith("/sessions/parent");
  });

  it("renders an empty state instead of an SVG", () => {
    const { container } = render(() => <DagView dag={{ nodes: [], edges: [] }} />);

    expect(screen.getByText("No DAG data yet.")).toBeInTheDocument();
    expect(container.querySelector("svg")).not.toBeInTheDocument();
  });

  it("renders the lineage layout with curved edges and delegates session navigation", () => {
    const selectSession = vi.fn();
    const { container } = render(() => (
      <DagView dag={lineageFixture} currentSessionId="parent" onSelectSession={selectSession} />
    ));

    expect(screen.getByRole("region", { name: "Agent lineage DAG" })).toHaveClass(
      "dag-stage--lineage",
    );
    expect(container.querySelectorAll(".dag-edges path")).toHaveLength(3);
    fireEvent.click(screen.getByRole("link", { name: "Open session: Reviewer" }));
    expect(selectSession).toHaveBeenCalledWith("reviewer");
    expect(navigateSpy).not.toHaveBeenCalled();
  });
});
