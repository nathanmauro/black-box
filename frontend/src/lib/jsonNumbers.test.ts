import { describe, expect, it } from "vitest";
import { decimalNumberRoundtrips, scanJsonIdentity } from "./jsonNumbers";

describe("shared numeric JSON scan", () => {
  it("preserves identity semantics while exposing negative zero for display", () => {
    expect(scanJsonIdentity(' { "n" : -0 } ')).toEqual({
      compact: '{"n":-0}',
      safeNumbers: true,
      depth: 1,
      negativeZero: true,
    });
    expect(decimalNumberRoundtrips("-0")).toBe(true);
  });
  it("distinguishes inexact decimal and unsupported legacy numeric forms", () => {
    expect(decimalNumberRoundtrips("9007199254740993")).toBe(false);
    expect(decimalNumberRoundtrips("100e-2")).toBe(true);
    expect(decimalNumberRoundtrips("0x10")).toBeUndefined();
  });
  it("fails conservatively rather than throwing if numeric-token validation drifts", () => {
    expect(scanJsonIdentity('{"n":-}')).toEqual({
      compact: '{"n":-}',
      safeNumbers: false,
      depth: 1,
      negativeZero: false,
    });
  });
});
