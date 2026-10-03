import { describe, expect, it } from "vitest";
import { compareCanonicalInstants } from "./instant";

describe("canonical instant comparison", () => {
  it("distinguishes nanoseconds within one millisecond in either order", () => {
    const older = "2026-10-03T12:00:00.123456788Z";
    const newer = "2026-10-03T12:00:00.123456789Z";
    expect(Date.parse(older)).toBe(Date.parse(newer));
    expect(compareCanonicalInstants(older, newer)).toBe(-1);
    expect(compareCanonicalInstants(newer, older)).toBe(1);
  });
  it("normalizes fractional precision only for genuinely equal instants", () => {
    for (const fraction of ["1", "100", "100000", "100000000"]) {
      expect(
        compareCanonicalInstants(`2026-10-03T12:00:00.${fraction}Z`, "2026-10-03T12:00:00.1Z"),
      ).toBe(0);
    }
    expect(compareCanonicalInstants("2026-10-03T12:00:00Z", "2026-10-03T12:00:00.000000000Z")).toBe(
      0,
    );
    expect(compareCanonicalInstants("2026-10-03T12:00:00Z", "2026-10-03T12:00:00.000000001Z")).toBe(
      -1,
    );
  });
  it("orders second, epoch, year and full Java Instant boundaries", () => {
    const chronological = [
      "-1000000000-01-01T00:00:00Z",
      "-10000-01-01T00:00:00Z",
      "-0001-12-31T23:59:59.999999999Z",
      "0000-01-01T00:00:00Z",
      "1969-12-31T23:59:59.999999999Z",
      "1970-01-01T00:00:00Z",
      "2026-10-03T12:00:00.999999999Z",
      "2026-10-03T12:00:01Z",
      "9999-12-31T23:59:59.999999999Z",
      "+10000-01-01T00:00:00Z",
      "+1000000000-12-31T23:59:59.999999999Z",
    ];
    for (let i = 1; i < chronological.length; i++) {
      expect(compareCanonicalInstants(chronological[i - 1], chronological[i])).toBe(-1);
    }
    expect(compareCanonicalInstants("2000-02-29T00:00:00Z", "2000-03-01T00:00:00Z")).toBe(-1);
  });
  it("leaves missing, malformed and noncanonical values to the caller's existing policy", () => {
    for (const value of [
      null,
      undefined,
      "",
      "invalid",
      "2026-02-29T00:00:00Z",
      "1900-02-29T00:00:00Z",
      "2026-13-01T00:00:00Z",
      "2026-01-00T00:00:00Z",
      "2026-01-01T24:00:00Z",
      "2026-01-01T00:60:00Z",
      "2026-01-01T00:00:60Z",
      "2026-01-01T00:00:00.1234567890Z",
      "2026-01-01T00:00:00",
      "2026-01-01T01:00:00+01:00",
      "+2026-01-01T00:00:00Z",
      "-0000-01-01T00:00:00Z",
      "+1000000001-01-01T00:00:00Z",
    ]) {
      expect(compareCanonicalInstants(value, "2026-01-01T00:00:00Z")).toBeUndefined();
      expect(compareCanonicalInstants("2026-01-01T00:00:00Z", value)).toBeUndefined();
    }
  });
});
