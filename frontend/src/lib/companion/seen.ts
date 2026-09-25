import { createSignal, onCleanup, type Accessor } from "solid-js";

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

  // Two /companion tabs each hold their own in-memory copy; without this, the second tab's markAll
  // would persist only its own ids and silently clobber whatever the first tab already wrote.
  function handleStorageEvent(event: StorageEvent): void {
    if (event.key !== SEEN_STORAGE_KEY) return;
    const incoming = parse(event.newValue);
    if (!incoming) return;
    setSeen((current) => {
      const next = new Set(current);
      let changed = false;
      for (const id of incoming) {
        if (!next.has(id)) {
          next.add(id);
          changed = true;
        }
      }
      return changed ? next : current;
    });
  }
  // Each mount of the page that owns this store (CompanionPage, re-mounted on every SPA navigate
  // back to /companion) adds one listener; without removing it on cleanup they pile up, each
  // firing on every future storage event.
  if (typeof window !== "undefined") {
    window.addEventListener("storage", handleStorageEvent);
    onCleanup(() => window.removeEventListener("storage", handleStorageEvent));
  }

  return {
    seen,
    markAll(ids) {
      // Re-read storage first so a concurrent write from another tab (since this store's last read
      // or write) is merged in rather than overwritten.
      const next = new Set([...seen(), ...load(storage)]);
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

function parse(raw: string | null): Set<string> | null {
  if (raw == null) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return new Set(
      Array.isArray(parsed)
        ? parsed.filter((value): value is string => typeof value === "string")
        : [],
    );
  } catch {
    return null;
  }
}

function load(storage: Storage | null): Set<string> {
  if (!storage) return new Set();
  return parse(storage.getItem(SEEN_STORAGE_KEY)) ?? new Set();
}

function persist(storage: Storage | null, seen: ReadonlySet<string>): void {
  if (!storage) return;
  try {
    storage.setItem(SEEN_STORAGE_KEY, JSON.stringify([...seen]));
  } catch {
    // Storage full or blocked: the in-memory set still serves this page.
  }
}
