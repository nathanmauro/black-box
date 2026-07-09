import { useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import StreamRow from "../components/events/StreamRow";
import { getEventFeed, searchValues, type EventFeedItem } from "../lib/api";
import { FACET_FIELDS, parseQuery, setFacet, type FacetField } from "../lib/query";
import { useLiveStore } from "../lib/sse";
import { sourceFilter } from "../lib/stores";

const FEED_LIMIT = 100;
const MAX_ROWS = 500;

const VALUE_FIELD: Record<FacetField["key"], string> = {
  source: "source",
  kind: "event_type",
  tool: "tool_name",
  project: "cwd",
};

const QUICK_VALUES: Record<FacetField["key"], string[]> = {
  source: ["claude", "codex", "cursor", "raycast", "cockpit", "cli", "manual"],
  kind: ["Decision", "Handoff", "Observation", "UserPromptSubmit", "PostToolUse"],
  tool: [],
  project: [],
};

export default function StreamPage() {
  let inputRef: HTMLInputElement | undefined;
  let inputWrapRef: HTMLDivElement | undefined;
  let feedRef: HTMLDivElement | undefined;
  let liveTimer: ReturnType<typeof setTimeout> | undefined;
  let loadToken = 0;

  const live = useLiveStore();
  const [params, setParams] = useSearchParams<{ q?: string }>();
  const [draft, setDraft] = createSignal(params.q ?? "");
  const [meaningfulOnly, setMeaningfulOnly] = createSignal(true);
  const [items, setItems] = createSignal<EventFeedItem[]>([]);
  const [pendingItems, setPendingItems] = createSignal<EventFeedItem[]>([]);
  const [nextBefore, setNextBefore] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(false);
  const [loadingMore, setLoadingMore] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  const [newCount, setNewCount] = createSignal(0);
  const [expandedId, setExpandedId] = createSignal<string | null>(null);
  const [suggestionsOpen, setSuggestionsOpen] = createSignal(false);

  const submitted = () => params.q ?? "";
  const parsed = createMemo(() => parseQuery(submitted()));
  const filteredItems = createMemo(() => sourceFilter.matches(items()));
  const newestObservedAt = createMemo(() => pendingItems()[0]?.observedAt ?? items()[0]?.observedAt);
  const canLoadMore = createMemo(() => Boolean(nextBefore()) && items().length < MAX_ROWS);

  createEffect(() => setDraft(params.q ?? ""));

  createEffect(() => {
    const q = submitted();
    const meaningful = meaningfulOnly();
    const token = ++loadToken;
    setLoading(true);
    setError(null);
    getEventFeed({ limit: FEED_LIMIT, q, meaningful })
      .then((response) => {
        if (token !== loadToken) return;
        setItems(response.items.slice(0, MAX_ROWS));
        setPendingItems([]);
        setNewCount(0);
        setNextBefore(response.nextBefore ?? null);
        setExpandedId(null);
      })
      .catch((cause) => {
        if (token !== loadToken) return;
        setError(cause instanceof Error ? cause.message : "Unable to load activity stream.");
      })
      .finally(() => {
        if (token === loadToken) setLoading(false);
      });
  });

  const editing = createMemo(() => {
    const tokens = draft().split(/\s+/);
    const last = tokens[tokens.length - 1] ?? "";
    const sep = last.indexOf(":");
    if (sep <= 0) return null;
    const raw = last.slice(0, sep).toLowerCase();
    const field = FACET_FIELDS.find((f) => f.key === raw || (raw === "agent" && f.key === "source"));
    if (!field) return null;
    return { key: field.key, prefix: last.slice(sep + 1) };
  });
  const [suggestions] = createSignalResource(editing, async (edit) =>
    edit ? searchValues(VALUE_FIELD[edit.key], edit.prefix, 8).catch(() => []) : [],
  );
  const showSuggestions = () => suggestionsOpen() && editing() !== null && (suggestions()?.length ?? 0) > 0;

  createEffect(() => {
    if (editing() === null) setSuggestionsOpen(false);
  });

  createEffect((previousLiveCount = 0) => {
    const liveCount = live.events().length;
    if (!liveCount) return liveCount;
    if (!newestObservedAt()) return previousLiveCount;
    if (liveCount === previousLiveCount) return liveCount;
    if (liveTimer) clearTimeout(liveTimer);
    liveTimer = setTimeout(() => {
      void refetchHead(newestObservedAt());
    }, 500);
    return liveCount;
  });

  function run(next: string) {
    setParams({ q: next.trim() || undefined });
  }

  function submit(event: SubmitEvent) {
    event.preventDefault();
    run(draft());
  }

  function applyFacet(key: FacetField["key"], value: string | null, mode: "include" | "exclude" = "include") {
    run(setFacet(submitted(), key, value, mode));
  }

  function dismissSuggestions() {
    setSuggestionsOpen(false);
  }

  function pickSuggestion(value: string) {
    const edit = editing();
    if (!edit) return;
    const tokens = draft().split(/\s+/);
    tokens[tokens.length - 1] = `${edit.key}:${/\s/.test(value) ? `"${value}"` : value}`;
    setDraft(tokens.join(" ") + " ");
    dismissSuggestions();
    inputRef?.focus();
  }

  function handleDocumentPointerDown(event: PointerEvent) {
    if (!inputWrapRef?.contains(event.target as Node)) dismissSuggestions();
  }

  async function loadMore() {
    const before = nextBefore();
    if (!before || loadingMore() || items().length >= MAX_ROWS) return;
    setLoadingMore(true);
    setError(null);
    try {
      const response = await getEventFeed({ limit: FEED_LIMIT, q: submitted(), meaningful: meaningfulOnly(), before });
      setItems((current) => dedupe([...current, ...response.items]).slice(0, MAX_ROWS));
      setNextBefore(items().length >= MAX_ROWS ? null : response.nextBefore ?? null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to load more events.");
    } finally {
      setLoadingMore(false);
    }
  }

  async function refetchHead(since: string | undefined) {
    if (!since) return;
    try {
      const response = await getEventFeed({ limit: FEED_LIMIT, q: submitted(), meaningful: meaningfulOnly(), since });
      const existing = new Set([...items(), ...pendingItems()].map((item) => item.id));
      const fresh = response.items.filter((item) => !existing.has(item.id));
      if (!fresh.length) return;
      if (nearTop()) {
        setItems((current) => dedupe([...fresh, ...current]).slice(0, MAX_ROWS));
      } else {
        setPendingItems((current) => {
          const merged = dedupe([...fresh, ...current]).slice(0, MAX_ROWS);
          setNewCount(merged.length);
          return merged;
        });
      }
    } catch {
      // Live refetch is opportunistic; the normal feed error state remains tied to explicit loads.
    }
  }

  function nearTop() {
    return !feedRef || feedRef.scrollTop < 80;
  }

  function showNewItems() {
    const pending = pendingItems();
    if (!pending.length) return;
    setItems((current) => dedupe([...pending, ...current]).slice(0, MAX_ROWS));
    setPendingItems([]);
    setNewCount(0);
    feedRef?.scrollTo({ top: 0 });
  }

  document.addEventListener("pointerdown", handleDocumentPointerDown);
  onCleanup(() => {
    document.removeEventListener("pointerdown", handleDocumentPointerDown);
    if (liveTimer) clearTimeout(liveTimer);
  });

  return (
    <section class="page page--stream">
      <form class="stream-filter-bar" onSubmit={submit} autocomplete="off">
        <div ref={inputWrapRef} class="search-input-wrap">
          <input
            ref={inputRef}
            class="search-input"
            value={draft()}
            onInput={(event) => {
              setDraft(event.currentTarget.value);
              setSuggestionsOpen(true);
            }}
            onKeyDown={(event) => {
              if (event.key === "Escape") dismissSuggestions();
            }}
            placeholder="source:codex kind:Decision recall bug"
            aria-label="Stream query"
          />
          <button type="submit">Filter</button>
          <Show when={showSuggestions()}>
            <ul class="suggest-popover" role="listbox">
              <For each={suggestions()}>
                {(value) => (
                  <li>
                    <button type="button" onClick={() => pickSuggestion(value)}>
                      {value}
                    </button>
                  </li>
                )}
              </For>
            </ul>
          </Show>
        </div>
        <div class="facet-rail">
          <For each={FACET_FIELDS}>
            {(field) => (
              <div class="facet-group">
                <span class="facet-label">{field.label}</span>
                <Show when={parsed().facets[field.key]}>
                  {(value) => (
                    <button type="button" class="facet-chip facet-chip--active" onClick={() => applyFacet(field.key, null)}>
                      {value()} x
                    </button>
                  )}
                </Show>
                <Show when={parsed().excludeFacets[field.key]}>
                  {(value) => (
                    <button
                      type="button"
                      class="facet-chip facet-chip--active facet-chip--exclude"
                      aria-label={`${field.key} != ${value()}`}
                      onClick={() => applyFacet(field.key, null, "exclude")}
                    >
                      {field.key} != {value()} x
                    </button>
                  )}
                </Show>
                <Show when={!parsed().facets[field.key] && !parsed().excludeFacets[field.key]}>
                  <div class="facet-quick">
                    <For each={QUICK_VALUES[field.key]}>
                      {(value) => (
                        <button type="button" class="facet-chip" onClick={() => applyFacet(field.key, value)}>
                          {value}
                        </button>
                      )}
                    </For>
                    <Show when={QUICK_VALUES[field.key].length === 0}>
                      <span class="facet-hint">type {field.key}:...</span>
                    </Show>
                  </div>
                </Show>
              </div>
            )}
          </For>
          <label class="meaningful-toggle">
            <input type="checkbox" checked={meaningfulOnly()} onChange={(event) => setMeaningfulOnly(event.currentTarget.checked)} />
            meaningful events only
          </label>
        </div>
      </form>

      <Show when={error()}>
        {(message) => <p class="empty-state">{message()}</p>}
      </Show>

      <div ref={feedRef} class="stream-feed">
        <Show when={newCount()}>
          <button type="button" class="stream-new-pill" onClick={showNewItems}>
            {newCount()} new
          </button>
        </Show>
        <Show when={!loading()} fallback={<p class="empty-state">Loading activity...</p>}>
          <For each={filteredItems()}>
            {(item) => (
              <StreamRow
                item={item}
                expanded={expandedId() === item.id}
                onToggle={() => setExpandedId((current) => (current === item.id ? null : item.id))}
              />
            )}
          </For>
          <Show when={!filteredItems().length}>
            <p class="empty-state">No stream events match the current filters.</p>
          </Show>
        </Show>
      </div>

      <Show when={canLoadMore()}>
        <button type="button" class="stream-load-more" disabled={loadingMore()} onClick={loadMore}>
          {loadingMore() ? "Loading..." : "Load more"}
        </button>
      </Show>
    </section>
  );
}

function dedupe(items: EventFeedItem[]): EventFeedItem[] {
  const seen = new Set<string>();
  const result: EventFeedItem[] = [];
  for (const item of items) {
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    result.push(item);
  }
  return result;
}

function createSignalResource<TSource, TResult>(
  source: () => TSource,
  fetcher: (source: TSource) => Promise<TResult>,
): [() => TResult | undefined] {
  const [value, setValue] = createSignal<TResult>();
  let token = 0;
  createEffect(() => {
    const currentSource = source();
    const currentToken = ++token;
    fetcher(currentSource).then((next) => {
      if (currentToken === token) setValue(() => next);
    });
  });
  return [value];
}
