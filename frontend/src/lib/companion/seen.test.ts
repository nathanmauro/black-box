import { describe, expect, it } from "vitest";
import { createSeenStore, SEEN_MAX, SEEN_STORAGE_KEY } from "./seen";

class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() { return this.map.size; }
  clear() { this.map.clear(); }
  getItem(key: string) { return this.map.get(key) ?? null; }
  key(index: number) { return [...this.map.keys()][index] ?? null; }
  removeItem(key: string) { this.map.delete(key); }
  setItem(key: string, value: string) { this.map.set(key, value); }
}

describe("createSeenStore", () => {
  it("works without storage", () => {
    const store = createSeenStore(null);
    expect(store.seen().has("a")).toBe(false);
    store.markAll(["a", "b"]);
    expect(store.seen().has("a")).toBe(true);
    expect(store.seen().size).toBe(2);
  });

  it("persists and reloads from storage", () => {
    const storage = new MemoryStorage();
    createSeenStore(storage).markAll(["x"]);
    expect(JSON.parse(storage.getItem(SEEN_STORAGE_KEY) ?? "[]")).toEqual(["x"]);
    expect(createSeenStore(storage).seen().has("x")).toBe(true);
  });

  it("ignores corrupt storage and keeps only the newest SEEN_MAX ids", () => {
    const storage = new MemoryStorage();
    storage.setItem(SEEN_STORAGE_KEY, "{not json");
    const store = createSeenStore(storage);
    expect(store.seen().size).toBe(0);
    store.markAll(Array.from({ length: SEEN_MAX + 10 }, (_, index) => `id-${index}`));
    expect(store.seen().size).toBe(SEEN_MAX);
    expect(store.seen().has("id-0")).toBe(false);
    expect(store.seen().has(`id-${SEEN_MAX + 9}`)).toBe(true);
  });

  it("does not notify when nothing new is marked", () => {
    const store = createSeenStore(null);
    store.markAll(["a"]);
    const before = store.seen();
    store.markAll(["a"]);
    expect(store.seen()).toBe(before);
  });

  it("does not clobber another tab's ids already in storage when persisting", () => {
    const storage = new MemoryStorage();
    const tab1 = createSeenStore(storage);
    const tab2 = createSeenStore(storage); // snapshots storage before tab1 writes to it
    tab1.markAll(["a", "b"]);
    tab2.markAll(["c"]);
    const persisted: string[] = JSON.parse(storage.getItem(SEEN_STORAGE_KEY) ?? "[]");
    expect(persisted.sort()).toEqual(["a", "b", "c"]);
  });

  it("merges ids a storage event reports from another tab", () => {
    const storage = new MemoryStorage();
    const store = createSeenStore(storage);
    expect(store.seen().has("from-other-tab")).toBe(false);
    window.dispatchEvent(new StorageEvent("storage", { key: SEEN_STORAGE_KEY, newValue: JSON.stringify(["from-other-tab"]) }));
    expect(store.seen().has("from-other-tab")).toBe(true);
  });

  it("ignores storage events for unrelated keys", () => {
    const store = createSeenStore(null);
    window.dispatchEvent(new StorageEvent("storage", { key: "some-other-key", newValue: JSON.stringify(["nope"]) }));
    expect(store.seen().has("nope")).toBe(false);
  });
});
