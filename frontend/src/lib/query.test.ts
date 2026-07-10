import { describe, expect, it } from "vitest";
import { parseQuery, serializeQuery, setFacet } from "./query";

describe("parseQuery", () => {
  it("parses canonical facets and free text", () => {
    expect(parseQuery("source:codex kind:Decision rebase")).toEqual({
      facets: { source: "codex", kind: "Decision" },
      excludeFacets: {},
      text: ["rebase"],
    });
  });

  it("maps backend aliases and lets the last value win", () => {
    expect(parseQuery("event_type:Handoff kind:Decision tool_name:Edit tool:Read cwd:/tmp project:~/x")).toEqual({
      facets: { kind: "Decision", tool: "Read", project: "~/x" },
      excludeFacets: {},
      text: [],
    });
  });

  it("keeps quoted text and quoted facet values together", () => {
    expect(parseQuery('tool:Edit "ask history" project:"/Users/nathan/Developer/proj/sba agentic"')).toEqual({
      facets: { tool: "Edit", project: "/Users/nathan/Developer/proj/sba agentic" },
      excludeFacets: {},
      text: ["ask history"],
    });
  });

  it("parses readable and terse negative facets", () => {
    expect(parseQuery("source:codex NOT kind:PostToolUse -tool:Read recall")).toEqual({
      facets: { source: "codex" },
      excludeFacets: { kind: "PostToolUse", tool: "Read" },
      text: ["recall"],
    });
  });

  it("preserves dangling NOT before text", () => {
    expect(parseQuery("NOT recall bug")).toEqual({
      facets: {},
      excludeFacets: {},
      text: ["NOT", "recall", "bug"],
    });
  });
});

describe("serializeQuery", () => {
  it("round-trips to a normalized query", () => {
    const parsed = parseQuery('source:codex kind:Decision "rebase main"');
    expect(serializeQuery(parsed)).toBe('source:codex kind:Decision "rebase main"');
  });

  it("serializes negative facets with readable NOT syntax", () => {
    expect(
      serializeQuery({
        facets: { source: "codex" },
        excludeFacets: { kind: "PostToolUse" },
        text: ["recall"],
      }),
    ).toBe("source:codex NOT kind:PostToolUse recall");
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
});
