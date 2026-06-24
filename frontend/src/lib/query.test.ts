import { describe, expect, it } from "vitest";
import { parseQuery, serializeQuery } from "./query";

describe("parseQuery", () => {
  it("parses canonical facets and free text", () => {
    expect(parseQuery("source:codex kind:Decision rebase")).toEqual({
      facets: { source: "codex", kind: "Decision" },
      text: ["rebase"],
    });
  });

  it("maps backend aliases and lets the last value win", () => {
    expect(parseQuery("event_type:Handoff kind:Decision tool_name:Edit tool:Read cwd:/tmp project:~/x")).toEqual({
      facets: { kind: "Decision", tool: "Read", project: "~/x" },
      text: [],
    });
  });

  it("keeps quoted text and quoted facet values together", () => {
    expect(parseQuery('tool:Edit "ask history" project:"/Users/nathan/Developer/proj/sba agentic"')).toEqual({
      facets: { tool: "Edit", project: "/Users/nathan/Developer/proj/sba agentic" },
      text: ["ask history"],
    });
  });
});

describe("serializeQuery", () => {
  it("round-trips to a normalized query", () => {
    const parsed = parseQuery('source:codex kind:Decision "rebase main"');
    expect(serializeQuery(parsed)).toBe('source:codex kind:Decision "rebase main"');
  });
});
