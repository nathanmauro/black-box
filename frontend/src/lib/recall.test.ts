import { describe, expect, it } from "vitest";
import type { RecalledItem } from "./api";
import { BRIEFING_MAX_CHARS, buildRecallBriefing, newestRecorded } from "./recall";
const base: RecalledItem = {
  eventId: "e1",
  sessionId: "s1",
  kind: "decision",
  source: "codex",
  observedAt: "2026-10-01T12:00:00Z",
  repo: "/repos/alpha",
  headline: "Choose A",
};
const options = { project: "/repos/alpha", withinHours: 8760, origin: "https://blackbox.example" };
describe("continuity evidence export", () => {
  it("preserves timestamps, source links and explicit replacement relations", () => {
    const text = buildRecallBriefing(
      [
        { ...base, supersededByEventId: "e2" },
        { ...base, eventId: "e2", supersedesEventId: "e1", headline: "Choose B" },
      ],
      options,
    );
    expect(text).toContain("2026-10-01T12:00:00Z");
    expect(text).toContain("https://blackbox.example/?view=browse&session=s1&event=e1&project=");
    expect(text).toContain("Explicitly replaced by: e2");
    expect(text).toContain("Explicitly replaces: e1");
    expect(text).toContain("not a complete history");
  });
  it("enforces the hard cap with honest truncation and complete source links", () => {
    const items = Array.from({ length: 50 }, (_, n) => ({
      ...base,
      eventId: `e${n}`,
      headline: "x".repeat(40000),
      rationale: "y".repeat(40000),
    }));
    const text = buildRecallBriefing(items, options);
    expect(text.length).toBeLessThanOrEqual(BRIEFING_MAX_CHARS);
    expect(text).toContain("[Capture truncated; open the evidence link for full text.]");
    expect(text).toMatch(/Export limits: [1-9]\d* captures truncated; [1-9]\d* captures omitted\./);
    expect(text).toContain("event=e0&project=");
  });
  it("finds latest retrieved handoff without treating replaced evidence as current", () => {
    expect(
      newestRecorded(
        [
          { ...base, kind: "handoff" },
          { ...base, kind: "handoff", eventId: "e-new", observedAt: "2026-10-02T12:00:00Z" },
        ],
        "handoff",
      )?.eventId,
    ).toBe("e-new");
    expect(newestRecorded([{ ...base, supersededByEventId: "e2" }], "decision")).toBeUndefined();
  });
});
