import { describe, expect, it } from "vitest";
import type { ProjectSummary } from "./api";
import { stableWindowOrder, windowSpanMs } from "./projectWindows";

const NOW = Date.parse("2026-10-23T12:00:00Z");
const HOUR = 60 * 60_000;

function project(projectKey: string, hoursAgo: number | null): ProjectSummary {
  return {
    projectKey,
    canonicalKey: `/work/${projectKey}`,
    label: projectKey,
    sessionCount: 1,
    eventCount: 1,
    savedMeldCount: 0,
    lastSeenAt: hoursAgo === null ? null : new Date(NOW - hoursAgo * HOUR).toISOString(),
  };
}

describe("stableWindowOrder", () => {
  it("places active projects most recent first and leaves quiet ones out", () => {
    const projects = [project("old", 72), project("mid", 5), project("hot", 1), project("x", null)];

    expect(stableWindowOrder([], projects, NOW, windowSpanMs("48h"))).toEqual(["hot", "mid"]);
  });

  it("keeps placed windows where they are and appends new arrivals", () => {
    const projects = [project("a", 3), project("b", 2), project("c", 0)];

    expect(stableWindowOrder(["a", "b"], projects, NOW, windowSpanMs("48h"))).toEqual([
      "a",
      "b",
      "c",
    ]);
  });

  it("returns the same array when nothing new arrived", () => {
    const placed = ["a"];

    expect(stableWindowOrder(placed, [project("a", 0)], NOW)).toBe(placed);
  });

  it("widens with the span", () => {
    const projects = [project("week", 100), project("day", 10)];

    expect(stableWindowOrder([], projects, NOW, windowSpanMs("24h"))).toEqual(["day"]);
    expect(stableWindowOrder([], projects, NOW, windowSpanMs("7d"))).toEqual(["day", "week"]);
  });
});
