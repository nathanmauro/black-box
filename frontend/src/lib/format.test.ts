import { describe, expect, it } from "vitest";
import { sourceColor, sourceLabel, timeAgo, truncatePath } from "./format";

describe("timeAgo", () => {
  const now = Date.parse("2026-06-16T20:00:00.000Z");

  it("formats seconds, hours, and days compactly", () => {
    expect(timeAgo("2026-06-16T19:59:30.000Z", now)).toBe("30s");
    expect(timeAgo("2026-06-16T17:00:00.000Z", now)).toBe("3h");
    expect(timeAgo("2026-06-14T20:00:00.000Z", now)).toBe("2d");
  });
});

describe("truncatePath", () => {
  it("replaces a macOS user home prefix with tilde", () => {
    expect(truncatePath("/Users/nathan/Developer/proj/x")).toBe("~/Developer/proj/x");
  });
});

describe("source helpers", () => {
  it("maps known sources and falls back to neutral", () => {
    expect(sourceColor("codex")).toBe("#34d399");
    expect(sourceColor("???")).toBe("#6b7280");
  });

  it("labels known sources", () => {
    expect(sourceLabel("raycast")).toBe("Raycast");
  });
});
