import { describe, expect, it } from "vitest";
import { outputLooksFailed } from "./failure";

describe("outputLooksFailed", () => {
  it("recognizes the explicit failure shapes", () => {
    expect(outputLooksFailed('{"is_error":true}')).toBe(true);
    expect(outputLooksFailed('{"isError":true}')).toBe(true);
    expect(outputLooksFailed('{"exit_code":1}')).toBe(true);
    expect(outputLooksFailed('{"exitCode":2}')).toBe(true);
    expect(outputLooksFailed('{"exit_code":"3"}')).toBe(true);
    expect(outputLooksFailed('{"error":"file not found"}')).toBe(true);
    expect(outputLooksFailed('{"status":"error"}')).toBe(true);
    expect(outputLooksFailed('{"status":"failed"}')).toBe(true);
  });

  it("stays quiet on success shapes", () => {
    expect(outputLooksFailed('{"exit_code":0}')).toBe(false);
    expect(outputLooksFailed('{"is_error":false}')).toBe(false);
    expect(outputLooksFailed('{"error":""}')).toBe(false);
    expect(outputLooksFailed('{"status":"ok","output":"done"}')).toBe(false);
  });

  it("fails quiet on unknown, malformed, or non-object payloads", () => {
    expect(outputLooksFailed(null)).toBe(false);
    expect(outputLooksFailed(undefined)).toBe(false);
    expect(outputLooksFailed("")).toBe(false);
    expect(outputLooksFailed("   ")).toBe(false);
    expect(outputLooksFailed('{"is_error": tru')).toBe(false);
    expect(outputLooksFailed('"just a string result"')).toBe(false);
    expect(outputLooksFailed("[1,2,3]")).toBe(false);
    expect(outputLooksFailed("Error: something exploded")).toBe(false);
  });
});
