import { describe, expect, it } from "vitest";
import type { EventFeedItem, ProjectSummary } from "../api";
import { deriveModel, headlineOf, pulseOf, toMeaningfulItem, UNASSIGNED_KEY } from "./model";
import { eventHref } from "./links";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const iso = (offsetMs: number) => new Date(NOW - offsetMs).toISOString();

function project(path: string, key = `key:${path}`): ProjectSummary {
  return {
    projectKey: key,
    canonicalKey: path,
    label: path,
    sessionCount: 1,
    eventCount: 1,
    savedMeldCount: 0,
    firstSeenAt: iso(86_400_000),
    lastSeenAt: iso(0),
    scopes: [{ projectKey: key, canonicalKey: path, label: path, primary: true }],
  };
}

function event(overrides: Partial<EventFeedItem> & { id: string }): EventFeedItem {
  return {
    sessionId: "s1",
    source: "claude",
    clientSessionId: "c1",
    eventType: "Decision",
    text: "Use X\n\nWhy: because",
    cwd: "/repo/a",
    observedAt: iso(60_000),
    ...overrides,
  };
}

const projects = [project("/repo/a"), project("/repo/b")];

describe("headlineOf", () => {
  it("prefers metadata.decision, then contextSummary, then the first text line", () => {
    expect(headlineOf(event({ id: "1", metadata: { decision: "Pick SQLite" } }))).toBe("Pick SQLite");
    expect(headlineOf(event({ id: "2", eventType: "Handoff", metadata: { contextSummary: "Done: tests\nNext: ship" } }))).toBe("Done: tests");
    expect(headlineOf(event({ id: "3", text: "\n  first line  \nsecond" }))).toBe("first line");
  });

  it("falls back to the first text line and trims to 120 chars", () => {
    const long = "x".repeat(200);
    expect(headlineOf(event({ id: "4", eventType: "Handoff", text: long }))).toHaveLength(120);
    expect(headlineOf(event({ id: "5", text: null }))).toBe("Decision");
  });
});

describe("toMeaningfulItem", () => {
  it("returns null for non-meaningful event types", () => {
    expect(toMeaningfulItem(event({ id: "1", eventType: "PostToolUse" }), projects, new Set())).toBeNull();
  });

  it("maps a handoff with next action and open loops", () => {
    const item = toMeaningfulItem(
      event({ id: "h1", eventType: "Handoff", metadata: { contextSummary: "Wired it", nextAction: "Run tests", openLoops: ["a", "b"] } }),
      projects,
      new Set(["h1"]),
    );
    expect(item).toMatchObject({ kind: "handoff", eventType: "Handoff", headline: "Wired it", nextAction: "Run tests", openLoops: ["a", "b"], seen: true, projectName: "a" });
    expect(item?.href).toBe(eventHref("s1", "h1", projects[0].projectKey));
  });

  it("groups unresolved cwd under Unassigned", () => {
    expect(toMeaningfulItem(event({ id: "u1", cwd: "/elsewhere" }), projects, new Set())).toMatchObject({ projectKey: UNASSIGNED_KEY, projectName: "Unassigned" });
    expect(toMeaningfulItem(event({ id: "u2", cwd: null }), projects, new Set())).toMatchObject({ projectKey: UNASSIGNED_KEY });
  });
});

describe("pulseOf", () => {
  it("maps connection and recency to a pulse state", () => {
    expect(pulseOf("down", iso(0), NOW)).toBe("disconnected");
    expect(pulseOf("connecting", null, NOW)).toBe("connecting");
    expect(pulseOf("live", iso(30_000), NOW)).toBe("live");
    expect(pulseOf("live", iso(300_000), NOW)).toBe("idle");
    expect(pulseOf("live", null, NOW)).toBe("idle");
  });
});

describe("deriveModel", () => {
  it("builds project cards from live sessions and items, sorted by unseen then activity", () => {
    const model = deriveModel({
      now: NOW,
      connection: "live",
      lastEventAt: iso(5_000),
      projects,
      sessions: [
        { id: "s1", cwd: "/repo/a", lastSeenAt: iso(30_000) },
        { id: "s2", cwd: "/repo/b", lastSeenAt: iso(3_600_000) },
      ],
      events: [
        event({ id: "a1", cwd: "/repo/a", observedAt: iso(120_000) }),
        event({ id: "b1", cwd: "/repo/b", observedAt: iso(60_000), eventType: "Observation" }),
        event({ id: "b2", cwd: "/repo/b", observedAt: iso(30_000), eventType: "Handoff" }),
        event({ id: "b2", cwd: "/repo/b", observedAt: iso(30_000), eventType: "Handoff" }),
      ],
      seen: new Set(["a1"]),
    });
    expect(model.pulse).toBe("live");
    expect(model.unseenTotal).toBe(2);
    expect(model.projects.map((card) => card.name)).toEqual(["b", "a"]);
    expect(model.projects[0]).toMatchObject({ liveSessions: 0, unseen: 2, latest: expect.objectContaining({ id: "b2" }) });
    expect(model.projects[1]).toMatchObject({ liveSessions: 1, unseen: 0, lastActivityAt: iso(30_000) });
    expect(model.river.map((item) => item.id)).toEqual(["b2", "b1", "a1"]);
  });

  it("returns no cards when nothing is live and nothing is meaningful", () => {
    const model = deriveModel({ now: NOW, connection: "live", lastEventAt: null, projects, sessions: [], events: [], seen: new Set() });
    expect(model.projects).toEqual([]);
    expect(model.pulse).toBe("idle");
  });
});
