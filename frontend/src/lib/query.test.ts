import { describe, expect, it } from "vitest";
import { describeTimeSpec, parseQuery, removeFacetValue, resolvesToPastInstant, serializeQuery, setFacet } from "./query";

describe("parseQuery", () => {
  it("parses canonical facets and free text", () => {
    const parsed = parseQuery("source:codex kind:Decision rebase");
    expect(parsed.facets).toEqual({ source: ["codex"], kind: ["Decision"] });
    expect(parsed.excludeFacets).toEqual({});
    expect(parsed.freeTerms).toEqual(["rebase"]);
  });

  it("maps backend aliases and unions repeated tokens", () => {
    const parsed = parseQuery("event_type:Handoff kind:Decision tool_name:Edit tool:Read cwd:/tmp project:~/x");
    expect(parsed.facets).toEqual({
      kind: ["Handoff", "Decision"],
      tool: ["Edit", "Read"],
      project: ["/tmp", "~/x"],
    });
  });

  it("splits unquoted comma values and keeps quoted ones whole", () => {
    expect(parseQuery("source:codex,claude").facets.source).toEqual(["codex", "claude"]);
    expect(parseQuery('source:"a,b"').facets.source).toEqual(["a,b"]);
  });

  it("keeps quoted text and quoted facet values together", () => {
    const parsed = parseQuery('tool:Edit "ask history" project:"/Users/nathan/Developer/proj/sba agentic"');
    expect(parsed.facets).toEqual({ tool: ["Edit"], project: ["/Users/nathan/Developer/proj/sba agentic"] });
    expect(parsed.freeTerms).toEqual(["ask history"]);
  });

  it("parses readable and terse negative facets", () => {
    const parsed = parseQuery("source:codex NOT kind:PostToolUse -tool:Read recall");
    expect(parsed.facets).toEqual({ source: ["codex"] });
    expect(parsed.excludeFacets).toEqual({ kind: ["PostToolUse"], tool: ["Read"] });
    expect(parsed.freeTerms).toEqual(["recall"]);
  });

  it("preserves dangling NOT before text", () => {
    expect(parseQuery("NOT recall bug").freeTerms).toEqual(["NOT", "recall", "bug"]);
  });

  it("parses operator tokens into top-level state", () => {
    const parsed = parseQuery("session:abc since:yesterday until:2026-08-18 is:all");
    expect(parsed.session).toBe("abc");
    expect(parsed.since).toEqual({ kind: "keyword", value: "yesterday" });
    expect(parsed.until).toEqual({ kind: "absolute", value: "2026-08-18" });
    expect(parsed.isAll).toBe(true);
  });

  it("treats last: as sugar for a since duration", () => {
    expect(parseQuery("last:2h").since).toEqual({ kind: "duration", value: "2h" });
  });

  it("keeps unparseable and negated operator tokens as free text", () => {
    expect(parseQuery("since:banana").freeTerms).toEqual(["since:banana"]);
    expect(parseQuery("-since:today").freeTerms).toEqual(["-since:today"]);
    expect(parseQuery("last:today").freeTerms).toEqual(["last:today"]);
  });
});

describe("serializeQuery", () => {
  it("round-trips to a normalized query", () => {
    const parsed = parseQuery('source:codex kind:Decision "rebase main"');
    expect(serializeQuery(parsed)).toBe('source:codex kind:Decision "rebase main"');
  });

  it("serializes multi-value facets as comma lists", () => {
    expect(serializeQuery(parseQuery("source:codex source:claude"))).toBe("source:codex,claude");
  });

  it("serializes negative facets with readable NOT syntax", () => {
    expect(serializeQuery(parseQuery("recall NOT kind:PostToolUse source:codex"))).toBe(
      "source:codex NOT kind:PostToolUse recall",
    );
  });

  it("serializes session, time, and is:all tokens", () => {
    expect(serializeQuery(parseQuery("is:all session:abc last:7d until:today kind:Decision"))).toBe(
      "kind:Decision session:abc since:7d until:today is:all",
    );
  });

  it("re-quotes values containing whitespace or commas", () => {
    const q = 'source:"a,b" project:"two words"';
    expect(serializeQuery(parseQuery(q))).toBe(q);
  });

  it("re-quotes free terms that would re-parse as operators", () => {
    const parsed = parseQuery('"is:all" "session:x" "kind:Decision"');
    expect(parsed.freeTerms).toEqual(["is:all", "session:x", "kind:Decision"]);
    const serialized = serializeQuery(parsed);
    expect(serialized).toBe('"is:all" "session:x" "kind:Decision"');
    expect(parseQuery(serialized)).toEqual(parsed);
  });
});

describe("setFacet", () => {
  it("removes include and exclude facets independently", () => {
    expect(setFacet("source:codex NOT kind:PostToolUse", "kind", null, "exclude")).toBe("source:codex");
    expect(setFacet("source:codex NOT kind:PostToolUse", "source", null)).toBe("NOT kind:PostToolUse");
  });

  it("clears the same value from the opposite mode when setting facets", () => {
    expect(setFacet("NOT source:codex", "source", "codex")).toBe("source:codex");
    expect(setFacet("source:codex", "source", "codex", "exclude")).toBe("NOT source:codex");
  });

  it("replaces the whole value list for the field", () => {
    expect(setFacet("source:codex,claude kind:Decision", "source", "cursor")).toBe("source:cursor kind:Decision");
  });

  it("accepts multi-value arrays and treats an empty array as removal", () => {
    expect(setFacet("kind:Decision", "source", ["codex", "claude"])).toBe("source:codex,claude kind:Decision");
    expect(setFacet("source:codex kind:Decision", "source", [])).toBe("kind:Decision");
  });

  it("preserves operator tokens it does not touch", () => {
    expect(setFacet("session:abc last:2h recall", "kind", "Decision")).toBe(
      "kind:Decision session:abc since:2h recall",
    );
  });
});

describe("removeFacetValue", () => {
  it("removes one value and re-serializes the rest", () => {
    expect(removeFacetValue("source:codex,claude", "source", "codex")).toBe("source:claude");
    expect(removeFacetValue("source:codex,claude", "source", "claude")).toBe("source:codex");
  });

  it("drops the facet entirely when the last value is removed", () => {
    expect(removeFacetValue("source:codex kind:Decision", "source", "codex")).toBe("kind:Decision");
  });

  it("removes exclude values in exclude mode", () => {
    expect(removeFacetValue("NOT kind:PostToolUse,Read source:codex", "kind", "Read", "exclude")).toBe(
      "source:codex NOT kind:PostToolUse",
    );
  });
});

describe("describeTimeSpec", () => {
  it("phrases durations as past windows on the since side", () => {
    expect(describeTimeSpec({ kind: "duration", value: "2h" }, "since")).toBe("Past 2 hours");
    expect(describeTimeSpec({ kind: "duration", value: "1w" }, "since")).toBe("Past 1 week");
    expect(describeTimeSpec({ kind: "duration", value: "30m" }, "until")).toBe("Until 30 minutes ago");
  });

  it("phrases keywords with the side word", () => {
    expect(describeTimeSpec({ kind: "keyword", value: "yesterday" }, "since")).toBe("Since yesterday");
    expect(describeTimeSpec({ kind: "keyword", value: "today" }, "until")).toBe("Until today");
  });

  it("phrases plain dates without a timezone shift", () => {
    expect(describeTimeSpec({ kind: "absolute", value: "2026-08-18" }, "until")).toMatch(/^Until Aug 18/);
    expect(describeTimeSpec({ kind: "absolute", value: "1999-01-02" }, "since")).toBe("Since Jan 2, 1999");
  });
});

describe("resolvesToPastInstant", () => {
  const now = new Date(2026, 7, 20, 12, 0, 0); // local 2026-08-20T12:00

  it("treats positive duration untils as past (until:2h means until two hours ago)", () => {
    expect(resolvesToPastInstant({ kind: "duration", value: "2h" }, now)).toBe(true);
    expect(resolvesToPastInstant({ kind: "duration", value: "1w" }, now)).toBe(true);
    expect(resolvesToPastInstant({ kind: "duration", value: "0m" }, now)).toBe(false);
  });

  it("resolves keywords to period end: yesterday is past, today is still live", () => {
    expect(resolvesToPastInstant({ kind: "keyword", value: "yesterday" }, now)).toBe(true);
    expect(resolvesToPastInstant({ kind: "keyword", value: "today" }, now)).toBe(false);
  });

  it("resolves plain dates to end of the local day", () => {
    expect(resolvesToPastInstant({ kind: "absolute", value: "2026-08-19" }, now)).toBe(true);
    expect(resolvesToPastInstant({ kind: "absolute", value: "2026-08-20" }, now)).toBe(false);
    expect(resolvesToPastInstant({ kind: "absolute", value: "2026-08-21" }, now)).toBe(false);
  });

  it("compares datetimes directly", () => {
    expect(resolvesToPastInstant({ kind: "absolute", value: "2026-08-20T11:00" }, now)).toBe(true);
    expect(resolvesToPastInstant({ kind: "absolute", value: "2026-08-20T13:00" }, now)).toBe(false);
  });
});
