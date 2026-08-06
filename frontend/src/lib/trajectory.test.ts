import { describe, expect, it } from "vitest";
import type {
  ProjectTrajectoryResponse,
  TrajectoryCapture,
  TrajectoryTask,
} from "./api";
import {
  FRONTIER_DAYS,
  FUTURE_MAX,
  GHOST_MAX,
  GAP_HOURS,
  PROJECTION_TTL,
  SPINE_MAX,
  STALE_DAYS,
  buildTrajectory,
  layoutTrajectory,
} from "./trajectory";

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

describe("buildTrajectory", () => {
  it("clusters bursts at the 48h boundary and collapses older spine into deep past", () => {
    const startMs = Date.parse("2026-01-01T00:00:00Z");
    const captures = [
      capture("b0-a", "observation", iso(startMs), { headline: "First capture" }),
      capture("b0-b", "decision", iso(startMs + GAP_HOURS * HOUR_MS), { headline: "Boundary decision" }),
      ...Array.from({ length: SPINE_MAX + 1 }, (_, index) =>
        capture(
          `b${index + 1}`,
          index === SPINE_MAX ? "handoff" : "observation",
          iso(startMs + (GAP_HOURS * 2 + 1 + index * (GAP_HOURS + 1)) * HOUR_MS),
          { headline: `Burst ${index + 1}` },
        ),
      ),
    ];

    const graph = buildTrajectory(response(captures), Date.parse("2026-02-01T00:00:00Z"));
    const deepPast = graph.nodes.find((node) => node.kind === "deep-past");
    const visibleBursts = graph.nodes.filter((node) => node.kind === "burst");

    expect(deepPast).toMatchObject({
      id: "deep-past",
      memberCount: 2,
      hasDecision: true,
    });
    expect(deepPast?.members?.map((member) => member.id)).toEqual(["b0-a", "b0-b"]);
    expect(visibleBursts).toHaveLength(SPINE_MAX);
    expect(graph.headId).toBe("head:b6");
  });

  it("selects the latest handoff in the newest burst, falls back to latest capture, and marks stale after 14 days", () => {
    const headMs = Date.parse("2026-03-10T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("obs-before", "observation", iso(headMs - HOUR_MS), { headline: "Earlier observation" }),
        capture("handoff", "handoff", iso(headMs), { headline: "Latest handoff" }),
        capture("obs-after", "observation", iso(headMs + HOUR_MS), { headline: "Later observation" }),
      ]),
      headMs + DAY_MS,
    );

    const head = graph.nodes.find((node) => node.kind === "head");
    expect(graph.headId).toBe("head:handoff");
    expect(head?.sourceCapture?.id).toBe("handoff");
    expect(head?.label).toBe("Latest handoff");

    const fallback = buildTrajectory(
      response([
        capture("old-observation", "observation", iso(headMs), { headline: "Old observation" }),
        capture("latest-decision", "decision", iso(headMs + HOUR_MS), { headline: "Latest decision" }),
      ]),
      headMs + DAY_MS,
    );
    expect(fallback.headId).toBe("head:latest-decision");

    const fresh = buildTrajectory(response([capture("fresh", "handoff", iso(headMs))]), headMs + STALE_DAYS * DAY_MS);
    const stale = buildTrajectory(
      response([capture("stale", "handoff", iso(headMs))]),
      headMs + STALE_DAYS * DAY_MS + 1,
    );
    expect(fresh.stale).toBe(false);
    expect(stale.stale).toBe(true);
    expect(stale.nodes.find((node) => node.kind === "head")).toMatchObject({
      stale: true,
      eyebrow: expect.stringMatching(/^IDLE /),
    });
  });

  it("uses only the latest handoff per frontier session inside the seven-day window", () => {
    const headMs = Date.parse("2026-04-10T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("too-far", "handoff", iso(headMs - FRONTIER_DAYS * DAY_MS - HOUR_MS), {
          sessionId: "far",
          nextAction: "Far outside action",
        }),
        capture("boundary", "handoff", iso(headMs - FRONTIER_DAYS * DAY_MS), {
          sessionId: "boundary",
          nextAction: "Boundary action",
        }),
        capture("old-a", "handoff", iso(headMs - 2 * DAY_MS), {
          sessionId: "session-a",
          nextAction: "Superseded action",
        }),
        capture("new-a", "handoff", iso(headMs - DAY_MS), {
          sessionId: "session-a",
          nextAction: "New session action",
        }),
        capture("head", "handoff", iso(headMs), { sessionId: "head-session" }),
      ]),
      headMs,
    );

    expect(futureLabels(graph, "future-next")).toEqual(["New session action", "Boundary action"]);
    expect(allFutureLabels(graph)).not.toContain("Superseded action");
    expect(allFutureLabels(graph)).not.toContain("Far outside action");
  });

  it("dedupes future text at Jaccard 0.5 and keeps the newer capture", () => {
    const headMs = Date.parse("2026-05-01T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("older", "handoff", iso(headMs - 2 * DAY_MS), {
          sessionId: "older",
          nextAction: "alpha beta gamma",
        }),
        capture("newer", "handoff", iso(headMs - DAY_MS), {
          sessionId: "newer",
          nextAction: "alpha beta delta",
        }),
        capture("head", "handoff", iso(headMs), { sessionId: "head" }),
      ]),
      headMs,
    );

    expect(futureLabels(graph, "future-next")).toEqual(["alpha beta delta"]);
    expect(allFutureLabels(graph)).not.toContain("alpha beta gamma");
  });

  it("dedupes solid futures across categories while keeping the earlier tier", () => {
    const headMs = Date.parse("2026-05-02T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("next", "handoff", iso(headMs - 2 * HOUR_MS), {
          sessionId: "next",
          nextAction: "Audit trajectory detail flow",
        }),
        capture("loop", "handoff", iso(headMs - HOUR_MS), {
          sessionId: "loop",
          openLoops: ["Audit trajectory detail flow"],
        }),
        capture("head", "handoff", iso(headMs), { sessionId: "head" }),
      ]),
      headMs,
    );

    expect(futureLabels(graph, "future-next")).toEqual(["Audit trajectory detail flow"]);
    expect(futureLabels(graph, "future-loop")).toEqual([]);
    expect(allFutureLabels(graph).filter((label) => label === "Audit trajectory detail flow")).toHaveLength(1);
  });

  it("uses the latest non-expired projection as the full ghost set even when it is empty", () => {
    const nowMs = Date.parse("2026-05-03T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("old-projection", "projection", iso(nowMs - 2 * HOUR_MS), {
          paths: [{ title: "Old ghost path", description: "Older projection", confidence: 0.9 }],
        }),
        capture("empty-projection", "projection", iso(nowMs - HOUR_MS), {
          paths: [],
        }),
        capture("head", "handoff", iso(nowMs), { sessionId: "head" }),
      ]),
      nowMs,
    );

    expect(graph.nodes.some((node) => node.kind === "future-ghost")).toBe(false);
    expect(allFutureLabels(graph)).not.toContain("Old ghost path");
  });

  it("ranks and caps futures with ghost reservation, projection TTL, and overflow", () => {
    const nowMs = Date.parse("2026-08-01T12:00:00Z");
    const graph = buildTrajectory(rankingResponse(nowMs), nowMs);
    const visibleFutures = graph.nodes.filter((node) => node.kind.startsWith("future-"));
    const rankedVisible = visibleFutures.filter((node) => node.kind !== "future-more");
    const more = graph.nodes.find((node) => node.kind === "future-more");

    expect(visibleFutures).toHaveLength(FUTURE_MAX);
    expect(rankedVisible.map((node) => node.label)).toEqual([
      "Draft tests",
      "Review graph surface",
      "Blocked high",
      "Blocked mid",
      "In-progress task",
      "Ghost path one",
    ]);
    expect(futureLabels(graph, "future-next")).toHaveLength(2);
    expect(allFutureLabels(graph)).not.toContain("Audit docs");
    expect(allFutureLabels(graph)).toEqual(expect.arrayContaining(["Blocked high", "Blocked mid"]));
    expect(allFutureLabels(graph)).not.toContain("Blocked low");
    expect(allFutureLabels(graph)).toEqual(expect.arrayContaining(["In-progress task", "Claimed task", "Open task"]));
    expect(more).toMatchObject({ label: "+6 more" });
    expect(more?.items?.map((item) => item.label)).toEqual([
      "Claimed task",
      "Open task",
      "Loop one",
      "Loop two",
      "Ghost path two",
      "Ghost path three",
    ]);
    expect(visibleFutures.filter((node) => node.kind === "future-ghost")).toHaveLength(1);
    expect(allFutureLabels(graph).filter((label) => label.startsWith("Ghost path"))).toHaveLength(GHOST_MAX);
    expect(allFutureLabels(graph)).not.toContain("Ghost path four");

    const expiredProjection = buildTrajectory(
      rankingResponse(nowMs, iso(nowMs - (PROJECTION_TTL + 1) * DAY_MS)),
      nowMs,
    );
    expect(expiredProjection.nodes.some((node) => node.kind === "future-ghost")).toBe(false);
    expect(allFutureLabels(expiredProjection).some((label) => label.startsWith("Ghost path"))).toBe(false);
  });

  it("aggregates at most one rejected stub per burst and never attaches stubs to futures", () => {
    const startMs = Date.parse("2026-06-01T12:00:00Z");
    const graph = buildTrajectory(
      response([
        capture("old-decision-a", "decision", iso(startMs), {
          alternatives: ["Keep the old parser"],
        }),
        capture("old-decision-b", "decision", iso(startMs + HOUR_MS), {
          alternatives: ["Keep the old renderer"],
        }),
        capture("head-decision", "decision", iso(startMs + 4 * DAY_MS), {
          alternatives: ["Defer the graph"],
        }),
        capture("head", "handoff", iso(startMs + 4 * DAY_MS + HOUR_MS), {
          sessionId: "head",
          nextAction: "Continue the graph",
        }),
      ]),
      startMs + 5 * DAY_MS,
    );

    const stubs = graph.nodes.filter((node) => node.kind === "stub");
    const rejectedEdges = graph.edges.filter((edge) => edge.type === "rejected");

    expect(stubs).toHaveLength(2);
    expect(stubs.find((node) => node.sourceBurstId?.startsWith("burst:"))?.alternatives).toEqual([
      "Keep the old parser",
      "Keep the old renderer",
    ]);
    expect(stubs.find((node) => node.sourceBurstId === graph.headId)?.alternatives).toEqual(["Defer the graph"]);
    expect(stubs.every((node) => !node.sourceBurstId?.startsWith("future:"))).toBe(true);
    expect(rejectedEdges.every((edge) => !edge.from.startsWith("future:"))).toBe(true);
  });
});

describe("layoutTrajectory", () => {
  it("keeps a left-to-right spine, non-overlapping future rows, and bounds around every node", () => {
    const nowMs = Date.parse("2026-08-01T12:00:00Z");
    const graph = buildTrajectory(
      rankingResponse(nowMs, undefined, [
        ...Array.from({ length: SPINE_MAX + 4 }, (_, index) =>
          capture(`old-${index}`, index % 2 === 0 ? "observation" : "decision", iso(nowMs - (42 - index * 3) * DAY_MS), {
            headline: `Old burst ${index}`,
          }),
        ),
      ]),
      nowMs,
    );
    const layout = layoutTrajectory(graph);
    const spine = layout.nodes
      .filter((node) => node.spineIndex !== undefined)
      .sort((left, right) => (left.spineIndex ?? 0) - (right.spineIndex ?? 0));
    const head = layout.nodes.find((node) => node.id === graph.headId);
    const futures = layout.nodes.filter((node) => node.kind.startsWith("future-"));

    for (let index = 1; index < spine.length; index += 1) {
      expect(spine[index].x).toBeGreaterThan(spine[index - 1].x);
    }
    expect(head?.x).toBeGreaterThan(spine[spine.length - 2].x);
    expect(futures).toHaveLength(FUTURE_MAX);
    expect(new Set(futures.map((node) => node.y)).size).toBe(FUTURE_MAX);
    for (const node of layout.nodes) {
      expect(node.x).toBeGreaterThanOrEqual(0);
      expect(node.y).toBeGreaterThanOrEqual(0);
      expect(node.x).toBeLessThan(layout.width);
      expect(node.y).toBeLessThan(layout.height);
      expect(node.labelLeft).toBeGreaterThanOrEqual(0);
      expect(node.labelRight).toBeLessThanOrEqual(layout.width);
    }
  });
});

function rankingResponse(
  nowMs: number,
  projectionObservedAt = iso(nowMs - 2 * HOUR_MS),
  extraCaptures: TrajectoryCapture[] = [],
): ProjectTrajectoryResponse {
  return response([
    ...extraCaptures,
    capture("next-3", "handoff", iso(nowMs - 6 * HOUR_MS), {
      sessionId: "next-3",
      nextAction: "Audit docs",
    }),
    capture("loops", "handoff", iso(nowMs - 5 * HOUR_MS), {
      sessionId: "loops",
      openLoops: ["Loop one", "Loop two"],
    }),
    capture("next-2", "handoff", iso(nowMs - 4 * HOUR_MS), {
      sessionId: "next-2",
      nextAction: "Review graph surface",
    }),
    capture("next-1", "handoff", iso(nowMs - 3 * HOUR_MS), {
      sessionId: "next-1",
      nextAction: "Draft tests",
    }),
    capture("projection", "projection", projectionObservedAt, {
      paths: [
        { title: "Ghost path one", description: "Speculative route one", confidence: 0.8 },
        { title: "Ghost path two", description: "Speculative route two", confidence: 0.7 },
        { title: "Ghost path three", description: "Speculative route three", confidence: 0.6 },
        { title: "Ghost path four", description: "Speculative route four", confidence: 0.5 },
      ],
    }),
    capture("head", "handoff", iso(nowMs), { sessionId: "head" }),
  ], [
    task("blocked-high", "Blocked high", "blocked", 90, nowMs - HOUR_MS),
    task("blocked-mid", "Blocked mid", "blocked", 80, nowMs - HOUR_MS),
    task("blocked-low", "Blocked low", "blocked", 70, nowMs - HOUR_MS),
    task("claimed", "Claimed task", "claimed", 10, nowMs - HOUR_MS),
    task("in-progress", "In-progress task", "in_progress", 100, nowMs - HOUR_MS),
    task("open", "Open task", "open", 100, nowMs - HOUR_MS),
  ]);
}

function futureLabels(graph: ReturnType<typeof buildTrajectory>, kind: string): string[] {
  return graph.nodes.filter((node) => node.kind === kind).map((node) => node.label);
}

function allFutureLabels(graph: ReturnType<typeof buildTrajectory>): string[] {
  return graph.nodes.flatMap((node) => {
    if (node.kind === "future-more") return (node.items ?? []).map((item) => item.label);
    if (node.kind.startsWith("future-")) return [node.label];
    return [];
  });
}

function response(captures: TrajectoryCapture[], tasks: TrajectoryTask[] = []): ProjectTrajectoryResponse {
  return {
    projectKey: "project-key",
    canonicalKey: "/repo/project",
    label: "Project",
    generatedAt: "2026-08-01T12:00:00Z",
    totalCaptures: captures.length,
    captures,
    tasks,
  };
}

function capture(
  id: string,
  kind: TrajectoryCapture["kind"],
  observedAt: string,
  overrides: Partial<Omit<TrajectoryCapture, "id" | "kind" | "observedAt">> = {},
): TrajectoryCapture {
  return {
    id,
    kind,
    observedAt,
    headline: id,
    text: id,
    ...overrides,
  };
}

function task(id: string, title: string, status: string, priority: number, updatedAtMs: number): TrajectoryTask {
  return { id, title, status, priority, updatedAt: iso(updatedAtMs) };
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}
