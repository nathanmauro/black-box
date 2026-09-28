import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

async function freshStore() {
  vi.resetModules();
  return import("./humanOnly");
}

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe("humanOnly store", () => {
  it("defaults to off", async () => {
    const store = await freshStore();
    expect(store.humanOnly()).toBe(false);
  });

  it("persists across module loads", async () => {
    const first = await freshStore();
    first.toggleHumanOnly();
    expect(first.humanOnly()).toBe(true);
    expect(localStorage.getItem("bb.humanOnly")).toBe("true");

    const second = await freshStore();
    expect(second.humanOnly()).toBe(true);
    second.setHumanOnly(false);
    expect(localStorage.getItem("bb.humanOnly")).toBe("false");
    expect((await freshStore()).humanOnly()).toBe(false);
  });

  it("survives storage that throws on read and write", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const store = await freshStore();
    expect(store.humanOnly()).toBe(false);
    expect(() => store.toggleHumanOnly()).not.toThrow();
    expect(store.humanOnly()).toBe(true);
  });
});

describe("human text helpers", () => {
  it("leads with the first non-empty line", async () => {
    const { leadLine } = await freshStore();
    expect(leadLine("\n  first line \nsecond")).toBe("first line");
    expect(leadLine("   ")).toBeNull();
    expect(leadLine(null)).toBeNull();
  });

  it("swaps in humanText only in human mode", async () => {
    const { withHumanText } = await freshStore();
    const item = { text: "raw", humanText: "clean" };
    expect(withHumanText(item, true).text).toBe("clean");
    expect(withHumanText(item, false).text).toBe("raw");
    expect(withHumanText({ text: "raw", humanText: null }, true).text).toBe("raw");
  });

  it("keeps a title as secondary text only when it says something the lead does not", async () => {
    const { distinctTitle } = await freshStore();
    const turn = "didn't I have some   type of session\nsecond line";
    expect(distinctTitle("didn't I have some type of session", turn)).toBeNull();
    expect(distinctTitle("didn't I have some type...", turn)).toBeNull();
    expect(distinctTitle("User wanted to fix a monitor", turn)).toBe(
      "User wanted to fix a monitor",
    );
    expect(distinctTitle("Any title", null)).toBe("Any title");
    expect(distinctTitle("  ", turn)).toBeNull();
  });
});
