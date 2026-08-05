import { describe, expect, it } from "vitest";
import { looksLikeJson, parseJsonObject, parsePayload, parseToolResult, payloadText } from "./payload";

describe("parsePayload", () => {
  it("unwraps double-serialized JSON in two passes", () => {
    expect(parsePayload(JSON.stringify(JSON.stringify({ command: "ls" })))).toEqual({ command: "ls" });
  });
  it("returns raw text for non-JSON strings", () => {
    expect(parsePayload("plain text")).toBe("plain text");
  });
  it("returns null for empty input", () => {
    expect(parsePayload("  ")).toBeNull();
    expect(parsePayload(null)).toBeNull();
  });
});

describe("parseToolResult", () => {
  it("extracts the Codex exit/wall/output result format into structured fields", () => {
    const raw = JSON.stringify("Exit code: 0\nWall time: 1.2 seconds\nOutput:\n42 tests passed");
    expect(parseToolResult(raw)).toEqual({ exit_code: 0, wall_time: "1.2 seconds", output: "42 tests passed" });
  });
  it("passes through strings that do not match the format", () => {
    expect(parseToolResult(JSON.stringify("just output"))).toBe("just output");
  });
});

describe("payloadText", () => {
  it("prefers the output field of structured results", () => {
    expect(payloadText(JSON.stringify({ output: "hello", exit_code: 0 }))).toBe("hello");
  });
});

describe("parseJsonObject", () => {
  it("parses objects and rejects arrays", () => {
    expect(parseJsonObject('{"a":1}')).toEqual({ a: 1 });
    expect(parseJsonObject("[1]")).toBeNull();
  });
});

describe("looksLikeJson", () => {
  it("detects object and array shapes", () => {
    expect(looksLikeJson('{"a":1}')).toBe(true);
    expect(looksLikeJson("plain")).toBe(false);
  });
});
