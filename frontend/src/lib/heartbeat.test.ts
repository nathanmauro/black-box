import { describe, it, expect } from "vitest";
import { formatRelativeTime, getSessionStatus } from "./heartbeat";

describe("heartbeat", () => {
  describe("formatRelativeTime", () => {
    it("returns 'unknown' for null", () => {
      expect(formatRelativeTime(null)).toBe("unknown");
    });

    it("returns 'unknown' for undefined", () => {
      expect(formatRelativeTime(undefined)).toBe("unknown");
    });

    it("returns 'just now' for very recent times", () => {
      const now = new Date().toISOString();
      expect(formatRelativeTime(now)).toBe("just now");
    });

    it("formats seconds ago", () => {
      const fiveSecondsAgo = new Date(Date.now() - 5000).toISOString();
      expect(formatRelativeTime(fiveSecondsAgo)).toBe("5s ago");
    });

    it("formats minutes ago", () => {
      const twoMinutesAgo = new Date(Date.now() - 120_000).toISOString();
      expect(formatRelativeTime(twoMinutesAgo)).toBe("2m ago");
    });

    it("formats hours ago", () => {
      const threeHoursAgo = new Date(Date.now() - 3 * 3600_000).toISOString();
      expect(formatRelativeTime(threeHoursAgo)).toBe("3h ago");
    });

    it("formats days ago", () => {
      const twoDaysAgo = new Date(Date.now() - 2 * 86400_000).toISOString();
      expect(formatRelativeTime(twoDaysAgo)).toBe("2d ago");
    });
  });

  describe("getSessionStatus", () => {
    it("returns 'stale' for null", () => {
      expect(getSessionStatus(null)).toBe("stale");
    });

    it("returns 'stale' for undefined", () => {
      expect(getSessionStatus(undefined)).toBe("stale");
    });

    it("returns 'running' for very recent activity", () => {
      const fiveSecondsAgo = new Date(Date.now() - 5000).toISOString();
      expect(getSessionStatus(fiveSecondsAgo)).toBe("running");
    });

    it("returns 'idle' for activity 30 seconds ago", () => {
      const thirtySecondsAgo = new Date(Date.now() - 30_000).toISOString();
      expect(getSessionStatus(thirtySecondsAgo)).toBe("idle");
    });

    it("returns 'stale' for activity 10 minutes ago", () => {
      const tenMinutesAgo = new Date(Date.now() - 10 * 60_000).toISOString();
      expect(getSessionStatus(tenMinutesAgo)).toBe("stale");
    });
  });
});
