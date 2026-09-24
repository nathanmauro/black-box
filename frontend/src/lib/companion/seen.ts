import { createSignal, type Accessor } from "solid-js";

export const SEEN_STORAGE_KEY = "blackbox.companion.seen.v1";
export const SEEN_MAX = 2000;

export type SeenStore = {
  seen: Accessor<ReadonlySet<string>>;
  markAll(ids: Iterable<string>): void;
};

export function safeStorage(): Storage | null {
  try {
    const storage = window.localStorage;
    const probe = "__companion_probe__";
    storage.setItem(probe, "1");
    storage.removeItem(probe);
    return storage;
  } catch {
    return null;
  }
}

export function createSeenStore(storage: Storage | null = safeStorage()): SeenStore {
  const [seen, setSeen] = createSignal<ReadonlySet<string>>(load(storage));
  return {
    seen,
    markAll(ids) {
      const next = new Set(seen());
      let changed = false;
      for (const id of ids) {
        if (!next.has(id)) {
          next.add(id);
          changed = true;
        }
      }
      if (!changed) return;
      const trimmed = next.size > SEEN_MAX ? new Set([...next].slice(next.size - SEEN_MAX)) : next;
      setSeen(trimmed);
      persist(storage, trimmed);
    },
  };
}

function load(storage: Storage | null): Set<string> {
  if (!storage) return new Set();
  try {
    const parsed: unknown = JSON.parse(storage.getItem(SEEN_STORAGE_KEY) ?? "[]");
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === "string") : []);
  } catch {
    return new Set();
  }
}

function persist(storage: Storage | null, seen: ReadonlySet<string>): void {
  if (!storage) return;
  try {
    storage.setItem(SEEN_STORAGE_KEY, JSON.stringify([...seen]));
  } catch {
    // Storage full or blocked: the in-memory set still serves this page.
  }
}
