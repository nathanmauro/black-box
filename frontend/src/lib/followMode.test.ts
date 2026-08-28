import { describe, it, expect, beforeEach } from "vitest";
import { loadFollowMode, saveFollowMode } from "./followMode";

describe("followMode", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe("loadFollowMode", () => {
    it("returns false by default", () => {
      expect(loadFollowMode()).toBe(false);
    });

    it("returns true when saved as true", () => {
      localStorage.setItem("streamFollowMode", "true");
      expect(loadFollowMode()).toBe(true);
    });

    it("returns false when saved as false", () => {
      localStorage.setItem("streamFollowMode", "false");
      expect(loadFollowMode()).toBe(false);
    });
  });

  describe("saveFollowMode", () => {
    it("saves true to localStorage", () => {
      saveFollowMode(true);
      expect(localStorage.getItem("streamFollowMode")).toBe("true");
    });

    it("saves false to localStorage", () => {
      saveFollowMode(false);
      expect(localStorage.getItem("streamFollowMode")).toBe("false");
    });
  });
});
