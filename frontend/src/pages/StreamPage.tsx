import { useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createSignal, For, Match, onCleanup, Show, Switch } from "solid-js";
import RowSessionActions from "../components/events/RowSessionActions";
import RunHeader from "../components/events/RunHeader";
import StreamFold from "../components/events/StreamFold";
import StreamRow from "../components/events/StreamRow";
import { getEventFeed, getSessions, searchValues, type EventFeedItem, type ProjectSummary } from "../lib/api";
import { primaryProjectScope, projectShortName } from "../lib/projects";
import {
  describeTimeSpec,
  FACET_FIELDS,
  parseQuery,
  removeFacetValue,
  resolvesToPastInstant,
  serializeQuery,
  setFacet,
  type FacetField,
  type FacetKey,
  type FacetMode,
  type QueryState,
} from "../lib/query";
import { useLiveStore } from "../lib/sse";
import { loadStreamDensity, saveStreamDensity, type StreamDensity } from "../lib/streamDensity";
import { reconcileSegments, segmentStream, type FoldRow, type Segment } from "../lib/streamGroups";

const FEED_LIMIT = 100;
const MAX_ROWS = 500;

const VALUE_FIELD: Record<FacetField["key"], string> = {
  source: "source",
  kind: "event_type",
  tool: "tool_name",
  project: "cwd",
};

// No longer a standing rail (spec §4.6): these surface in the suggest-popover when a facet
// token is typed with an empty prefix. tool:/project:/session: suggest live values instead.
const QUICK_VALUES: Record<FacetField["key"], string[]> = {
  source: ["claude", "codex", "cursor", "raycast", "cockpit", "cli", "manual"],
  kind: ["Decision", "Handoff", "Observation", "UserPromptSubmit", "PostToolUse"],
  tool: [],
  project: [],
};

type EditingToken = { key: FacetField["key"] | "session"; prefix: string };

type StreamPageProps = {
  project?: ProjectSummary | null;
  projectScopePending?: boolean;
  onClearProject?: () => void;
};

export default function StreamPage(props: StreamPageProps = {}) {
  let inputRef: HTMLInputElement | undefined;
  let inputWrapRef: HTMLDivElement | undefined;
  let feedRef: HTMLDivElement | undefined;
  let liveTimer: ReturnType<typeof setTimeout> | undefined;
  let loadToken = 0;

  const live = useLiveStore();
  const [params, setParams] = useSearchParams<{ q?: string; project?: string }>();
  const [draft, setDraft] = createSignal(params.q ?? "");
  const [items, setItems] = createSignal<EventFeedItem[]>([]);
  const [pendingItems, setPendingItems] = createSignal<EventFeedItem[]>([]);
  const [nextBefore, setNextBefore] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(false);
  const [loadingMore, setLoadingMore] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  const [newCount, setNewCount] = createSignal(0);
  const [density, setDensity] = createSignal<StreamDensity>(loadStreamDensity());
  // Exceptions to the density mode, not a list of expanded rows: absence means "follow the mode",
  // so SSE rows arriving in expanded mode render expanded with zero bookkeeping (spec §4.5).
  const [overrides, setOverrides] = createSignal<Set<string>>(new Set());
  // Fold keys the user opened in place (spec §4.4) — cleared on reload, same lifecycle as the
  // density overrides. Keys anchor on the fold's oldest member id, so SSE prepends extending a
  // streak leave an open fold open; pagination changing the key refolds it (accepted residual).
  const [unfolds, setUnfolds] = createSignal<Set<string>>(new Set());

  const isExpanded = (id: string) => (density() === "expanded") !== overrides().has(id);

  function toggleRow(id: string) {
    setOverrides((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    // Toggling a chatter row can restructure fold streaks around it, rebuilding its run's DOM
    // and dropping focus; put focus back on the row the user just activated.
    const active = document.activeElement;
    if (!active || active === document.body || !active.isConnected) {
      feedRef?.querySelector<HTMLButtonElement>(`button[data-event-id="${CSS.escape(id)}"]`)?.focus();
    }
  }

  function switchDensity(next: StreamDensity) {
    if (next === density()) return;
    setDensity(next);
    saveStreamDensity(next);
    setOverrides(new Set<string>());
    setUnfolds(new Set<string>());
  }

  // Unfold in place and move focus to the first revealed row (spec §4.4/§4.6). Solid renders
  // synchronously on the signal write, so the revealed row exists by the querySelector.
  function unfold(fold: FoldRow) {
    const firstId = fold.items[0]?.id;
    setUnfolds((current) => {
      const next = new Set(current);
      next.add(fold.key);
      return next;
    });
    if (firstId) {
      feedRef?.querySelector<HTMLButtonElement>(`button[data-event-id="${CSS.escape(firstId)}"]`)?.focus();
    }
  }
  const [suggestionsOpen, setSuggestionsOpen] = createSignal(false);
  // "Options" disclosure (spec §4.6): density + meaningful live behind one quiet control.
  const [optionsOpen, setOptionsOpen] = createSignal(false);

  const submitted = () => params.q ?? "";
  const visibleSubmitted = createMemo(() => (props.project ? setFacet(submitted(), "project", null) : submitted()));
  const apiQuery = createMemo(() =>
    props.project ? appendProjectGroupScope(visibleSubmitted(), primaryProjectScope(props.project).canonicalKey) : submitted(),
  );
  const parsed = createMemo(() => parseQuery(visibleSubmitted()));
  const newestObservedAt = createMemo(() => pendingItems()[0]?.observedAt ?? items()[0]?.observedAt);
  const canLoadMore = createMemo(() => Boolean(nextBefore()) && items().length < MAX_ROWS);
  // "Live paused" when until: bounds the query in the past (client-side approximation, see
  // resolvesToPastInstant): new events cannot match, so the N-new pill and the live head-refetch
  // merge are suppressed while it is active — live behavior must never contradict the query.
  const livePaused = createMemo(() => {
    const until = parsed().until;
    return until !== null && resolvesToPastInstant(until);
  });
  // The result-header scope phrase (spec §4.6): the standing "meaningful" word whenever the
  // default filter is active (its visible representation under P3), plus the parsed time tokens.
  // Counts are slice 8 — until then the line is scope phrase only.
  const scopePhrases = createMemo(() => {
    const phrases: string[] = [];
    if (!parsed().isAll) phrases.push("meaningful");
    const since = parsed().since;
    const until = parsed().until;
    if (since) phrases.push(quietPhrase(describeTimeSpec(since, "since")));
    if (until) phrases.push(quietPhrase(describeTimeSpec(until, "until")));
    return phrases;
  });
  // "Visible filter" for the filter-honest gap labels (spec §4.2): anything that narrows the
  // feed and renders as a chip. The hidden project scope and the meaningful default don't count
  // (they are the two named standing defaults, P3); is:all widens, so it doesn't either.
  const hasVisibleFilter = createMemo(() => {
    const state = parsed();
    return (
      Object.values(state.facets).some((values) => values.length > 0) ||
      Object.values(state.excludeFacets).some((values) => values.length > 0) ||
      state.session !== null ||
      state.since !== null ||
      state.until !== null ||
      state.freeTerms.length > 0
    );
  });
  // The chip rail renders only when something earns it (spec §4.6: chrome is earned like
  // color) — active tokens, the pinned project, or the is:all widening.
  const hasActiveChips = createMemo(() => {
    const state = parsed();
    return (
      Boolean(props.project) ||
      Object.values(state.facets).some((values) => values.length > 0) ||
      Object.values(state.excludeFacets).some((values) => values.length > 0) ||
      state.session !== null ||
      state.since !== null ||
      state.until !== null ||
      state.isAll
    );
  });
  const facetHasChips = (key: FacetField["key"]) =>
    Boolean(parsed().facets[key]?.length || parsed().excludeFacets[key]?.length);
  // Folds apply only in collapsed mode ("Expanded" means expanded, spec §4.4); a per-row
  // override in collapsed mode means the row is expanded, which breaks fold streaks around it.
  // Reconciliation keeps object identity for untouched runs so a recompute (toggle, unfold,
  // density switch) only rebuilds the DOM of runs that structurally changed.
  let lastSegments: Segment[] = [];
  const segments = createMemo(() => {
    const next = segmentStream(items(), {
      hasVisibleFilter: hasVisibleFilter(),
      folds:
        density() === "collapsed"
          ? { isRowExpanded: (id) => overrides().has(id), unfolded: unfolds() }
          : undefined,
    });
    lastSegments = reconcileSegments(lastSegments, next);
    return lastSegments;
  });

  createEffect(() => setDraft(visibleSubmitted()));

  createEffect(() => {
    const visible = visibleSubmitted();
    if (props.project && visible !== submitted()) {
      setParams({ q: visible || undefined });
    }
  });

  createEffect(() => {
    if (props.projectScopePending) {
      loadToken += 1;
      setLoading(true);
      setError(null);
      setItems([]);
      setPendingItems([]);
      setNewCount(0);
      setNextBefore(null);
      setLoadingMore(false);
      setOverrides(new Set<string>());
      setUnfolds(new Set<string>());
      return;
    }
    const q = apiQuery();
    const token = ++loadToken;
    setLoading(true);
    setLoadingMore(false);
    setItems([]);
    setPendingItems([]);
    setNewCount(0);
    setNextBefore(null);
    setError(null);
    setOverrides(new Set<string>());
    setUnfolds(new Set<string>());
    // meaningful=true always rides the wire; opting out is expressed as is:all in q, which the
    // backend gives precedence (D16) — deep links and reloads reproduce the state from q alone.
    getEventFeed({ limit: FEED_LIMIT, q, meaningful: true })
      .then((response) => {
        if (token !== loadToken) return;
        setItems(response.items.slice(0, MAX_ROWS));
        setPendingItems([]);
        setNewCount(0);
        setNextBefore(response.nextBefore ?? null);
        setOverrides(new Set<string>());
        setUnfolds(new Set<string>());
      })
      .catch((cause) => {
        if (token !== loadToken) return;
        setError(cause instanceof Error ? cause.message : "Unable to load activity stream.");
      })
      .finally(() => {
        if (token === loadToken) setLoading(false);
      });
  });

  const editing = createMemo<EditingToken | null>(() => {
    const tokens = draft().split(/\s+/);
    const last = tokens[tokens.length - 1] ?? "";
    const sep = last.indexOf(":");
    if (sep <= 0) return null;
    const raw = last.slice(0, sep).toLowerCase();
    if (raw === "session") return { key: "session", prefix: last.slice(sep + 1) };
    const field = FACET_FIELDS.find((f) => f.key === raw || (raw === "agent" && f.key === "source"));
    if (!field) return null;
    return { key: field.key, prefix: last.slice(sep + 1) };
  });
  const [suggestions] = createSignalResource(editing, async (edit) => {
    if (!edit) return [];
    if (edit.key === "session") return sessionSuggestions(edit.prefix);
    // Empty prefix on an enumerable facet surfaces the static quick values (spec §4.6);
    // a typed prefix — and tool:/project: always — goes to the live value index.
    if (!edit.prefix && QUICK_VALUES[edit.key].length) return QUICK_VALUES[edit.key];
    return searchValues(VALUE_FIELD[edit.key], edit.prefix, 8).catch(() => []);
  });
  const showSuggestions = () => suggestionsOpen() && editing() !== null && (suggestions()?.length ?? 0) > 0;
  // Keyboard highlight for the popover: -1 means "typing, nothing highlighted".
  const [activeSuggestion, setActiveSuggestion] = createSignal(-1);

  createEffect(() => {
    if (editing() === null) setSuggestionsOpen(false);
  });
  createEffect(() => {
    suggestions();
    editing();
    setActiveSuggestion(-1);
  });

  createEffect((previousLiveCount = 0) => {
    const liveCount = live.events().length;
    if (livePaused()) return liveCount;
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

  function removeFacetChip(key: FacetKey, value: string, mode: FacetMode = "include") {
    run(removeFacetValue(visibleSubmitted(), key, value, mode));
  }

  function patchQuery(patch: (state: QueryState) => void) {
    const state = parseQuery(visibleSubmitted());
    patch(state);
    run(serializeQuery(state));
  }

  function dismissSuggestions() {
    setSuggestionsOpen(false);
    setActiveSuggestion(-1);
  }

  function handleInputKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      dismissSuggestions();
      return;
    }
    const list = suggestions() ?? [];
    if (editing() === null || !list.length) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setSuggestionsOpen(true);
      setActiveSuggestion((index) => (index + 1) % list.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setSuggestionsOpen(true);
      setActiveSuggestion((index) => (index <= 0 ? list.length - 1 : index - 1));
    } else if (event.key === "Enter" && showSuggestions() && activeSuggestion() >= 0) {
      // Enter with a highlight accepts the suggestion; without one it submits the form.
      event.preventDefault();
      pickSuggestion(list[activeSuggestion()]);
    }
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
    if (props.projectScopePending || loading() || !before || loadingMore() || items().length >= MAX_ROWS) return;
    const token = loadToken;
    setLoadingMore(true);
    setError(null);
    try {
      const response = await getEventFeed({ limit: FEED_LIMIT, q: apiQuery(), meaningful: true, before });
      if (!isCurrentStreamRequest(token)) return;
      setItems((current) => dedupe([...current, ...response.items]).slice(0, MAX_ROWS));
      setNextBefore(items().length >= MAX_ROWS ? null : response.nextBefore ?? null);
    } catch (cause) {
      if (!isCurrentStreamRequest(token)) return;
      setError(cause instanceof Error ? cause.message : "Unable to load more events.");
    } finally {
      if (isCurrentStreamRequest(token)) setLoadingMore(false);
    }
  }

  async function refetchHead(since: string | undefined) {
    if (props.projectScopePending || !since || livePaused()) return;
    const token = loadToken;
    try {
      const response = await getEventFeed({ limit: FEED_LIMIT, q: apiQuery(), meaningful: true, since });
      if (!isCurrentStreamRequest(token)) return;
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

  function isCurrentStreamRequest(token: number) {
    return token === loadToken && !props.projectScopePending;
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
        <div class="stream-input-line">
          <div ref={inputWrapRef} class="search-input-wrap">
            <input
              ref={inputRef}
              class="search-input"
              value={draft()}
              onInput={(event) => {
                setDraft(event.currentTarget.value);
                setSuggestionsOpen(true);
              }}
              onKeyDown={handleInputKeyDown}
              placeholder="source:codex kind:Decision recall bug"
              aria-label="Stream query"
              role="combobox"
              aria-expanded={showSuggestions()}
              aria-controls="stream-suggest-list"
              aria-autocomplete="list"
              aria-activedescendant={
                showSuggestions() && activeSuggestion() >= 0 ? `stream-suggest-option-${activeSuggestion()}` : undefined
              }
            />
            <button type="submit">Filter</button>
            <Show when={showSuggestions()}>
              <ul class="suggest-popover" role="listbox" id="stream-suggest-list" aria-label="Query suggestions">
                <For each={suggestions()}>
                  {(value, index) => (
                    <li
                      role="option"
                      id={`stream-suggest-option-${index()}`}
                      aria-selected={index() === activeSuggestion()}
                      classList={{ "suggest-option": true, "suggest-option--active": index() === activeSuggestion() }}
                      onClick={() => pickSuggestion(value)}
                    >
                      {value}
                    </li>
                  )}
                </For>
              </ul>
            </Show>
          </div>
          <div class="stream-options">
            <button
              type="button"
              class="stream-options-trigger"
              aria-expanded={optionsOpen()}
              aria-controls="stream-options-panel"
              onClick={() => setOptionsOpen((open) => !open)}
            >
              Options
            </button>
            <Show when={optionsOpen()}>
              <div id="stream-options-panel" class="stream-options-panel">
                <label class="meaningful-toggle">
                  <input
                    type="checkbox"
                    checked={!parsed().isAll}
                    onChange={(event) => {
                      const meaningful = event.currentTarget.checked;
                      patchQuery((state) => (state.isAll = !meaningful));
                    }}
                  />
                  meaningful events only
                </label>
                <div class="density-toggle" role="group" aria-label="Stream density">
                  <button
                    type="button"
                    classList={{ active: density() === "collapsed" }}
                    onClick={() => switchDensity("collapsed")}
                  >
                    Collapsed
                  </button>
                  <button
                    type="button"
                    classList={{ active: density() === "expanded" }}
                    onClick={() => switchDensity("expanded")}
                  >
                    Expanded
                  </button>
                </div>
              </div>
            </Show>
          </div>
        </div>
        <Show when={hasActiveChips()}>
          <div class="facet-rail">
          <Show when={props.project}>
            {(project) => (
              <button
                type="button"
                class="facet-chip facet-chip--active facet-chip--pinned"
                title={`Pinned project scope: ${primaryProjectScope(project()).canonicalKey}`}
                aria-label={`Pinned project ${projectShortName(project())} — clear project scope`}
                onClick={() => props.onClearProject?.()}
              >
                <span class="facet-chip-pin" aria-hidden="true">
                  📌
                </span>{" "}
                Project: {projectShortName(project())} ✕
              </button>
            )}
          </Show>
          <For each={FACET_FIELDS}>
            {(field) => (
              <Show when={facetHasChips(field.key)}>
                <div class="facet-group">
                  <span class="facet-label">{field.label}</span>
                  <For each={parsed().facets[field.key] ?? []}>
                    {(value) => (
                      <button type="button" class="facet-chip facet-chip--active" onClick={() => removeFacetChip(field.key, value)}>
                        {value} x
                      </button>
                    )}
                  </For>
                  <For each={parsed().excludeFacets[field.key] ?? []}>
                    {(value) => (
                      <button
                        type="button"
                        class="facet-chip facet-chip--active facet-chip--exclude"
                        aria-label={`${field.key} != ${value}`}
                        onClick={() => removeFacetChip(field.key, value, "exclude")}
                      >
                        {field.key} != {value} x
                      </button>
                    )}
                  </For>
                </div>
              </Show>
            )}
          </For>
          <For each={parsed().facets.project_exact ?? []}>
            {(value) => (
              <button
                type="button"
                class="facet-chip facet-chip--active"
                onClick={() => removeFacetChip("project_exact", value)}
              >
                project_exact: {value} x
              </button>
            )}
          </For>
          <For each={parsed().excludeFacets.project_exact ?? []}>
            {(value) => (
              <button
                type="button"
                class="facet-chip facet-chip--active facet-chip--exclude"
                aria-label={`project_exact != ${value}`}
                onClick={() => removeFacetChip("project_exact", value, "exclude")}
              >
                project_exact != {value} x
              </button>
            )}
          </For>
          <Show when={parsed().session}>
            {(sessionRef) => (
              <button
                type="button"
                class="facet-chip facet-chip--active"
                title={`session:${sessionRef()}`}
                onClick={() => patchQuery((state) => (state.session = null))}
              >
                session: {shortSessionRef(sessionRef())} x
              </button>
            )}
          </Show>
          <Show when={parsed().since}>
            {(spec) => (
              <button
                type="button"
                class="facet-chip facet-chip--active facet-chip--time"
                onClick={() => patchQuery((state) => (state.since = null))}
              >
                <span class="facet-chip-clock" aria-hidden="true">
                  🕒
                </span>{" "}
                {describeTimeSpec(spec(), "since")} ✕
              </button>
            )}
          </Show>
          <Show when={parsed().until}>
            {(spec) => (
              <button
                type="button"
                class="facet-chip facet-chip--active facet-chip--time"
                onClick={() => patchQuery((state) => (state.until = null))}
              >
                <span class="facet-chip-clock" aria-hidden="true">
                  🕒
                </span>{" "}
                {describeTimeSpec(spec(), "until")} ✕
              </button>
            )}
          </Show>
          <Show when={parsed().isAll}>
            <button
              type="button"
              class="facet-chip facet-chip--active"
              aria-label="remove is:all"
              onClick={() => patchQuery((state) => (state.isAll = false))}
            >
              all events x
            </button>
          </Show>
          </div>
        </Show>
      </form>

      <Show when={error()}>
        {(message) => <p class="empty-state">{message()}</p>}
      </Show>

      <Show when={scopePhrases().length > 0 || livePaused()}>
        <div class="stream-result-header">
          <Show when={scopePhrases().length}>
            <span class="stream-result-scope">{scopePhrases().join(" · ")}</span>
          </Show>
          <Show when={livePaused()}>
            <span class="stream-live-paused">live paused — historical scope</span>
          </Show>
        </div>
      </Show>

      {/* Persistent live region (spec §4.6): the N-new pill mounts conditionally and cannot be
          the live region — this one always exists, announcing count changes politely. */}
      <div class="visually-hidden" aria-live="polite">
        {newCount() > 0 ? `${newCount()} new ${newCount() === 1 ? "event" : "events"}` : ""}
      </div>

      <div ref={feedRef} class="stream-feed" role="feed" aria-busy={loadingMore()} aria-label="Activity stream">
        <Show when={newCount() && !livePaused()}>
          <button type="button" class="stream-new-pill" onClick={showNewItems}>
            {newCount()} new
          </button>
        </Show>
        <Show when={!loading()} fallback={<p class="empty-state">Loading activity...</p>}>
          <For each={segments()}>
            {(segment) => (
              <Switch>
                <Match when={segment.type === "day" ? segment : null}>
                  {(day) => (
                    <div class="stream-daybreak">
                      <span>{day().label}</span>
                      <Show when={day().gapLabel}>{(gap) => <span class="stream-daybreak-gap">· {gap()}</span>}</Show>
                    </div>
                  )}
                </Match>
                <Match when={segment.type === "run" ? segment : null}>
                  {/* The run wrapper is a generic <div>, not a <section>: a labeled section is
                      a region landmark that would interpose between the feed and its owned
                      articles (§4.6). The run header is its own article, labeled by session. */}
                  {(run) => (
                    <div class="stream-run">
                      <RunHeader
                        run={run()}
                        sticky={run().eventCount >= 3}
                        sessionHref={runSessionHref(run().sessionId, props.project)}
                        actions={
                          <RowSessionActions
                            sessionId={run().sessionId}
                            streamLink={sessionStreamLink(visibleSubmitted(), run().sessionId, props.project?.projectKey ?? "")}
                            onFilterToSession={(sessionId) => patchQuery((state) => (state.session = sessionId))}
                          />
                        }
                      />
                      <div class="stream-run-body">
                        <For each={run().rows}>
                          {(row) =>
                            row.type === "event" ? (
                              <StreamRow
                                item={row.item}
                                expanded={isExpanded(row.item.id)}
                                textExpanded={density() === "expanded"}
                                sessionHref={sessionHref(row.item, props.project)}
                                onToggle={() => toggleRow(row.item.id)}
                                cwdException={cwdDiffers(row.item.cwd, run().cwd)}
                              />
                            ) : (
                              <StreamFold fold={row} onUnfold={() => unfold(row)} />
                            )
                          }
                        </For>
                      </div>
                    </div>
                  )}
                </Match>
              </Switch>
            )}
          </For>
          <Show when={!items().length}>
            <p class="empty-state">No stream events match the current filters.</p>
          </Show>
        </Show>
      </div>

      <Show when={canLoadMore()}>
        <button type="button" class="stream-load-more" disabled={loadingMore()} onClick={loadMore}>
          {loadingMore() ? "Loading..." : "Load more"}
        </button>
      </Show>
      {/* Cap honesty (spec §4.5): at MAX_ROWS the silently-vanishing Load more becomes an
          honest terminal row instead. */}
      <Show when={items().length >= MAX_ROWS}>
        <p class="stream-endcap">{MAX_ROWS} of many shown — refine the filter to go deeper.</p>
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

function appendProjectGroupScope(query: string, canonicalKey: string): string {
  return [query.trim(), `project_group:${quoteHiddenFacet(canonicalKey)}`].filter(Boolean).join(" ");
}

// Commas trigger quoting too: an unquoted comma in a facet value now splits into an IN-list, so a
// canonicalKey containing one would fan out into bogus project groups.
function quoteHiddenFacet(value: string): string {
  return /[\s",]/u.test(value) ? `"${value.replace(/"/g, '\\"')}"` : value;
}

// Lowercases only the leading word of a describeTimeSpec phrase ("Past 2 hours" → "past 2 hours")
// so month names keep their capitals in the quiet result-header line.
function quietPhrase(phrase: string): string {
  return phrase.charAt(0).toLowerCase() + phrase.slice(1);
}

// The copy-link URL carries the VISIBLE q plus the session token — never the hidden project_group
// injection, which stays an apiQuery concern (spec §7). `project=` is always set explicitly:
// the pinned key when a project is selected, the empty-string explicit-global sentinel when not —
// otherwise the opener's remembered-project effect would rescope the link and the session AND
// project_group conjunction could empty the feed. Deep links reproduce what the sender saw.
function sessionStreamLink(visibleQuery: string, sessionId: string, projectKey: string): string {
  const state = parseQuery(visibleQuery);
  state.session = sessionId;
  const search = new URLSearchParams({ q: serializeQuery(state), project: projectKey });
  return `${window.location.origin}/stream?${search.toString()}`;
}

// session: suggestions from the recent-sessions listing — the cheapest live-value source
// already in api.ts; counted top-N value suggestions arrive with slice 8 (§6.4).
async function sessionSuggestions(prefix: string): Promise<string[]> {
  try {
    const sessions = await getSessions(50);
    const needle = prefix.toLowerCase();
    const values: string[] = [];
    for (const session of sessions) {
      const value = session.clientSessionId || session.id;
      if (!value || values.includes(value)) continue;
      const haystacks = [value, session.id, session.title ?? ""];
      if (needle && !haystacks.some((candidate) => candidate.toLowerCase().includes(needle))) continue;
      values.push(value);
      if (values.length >= 8) break;
    }
    return values;
  } catch {
    return [];
  }
}

function shortSessionRef(value: string): string {
  return value.length > 14 ? `${value.slice(0, 12)}…` : value;
}

// Per-event position link ("Open at this event →", spec §4.3/§9): view=browse&session=&event=.
function sessionHref(item: EventFeedItem, project: ProjectSummary | null | undefined): string {
  const query = new URLSearchParams({
    view: "browse",
    session: item.sessionId,
    event: item.id,
  });
  if (project) query.set("project", project.projectKey);
  return `/?${query.toString()}`;
}

// Session-level link for the run header ("View session →", spec §4.1): no event position.
function runSessionHref(sessionId: string, project: ProjectSummary | null | undefined): string {
  const query = new URLSearchParams({ view: "browse", session: sessionId });
  if (project) query.set("project", project.projectKey);
  return `/?${query.toString()}`;
}

function cwdDiffers(rowCwd: string | null | undefined, runCwd: string | null | undefined): boolean {
  return (rowCwd ?? null) !== (runCwd ?? null);
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
