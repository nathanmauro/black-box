import { beforeEach, describe, expect, it, vi } from "vitest";
import { listSavedViews, removeSavedView, saveSavedView } from "./savedViews";

const KEY = "blackbox.savedViews";

beforeEach(() => {
  localStorage.clear();
});

describe("savedViews", () => {
  it("returns an empty list when nothing was saved", () => {
    expect(listSavedViews()).toEqual([]);
  });

  it("saves, lists, and removes views through localStorage", () => {
    const afterSave = saveSavedView("My decisions", "kind:Decision last:7d");
    expect(afterSave).toMatchObject([{ name: "My decisions", q: "kind:Decision last:7d" }]);
    expect(typeof afterSave[0].createdAt).toBe("string");
    expect(listSavedViews()).toEqual(afterSave);
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual(afterSave);

    saveSavedView("Codex", "source:codex last:2h");
    expect(listSavedViews().map((view) => view.name)).toEqual(["My decisions", "Codex"]);

    const afterRemove = removeSavedView("My decisions");
    expect(afterRemove.map((view) => view.name)).toEqual(["Codex"]);
    expect(listSavedViews()).toEqual(afterRemove);
  });

  it("replaces an existing view of the same name instead of duplicating it", () => {
    saveSavedView("Mine", "kind:Decision");
    const next = saveSavedView("Mine", "kind:Handoff");
    expect(next).toHaveLength(1);
    expect(next[0].q).toBe("kind:Handoff");
  });

  it("rejects blank names and empty q strings without touching storage", () => {
    saveSavedView("Keep", "kind:Decision");
    expect(saveSavedView("   ", "kind:Decision")).toHaveLength(1);
    expect(saveSavedView("Empty q", "   ")).toHaveLength(1);
    expect(listSavedViews().map((view) => view.name)).toEqual(["Keep"]);
  });

  it("fails soft to an empty list on corrupt storage", () => {
    localStorage.setItem(KEY, "{not json");
    expect(listSavedViews()).toEqual([]);
    // Saving over the corrupt payload repairs it.
    expect(saveSavedView("Fresh", "kind:Decision")).toHaveLength(1);
    expect(JSON.parse(localStorage.getItem(KEY)!)).toMatchObject([{ name: "Fresh" }]);
  });

  it("drops malformed entries while keeping valid ones", () => {
    localStorage.setItem(
      KEY,
      JSON.stringify([
        { name: "Good", q: "kind:Decision", createdAt: "2026-08-20T00:00:00Z" },
        { name: "", q: "kind:Decision", createdAt: "2026-08-20T00:00:00Z" },
        { name: "No q", q: "", createdAt: "2026-08-20T00:00:00Z" },
        "not an object",
        null,
        { name: "Missing createdAt", q: "kind:Handoff" },
      ]),
    );
    expect(listSavedViews()).toEqual([{ name: "Good", q: "kind:Decision", createdAt: "2026-08-20T00:00:00Z" }]);
  });

  it("returns an empty list and swallows writes when storage throws", () => {
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    try {
      expect(listSavedViews()).toEqual([]);
      expect(() => saveSavedView("Mine", "kind:Decision")).not.toThrow();
      expect(() => removeSavedView("Mine")).not.toThrow();
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});
