import { describe, expect, it } from "vitest";
import {
  looksLikeJson,
  parseJsonObject,
  parsePayload,
  parseToolResult,
  parsePayloadWithPrecision,
  parseToolResultWithPrecision,
  payloadText,
} from "./payload";

describe("parsePayload", () => {
  it("unwraps double-serialized JSON in two passes", () => {
    expect(parsePayload(JSON.stringify(JSON.stringify({ command: "ls" })))).toEqual({
      command: "ls",
    });
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
    expect(parseToolResult(raw)).toEqual({
      exit_code: 0,
      wall_time: "1.2 seconds",
      output: "42 tests passed",
    });
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

describe("numeric preview diagnostics", () => {
  it.each([
    "9007199254740993",
    "-9007199254740993",
    "0.10000000000000001",
    "1e400",
    "1e-400",
    "-0",
  ])("flags %s without changing either parser's value", (token) => {
    const raw = `{"nested":[{"n":${token}}]}`;
    expect(parsePayloadWithPrecision(raw)).toEqual({
      value: parsePayload(raw),
      numericPrecision: "changed",
    });
    expect(parseToolResultWithPrecision(raw)).toEqual({
      value: parseToolResult(raw),
      numericPrecision: "changed",
    });
  });
  it("checks double-encoded JSON but does not invent a third decoding pass", () => {
    const raw = '{"n":9007199254740993}';
    const twice = JSON.stringify(raw);
    expect(parsePayloadWithPrecision(twice).numericPrecision).toBe("changed");
    expect(parsePayloadWithPrecision(JSON.stringify(twice))).toEqual({
      value: raw,
      numericPrecision: "preserved",
    });
  });
  it("ignores numeric-looking strings, escaped quotes and unconverted plain text", () => {
    for (const raw of [
      JSON.stringify({ text: 'escaped "9007199254740993" \\ 1e400 -0', n: 0.1 }),
      JSON.stringify("9007199254740993"),
      "9007199254740993",
      "plain text",
      "",
    ]) {
      expect(parsePayloadWithPrecision(raw)).toEqual({
        value: parsePayload(raw),
        numericPrecision: "preserved",
      });
    }
  });
  it("leaves malformed JSON untouched without a misleading numeric warning", () => {
    const raw = '{"n":9007199254740993,}';
    expect(parsePayloadWithPrecision(raw)).toEqual({ value: raw, numericPrecision: "preserved" });
    expect(parseToolResultWithPrecision(raw)).toEqual({
      value: raw,
      numericPrecision: "preserved",
    });
  });
  it("checks ordinary 50k JSON but marks over-budget parsed layers unchecked", () => {
    const ordinary = JSON.stringify({ text: "x".repeat(50_000) });
    expect(parsePayloadWithPrecision(ordinary).numericPrecision).toBe("preserved");
    const huge = JSON.stringify({ text: "x".repeat(150_000) });
    expect(parsePayloadWithPrecision(huge).numericPrecision).toBe("unchecked");
    expect(parsePayloadWithPrecision(huge).value).toEqual(parsePayload(huge));
  });
  it("keeps unchecked status across decoding layers and lets proven loss take precedence", () => {
    const prefix = " ".repeat(132_000);
    expect(parsePayloadWithPrecision(JSON.stringify(prefix + '{"n":1}')).numericPrecision).toBe(
      "unchecked",
    );
    expect(
      parsePayloadWithPrecision(JSON.stringify(prefix + '{"n":9007199254740993}')).numericPrecision,
    ).toBe("changed");
  });
  it("scans deep inline JSON iteratively and bounds huge exponent analysis", () => {
    const deep = '{"item":'.repeat(2200) + "9007199254740993" + "}".repeat(2200);
    expect(parsePayloadWithPrecision(deep).numericPrecision).toBe("changed");
    expect(parsePayloadWithPrecision(`{"n":1e${"9".repeat(12_000)}}`).numericPrecision).toBe(
      "changed",
    );
  });
  it.each([
    ["0", "preserved"],
    ["1", "preserved"],
    ["9007199254740993", "changed"],
    ["1e-400", "changed"],
    ["-0", "changed"],
    ["0x10", "unchecked"],
    ["Infinity", "preserved"],
    ["failure", "preserved"],
  ])(
    "diagnoses legacy exit code %s without changing existing conversion",
    (exit, numericPrecision) => {
      const raw = JSON.stringify(`Exit code: ${exit}\nWall time: 1 second\nOutput:\ndone`);
      expect(parseToolResultWithPrecision(raw)).toEqual({
        value: parseToolResult(raw),
        numericPrecision,
      });
    },
  );
  it("retains proven legacy loss after an over-budget serialized layer", () => {
    const raw = JSON.stringify(
      `Exit code: 9007199254740993\nWall time: 1 second\nOutput:\n${"x".repeat(150_000)}`,
    );
    expect(parseToolResultWithPrecision(raw).numericPrecision).toBe("changed");
  });
});
