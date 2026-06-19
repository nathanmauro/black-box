import { describe, expect, it } from "vitest";
import type { RecalledItem } from "../../lib/api";
import { buildConstellation } from "../GraphPage";

const items: RecalledItem[] = [
  {
    eventId: "decision-1",
    kind: "decision",
    source: "codex",
    clientSessionId: "session-a",
    repo: "/Users/nathan/Developer/proj/sba-agentic",
    observedAt: "2026-06-17T12:00:00.000Z",
    headline: "Use SolidJS + Vite for the UI rewrite",
    rationale: "Matches agent-observatory while keeping the jar self-contained.",
    confidence: 0.9,
  },
  {
    eventId: "handoff-1",
    kind: "handoff",
    source: "claude",
    clientSessionId: "session-b",
    repo: "/Users/nathan/Developer/proj/sba-agentic",
    observedAt: "2026-06-17T13:00:00.000Z",
    headline: "Continue the read-only UI pass",
    nextAction: "Add the final graph surface.",
  },
  {
    eventId: "decision-2",
    kind: "decision",
    source: "codex",
    clientSessionId: "session-c",
    repo: "/Users/nathan/Developer/proj/ask-my-history",
    observedAt: "2026-06-17T14:00:00.000Z",
    headline: "{\"decision\":\"Never show this raw JSON\"}",
    rationale: "Readable fallback should come from kind and source when a headline is JSON.",
  },
];

describe("buildConstellation", () => {
  it("builds origin to project clusters to recalled leaves", () => {
    const graph = buildConstellation(items);

    expect(graph.nodes).toHaveLength(6);
    expect(graph.edges).toHaveLength(5);
    expect(graph.nodes.filter((node) => node.type === "origin")).toHaveLength(1);
    expect(graph.nodes.filter((node) => node.type === "cluster")).toHaveLength(2);
    expect(graph.nodes.filter((node) => node.type === "leaf")).toHaveLength(3);

    const sbaCluster = graph.nodes.find((node) => node.type === "cluster" && node.label === "sba-agentic");
    expect(sbaCluster).toBeDefined();
    expect(graph.edges).toContainEqual({ id: `edge:origin:${sbaCluster!.id}`, from: "origin", to: sbaCluster!.id });

    const decision = graph.nodes.find((node) => node.type === "leaf" && node.label === "Use SolidJS + Vite for the UI rewrite");
    expect(decision).toBeDefined();
    expect(graph.edges).toContainEqual({ id: `edge:${sbaCluster!.id}:${decision!.id}`, from: sbaCluster!.id, to: decision!.id });
  });

  it("uses human labels instead of raw JSON labels", () => {
    const graph = buildConstellation(items);
    const jsonLeaf = graph.nodes.find((node) => node.id === "leaf:decision-2");

    expect(jsonLeaf).toBeDefined();
    expect(jsonLeaf!.label).toBe("Decision from codex");
    expect(jsonLeaf!.label).not.toContain("{");
    expect(jsonLeaf!.label).not.toContain("}");
  });
});
