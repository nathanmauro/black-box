import { describe, expect, it } from "vitest";
import {
  clampLegs,
  ideaFieldsFromEvent,
  ideaHeadline,
  isUnanswered,
  parseOriginParam,
  parseStatusParam,
  safeIdeaLink,
} from "./ideas";

describe("idea helpers", () => {
  it("parses status params in canonical order and ignores unknown values", () => {
    expect(parseStatusParam("tracked,bogus,untouched")).toEqual(["untouched", "tracked"]);
    expect(parseStatusParam(["superseded", "partially-built"])).toEqual([
      "partially-built",
      "superseded",
    ]);
    expect(parseStatusParam(undefined)).toEqual([]);
  });

  it("accepts one known origin and normalizes the legacy spelling", () => {
    expect(parseOriginParam("agent-proposed")).toBe("agent-proposed");
    expect(parseOriginParam("nathan-aside")).toBe("human-aside");
    expect(parseOriginParam("robot")).toBeNull();
    expect(parseOriginParam(undefined)).toBeNull();
  });

  it("clamps legs into 0..10 and treats missing values as null", () => {
    expect(clampLegs(7)).toBe(7);
    expect(clampLegs(14)).toBe(10);
    expect(clampLegs(-2)).toBe(0);
    expect(clampLegs(null)).toBeNull();
    expect(clampLegs("x")).toBeNull();
  });

  it("flags only agent-proposed untouched ideas as unanswered", () => {
    expect(isUnanswered({ origin: "agent-proposed", status: "untouched" })).toBe(true);
    expect(isUnanswered({ origin: "agent-proposed", status: "tracked" })).toBe(false);
    expect(isUnanswered({ origin: "human-aside", status: "untouched" })).toBe(false);
  });

  it("only turns web and obsidian links into anchors", () => {
    expect(safeIdeaLink("https://linear.app/team/issue/BB-1")).toEqual({
      href: "https://linear.app/team/issue/BB-1",
      external: true,
    });
    expect(safeIdeaLink("obsidian://open?vault=obsidian&file=Ideas%2Fx")).toEqual({
      href: "obsidian://open?vault=obsidian&file=Ideas%2Fx",
      external: false,
    });
    expect(safeIdeaLink("javascript:alert(1)")).toBeNull();
    expect(safeIdeaLink("not a url")).toBeNull();
    expect(safeIdeaLink("")).toBeNull();
  });

  it("reads idea fields from metadata and falls back to the rendered text", () => {
    const fromMeta = ideaFieldsFromEvent(
      {
        kind: "idea",
        title: "Tangent router",
        oneLiner: "File asides as ideas.",
        origin: "nathan-aside",
        legs: 8,
        connects: ["a", " ", "b"],
      },
      "[Idea] ignored — ignored",
    );
    expect(fromMeta).toMatchObject({
      title: "Tangent router",
      oneLiner: "File asides as ideas.",
      origin: "human-aside",
      status: "untouched",
      legs: 8,
      connects: ["a", "b"],
    });

    const fromText = ideaFieldsFromEvent(
      {},
      "[Idea] Tangent router — File asides as ideas.\n- legs: 8",
    );
    expect(ideaHeadline(fromText)).toBe("Tangent router — File asides as ideas.");
  });
});
