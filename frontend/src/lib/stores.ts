import { createResource, createSignal } from "solid-js";
import { type AgentSession, getSessions, search, type SearchResponse } from "./api";

const [selectedSources, setSelectedSources] = createSignal<Set<string>>(new Set());

export const sourceFilter = {
  selected: selectedSources,
  key: () => [...selectedSources()].sort().join(","),
  toggle(source: string) {
    setSelectedSources((current) => {
      const next = new Set(current);
      if (next.has(source)) next.delete(source);
      else next.add(source);
      return next;
    });
  },
  clear() {
    setSelectedSources(new Set<string>());
  },
  isActive(source: string) {
    const selected = selectedSources();
    return selected.size === 0 || selected.has(source);
  },
  matches<T extends { source: string }>(items: T[]): T[] {
    const selected = selectedSources();
    if (!selected.size) return items;
    return items.filter((item) => selected.has(item.source));
  },
};

export function createSessionsResource(limit = 250) {
  return createResource(
    () => sourceFilter.key(),
    async () => sourceFilter.matches(await getSessions(limit)),
    { initialValue: [] as AgentSession[] },
  );
}

export function createSearchResource(query: () => string, limit = 80) {
  return createResource(
    query,
    async (q): Promise<SearchResponse> =>
      q.trim()
        ? search(q, limit)
        : { query: "", local: [], elastic: [], elasticHealth: { enabled: false, available: false } },
  );
}
