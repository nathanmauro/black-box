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
  it("retains separate Projection basis before a body that exhausts the copy budget", () => {
    const rationale = "Only consider a server after demonstrated demand.";
    const text = buildRecallBriefing(
      [
        {
          ...base,
          kind: "projection",
          body: "Possible path. ".repeat(1500) + "[truncated]",
          rationale,
        },
      ],
      options,
    );
    expect(text).toContain(`Recorded basis: ${rationale}`);
    expect(text).toContain("[Capture truncated; open the evidence link for full text.]");
    expect(text.length).toBeLessThanOrEqual(BRIEFING_MAX_CHARS);
  });

  it("copies projection alternatives as possibilities and discloses legacy missing bodies", () => {
    const body = "Local first (0.8)\nA shared server (0.2) only after verified demand.";
    const text = buildRecallBriefing(
      [{ ...base, kind: "projection", body, confidence: 0.8 }],
      options,
    );
    expect(text).toContain(body);
    expect(text).toContain("Recorded possibilities; no selected outcome is implied.");
    expect(text).not.toContain("Full path evidence is unavailable");
    expect(text).toContain("event=e1&project=");
    const legacy = buildRecallBriefing([{ ...base, kind: "projection", confidence: 0.8 }], options);
    expect(legacy).toContain("Full path evidence is unavailable in this response");
    expect(legacy).toContain(base.headline!);
  });

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
  it("copies complete observation bodies once and keeps legacy headline-only items usable", () => {
    const body = "Observation title\nVerification failed.\nKeep the old database.";
    const text = buildRecallBriefing(
      [{ ...base, kind: "observation", headline: "Observation title", body }],
      options,
    );
    expect(text).toContain(body);
    expect(text.split("Observation title")).toHaveLength(2);
    expect(text).toContain("Export limits: 0 captures truncated; 0 captures omitted.");
    expect(buildRecallBriefing([{ ...base, kind: "observation" }], options)).toContain(
      base.headline!,
    );
  });
  it("marks oversized observation bodies as truncated without dropping their provenance", () => {
    const text = buildRecallBriefing(
      [{ ...base, kind: "observation", body: "Observation\n" + "Evidence ".repeat(4000) }],
      options,
    );
    expect(text.length).toBeLessThanOrEqual(BRIEFING_MAX_CHARS);
    expect(text).toContain("[Capture truncated; open the evidence link for full text.]");
    expect(text).toContain("Export limits: 1 captures truncated; 0 captures omitted.");
    expect(text).toContain("2026-10-01T12:00:00Z");
    expect(text).toContain("https://blackbox.example/?view=browse&session=s1&event=e1&project=");
  });
  it("does not split an emoji at the observation copy budget boundary", () => {
    const suffix = "\n[Capture truncated; open the evidence link for full text.]";
    const prefix = "x".repeat(6000 - suffix.length - 1);
    const text = buildRecallBriefing(
      [{ ...base, kind: "observation", body: prefix + "🧪" + "y".repeat(8000) }],
      options,
    );
    expect(text).toContain(prefix + suffix);
    expect(text).not.toContain("\uD83E");
    expect(text.length).toBeLessThanOrEqual(BRIEFING_MAX_CHARS);
    expect(text).toContain("Export limits: 1 captures truncated; 0 captures omitted.");
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
