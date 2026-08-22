// Saved stream views (spec §8, D13): a view IS a q string, so it is also a copyable URL and
// stays permanently live because relative time resolves server-side at execution (D7). One user,
// one machine — localStorage only, no server store. Built-in presets live with the Views
// dropdown in StreamPage; this module owns the user-saved list.
const KEY = "blackbox.savedViews";

export type SavedView = {
  name: string;
  q: string;
  createdAt: string;
};

/** The persisted list, oldest first. Corrupt or unavailable storage fails soft to []. */
export function listSavedViews(): SavedView[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isSavedView);
  } catch {
    return [];
  }
}

/**
 * Saves a view (replacing any existing view of the same name) and returns the updated list.
 * Blank names or empty q strings are rejected by returning the list unchanged.
 */
export function saveSavedView(name: string, q: string): SavedView[] {
  const cleanName = name.trim();
  const cleanQ = q.trim();
  const existing = listSavedViews();
  if (!cleanName || !cleanQ) return existing;
  const next = [
    ...existing.filter((view) => view.name !== cleanName),
    { name: cleanName, q: cleanQ, createdAt: new Date().toISOString() },
  ];
  persist(next);
  return next;
}

/** Removes a view by name and returns the updated list. */
export function removeSavedView(name: string): SavedView[] {
  const next = listSavedViews().filter((view) => view.name !== name);
  persist(next);
  return next;
}

function persist(views: SavedView[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(views));
  } catch {
    // Private-mode storage failures degrade to session-only views (streamDensity idiom).
  }
}

function isSavedView(value: unknown): value is SavedView {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record.name === "string" &&
    record.name.trim() !== "" &&
    typeof record.q === "string" &&
    record.q.trim() !== "" &&
    typeof record.createdAt === "string"
  );
}
