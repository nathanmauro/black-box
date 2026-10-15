import { A, useSearchParams } from "@solidjs/router";
import {
  batch,
  createEffect,
  createMemo,
  createSignal,
  For,
  Match,
  onCleanup,
  on,
  Show,
  Switch,
  untrack,
} from "solid-js";
import RowSessionActions from "../components/events/RowSessionActions";
import RunHeader from "../components/events/RunHeader";
import StreamFold from "../components/events/StreamFold";
import StreamRow from "../components/events/StreamRow";
import {
  getEventFacets,
  getEventFeed,
  getSessions,
  searchValues,
  type AgentSession,
  type EventFacetCounts,
  type EventFacetFields,
  type EventFeedItem,
  type FacetValueCount,
  type ProjectSummary,
} from "../lib/api";
import { truncatePath } from "../lib/format";
import { humanOnly, withHumanText } from "../lib/humanOnly";
import { findProjectByIdentifier, primaryProjectScope, projectShortName } from "../lib/projects";
import { listSavedViews, removeSavedView, saveSavedView } from "../lib/savedViews";
import {
  appendQueryToken,
  describeTimeSpec,
  FACET_FIELDS,
  parseQuery,
  projectIncludeSpans,
  removeFacetValue,
  resolvesToPastInstant,
  serializeQuery,
  setFacet,
  stripProjectIncludes,
  type FacetField,
  type FacetKey,
  type FacetMode,
  type QueryState,
} from "../lib/query";
import {
  applyCompletion,
  completionAt,
  countsApplyToDraft,
  fieldOptions,
  needsRecordedValues,
  recordedValueField,
  tabAcceptsFirstOption,
  valueOptions,
  type CompletionOption,
} from "../lib/queryCompletion";
import { interpretQuery, meaningfulRecovery, type QueryNote } from "../lib/queryInterpretation";
import { useLiveStore } from "../lib/sse";
import { loadStreamDensity, saveStreamDensity, type StreamDensity } from "../lib/streamDensity";
import { reconcileSegments, segmentStream, type FoldRow, type Segment } from "../lib/streamGroups";

const FEED_LIMIT = 100;
const MAX_ROWS = 500;

const FACETS_DEBOUNCE_MS = 300;
// Value-index lookups wait for a typing pause and abort when the token changes again.
const VALUES_DEBOUNCE_MS = 150;
const RECORDED_VALUE_LIMIT = 20;
// Recent sessions for session: completion, fetched once per popover opening.
const SESSION_SUGGESTION_LIMIT = 100;
const SESSION_CHIP_TITLE_LIMIT = 40;

// Built-in view presets (spec §8, D13): each is just a q string in the locked grammar, doubling
// as documentation of it. An "Errors today" preset is NOT shippable — zero error-shaped
// event_types exist and failure-ness is payload-derived client-side (§4.3); a server-side error
// facet is a recorded follow-up, out of scope (§12).
const VIEW_PRESETS: Array<{ name: string; q: string }> = [
  { name: "Decisions this week", q: "kind:Decision last:7d" },
  { name: "Handoffs", q: "kind:Handoff last:7d" },
  { name: "Codex right now", q: "source:codex last:2h" },
  // Prompts are not meaningful-filter rows, so this view needs is:all to show anything.
  { name: "Prompts today", q: "kind:UserPromptSubmit last:24h is:all" },
];

// The expanded landmark cards that earn a "Trajectory →" link (spec §9, D14).
const TRAJECTORY_LINK_KINDS = new Set(["Decision", "Handoff", "Observation"]);

type StreamPanel = "views" | "options";

type RecordedRequest = { key: string; field: string; prefix: string };

type StreamPageProps = {
  project?: ProjectSummary | null;
  // The full project catalog already loaded by the host page (ProjectPicker's source) — used to
  // resolve a row's cwd to a projectKey for the "Trajectory →" link. Optional: without it the
  // link is simply omitted.
  projects?: ProjectSummary[];
  projectScopePending?: boolean;
  onClearProject?: () => void;
};

export default function StreamPage(props: StreamPageProps = {}) {
  let inputRef: HTMLInputElement | undefined;
  let inputWrapRef: HTMLDivElement | undefined;
  let feedRef: HTMLDivElement | undefined;
  let liveTimer: ReturnType<typeof setTimeout> | undefined;
  let loadToken = 0;
  let canonicalRefreshNeeded = false;

  const live = useLiveStore();
  const [snapshotRevision, setSnapshotRevision] = createSignal(0);
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
      feedRef
        ?.querySelector<HTMLButtonElement>(`button[data-event-id="${CSS.escape(id)}"]`)
        ?.focus();
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
      feedRef
        ?.querySelector<HTMLButtonElement>(`button[data-event-id="${CSS.escape(firstId)}"]`)
        ?.focus();
    }
  }
  // The popover opens on input and closes on accept, Escape, Tab, submit or an outside press.
  const [suggestionsOpen, setSuggestionsOpen] = createSignal(false);
  // ArrowDown/ArrowUp asked for the list, so field names show before a letter is typed.
  const [explicitOpen, setExplicitOpen] = createSignal(false);
  // The input's selectionStart (UTF-16), tracked on input, keyup, click, select and focus. It is
  // only read while the popover is open, which always follows one of those events.
  const [caret, setCaret] = createSignal(0);
  // "Options" (spec §4.6: density + meaningful) and "Views" (spec §8, D13: presets + saved views)
  // are two quiet disclosures sharing one slot: opening one closes the other, and Escape or a
  // pointer-down outside the open panel dismisses it.
  const [openPanel, setOpenPanel] = createSignal<StreamPanel | null>(null);
  const optionsOpen = () => openPanel() === "options";
  const viewsOpen = () => openPanel() === "views";
  let viewsRef: HTMLDivElement | undefined;
  let optionsRef: HTMLDivElement | undefined;

  function togglePanel(panel: StreamPanel) {
    setOpenPanel((current) => (current === panel ? null : panel));
  }

  // Escape returns focus to the trigger that owns the panel so keyboard users are not dropped
  // onto <body> when the panel's contents unmount.
  function closePanel(restoreFocus = false) {
    const panel = openPanel();
    if (!panel) return;
    setOpenPanel(null);
    if (restoreFocus) {
      const wrap = panel === "views" ? viewsRef : optionsRef;
      wrap?.querySelector<HTMLButtonElement>(".stream-options-trigger")?.focus();
    }
  }
  const [savedViews, setSavedViews] = createSignal(listSavedViews());
  const [saveViewName, setSaveViewName] = createSignal("");
  // On-demand counted browser (spec §6.5, D10): opened from the match count, never standing.
  const [browserOpen, setBrowserOpen] = createSignal(false);
  // null = unavailable (loading, aborted, error, or the server's backfill omission). The header
  // and browser render nothing rather than a number for a different q.
  const [facetCounts, setFacetCounts] = createSignal<EventFacetCounts | null>(null);
  let facetsTimer: ReturnType<typeof setTimeout> | undefined;
  let facetsAbort: AbortController | undefined;
  let facetsToken = 0;

  const submitted = () => params.q ?? "";
  // A pinned project's hidden scope replaces visible project:/cwd: includes. They are removed by
  // span, never by re-serializing, so the rest of the text stays as typed and the URL settles.
  const visibleSubmitted = createMemo(() =>
    props.project ? stripProjectIncludes(submitted()) : submitted(),
  );
  const apiQuery = createMemo(() =>
    props.project
      ? appendProjectGroupScope(visibleSubmitted(), primaryProjectScope(props.project).canonicalKey)
      : submitted(),
  );
  const parsed = createMemo(() => parseQuery(visibleSubmitted()));
  const newestObservedAt = createMemo(
    () => pendingItems()[0]?.observedAt ?? items()[0]?.observedAt,
  );
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
  // The match count renders beside these when available and is omitted otherwise.
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

  // The applied query replaced the text (back/forward, a fix, a chip, a view, the pinned project):
  // the caret moves to its end, where the input's own caret lands, and suggestions for the old
  // text close. An unchanged draft keeps its caret.
  createEffect(() => {
    const next = visibleSubmitted();
    if (untrack(draft) === next) return;
    batch(() => {
      setDraft(next);
      setCaret(next.length);
      dismissSuggestions();
    });
  });

  // The tokens the pinned-project strip removed, kept for a note while the query they were
  // stripped from stays applied under the same pinned project. Any later submission, or another
  // pinned project, retires the record, so the note never returns for a query that lost nothing.
  const [strippedProject, setStrippedProject] = createSignal<{
    query: string;
    projectKey: string;
    tokens: string[];
  } | null>(null);
  createEffect(() => {
    const raw = submitted();
    const visible = visibleSubmitted();
    const project = props.project;
    if (project && visible !== raw) {
      setStrippedProject({
        query: visible,
        projectKey: project.projectKey,
        tokens: projectIncludeSpans(raw).map((span) => span.text),
      });
      // Replace, never push: Back would land on the unnormalized URL and come straight forward.
      setParams({ q: visible || undefined }, { replace: true });
      return;
    }
    const record = untrack(strippedProject);
    if (record && (raw !== record.query || project?.projectKey !== record.projectKey)) {
      setStrippedProject(null);
    }
  });
  const strippedProjectTokens = createMemo(() => {
    const record = strippedProject();
    if (!record || record.query !== visibleSubmitted()) return [];
    return record.projectKey === props.project?.projectKey ? record.tokens : [];
  });

  createEffect(() => {
    snapshotRevision();
    if (liveTimer) clearTimeout(liveTimer);
    liveTimer = undefined;
    canonicalRefreshNeeded = false;
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
    const human = humanOnly();
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
    getEventFeed({ limit: FEED_LIMIT, q, meaningful: true, humanOnly: human || undefined })
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

  // Counts follow the query with a debounce and abort-on-change: the stale value clears the
  // moment q moves, the in-flight request is aborted, and only the newest response ever lands —
  // a count for a different q must never render (spec §4.6/§6.5).
  createEffect(() => {
    snapshotRevision();
    const pendingScope = props.projectScopePending;
    const q = apiQuery();
    const human = humanOnly();
    const token = ++facetsToken;
    setFacetCounts(null);
    facetsAbort?.abort();
    if (facetsTimer) clearTimeout(facetsTimer);
    if (pendingScope) return;
    facetsTimer = setTimeout(() => {
      const controller = new AbortController();
      facetsAbort = controller;
      getEventFacets({ q, meaningful: true, humanOnly: human || undefined }, controller.signal)
        .then((counts) => {
          if (token === facetsToken) setFacetCounts(counts);
        })
        .catch(() => {
          // Unavailable counts stay omitted (scope phrase only) — never an error state.
        });
    }, FACETS_DEBOUNCE_MS);
  });

  const countedFields = createMemo<EventFacetFields | null>(() => {
    const counts = facetCounts();
    return counts && counts.total !== null && counts.fields ? counts.fields : null;
  });
  const matchTotal = createMemo<number | null>(() => {
    const counts = facetCounts();
    return counts && counts.fields ? counts.total : null;
  });

  // ---- Query composition (plan 2026-10-15): complete the token at the caret, never the rest.
  const completion = createMemo(() => completionAt(draft(), caret()));

  // Value-index lookups (/api/search/values) are global "recorded" values: asked only when counts
  // are unavailable or leave room, debounced, and aborted when the edited token changes.
  const recordedRequest = createMemo<RecordedRequest | null>(
    () => {
      const context = completion();
      if (!suggestionsOpen() || !context || context.kind !== "value") return null;
      const field = recordedValueField(context.field);
      if (!field || !needsRecordedValues(context, countedFields())) return null;
      return { key: `${field}\n${context.prefix}`, field, prefix: context.prefix };
    },
    null,
    { equals: (left, right) => left?.key === right?.key },
  );
  const [recorded, setRecorded] = createSignal<{ key: string; values: string[] } | null>(null);
  let recordedTimer: ReturnType<typeof setTimeout> | undefined;
  let recordedAbort: AbortController | undefined;
  createEffect(() => {
    const request = recordedRequest();
    if (recordedTimer) clearTimeout(recordedTimer);
    recordedAbort?.abort();
    recordedAbort = undefined;
    if (!request) return;
    recordedTimer = setTimeout(() => {
      const controller = new AbortController();
      recordedAbort = controller;
      searchValues(request.field, request.prefix, RECORDED_VALUE_LIMIT, controller.signal)
        .then((values) => {
          if (!controller.signal.aborted) setRecorded({ key: request.key, values });
        })
        .catch(() => {
          // An unavailable value index leaves the list to the other sources.
          if (!controller.signal.aborted) setRecorded({ key: request.key, values: [] });
        });
    }, VALUES_DEBOUNCE_MS);
  });
  // Only values answered for the token being edited now; a stale list is never selectable.
  const recordedValues = () => {
    const current = recorded();
    return current && current.key === recordedRequest()?.key ? current.values : null;
  };

  // session: offers recent sessions, fetched once per popover opening and filtered locally.
  const [recentSessions, setRecentSessions] = createSignal<AgentSession[] | null>(null);
  let sessionsOpening = 0;
  let sessionsFetchedFor = -1;
  createEffect(() => {
    if (suggestionsOpen()) return;
    sessionsOpening += 1;
    setRecentSessions(null);
  });
  const wantsSessions = createMemo(() => {
    const context = completion();
    return suggestionsOpen() && context?.kind === "value" && context.field === "session";
  });
  createEffect(() => {
    if (!wantsSessions() || sessionsFetchedFor === sessionsOpening) return;
    const opening = sessionsOpening;
    sessionsFetchedFor = opening;
    getSessions(SESSION_SUGGESTION_LIMIT)
      .then((sessions) => {
        if (opening === sessionsOpening) setRecentSessions(sessions);
      })
      .catch(() => {
        if (opening === sessionsOpening) setRecentSessions([]);
      });
  });

  const suggestionOptions = createMemo<CompletionOption[]>(() => {
    const context = completion();
    if (!context) return [];
    if (context.kind === "field") return fieldOptions(context);
    return valueOptions(context, {
      counts: countedFields(),
      showCounts: countsApplyToDraft(draft(), context, visibleSubmitted()),
      recorded: recordedValues(),
      projects: props.projects ?? [],
      pinnedProject: props.project ?? null,
      sessions: recentSessions(),
    });
  });
  const selectableOptions = createMemo(() =>
    suggestionOptions().filter((option) => option.kind !== "note"),
  );
  const showSuggestions = createMemo(() => {
    const context = completion();
    if (!suggestionsOpen() || !context || !suggestionOptions().length) return false;
    // Before a letter is typed, field names wait for an explicit ArrowDown/ArrowUp.
    return !(context.kind === "field" && context.explicitOnly) || explicitOpen();
  });
  // Rendered rows with their keyboard index; a note is never highlighted or accepted.
  const renderedOptions = createMemo(() => {
    if (!showSuggestions()) return [];
    let selectable = -1;
    return suggestionOptions().map((option) => ({
      option,
      index: option.kind === "note" ? -1 : ++selectable,
    }));
  });
  // Rows are keyed by option id and read their option by id, so a recompute (counts, the value
  // index or sessions arriving) updates a row in place instead of replacing its element: a press
  // that began on an option still lands on it.
  const renderedIds = createMemo(() => renderedOptions().map((entry) => entry.option.id));
  const renderedById = createMemo(
    () => new Map(renderedOptions().map((entry) => [entry.option.id, entry])),
  );
  // Keyboard highlight, held by option id so values that arrive later (counts, the value index,
  // sessions) never move it to a different option; -1 means "typing, nothing highlighted".
  const [activeSuggestionId, setActiveSuggestionId] = createSignal<string | null>(null);
  const activeSuggestion = createMemo(() => {
    const id = activeSuggestionId();
    return id === null ? -1 : selectableOptions().findIndex((option) => option.id === id);
  });
  // Another token under the caret (typing, moving, accepting) starts without a highlight.
  createEffect(on(completion, () => setActiveSuggestionId(null), { defer: true }));
  // Counts stay out of option names (aria-hidden); the polite region carries them instead.
  const suggestionAnnouncement = createMemo(() => {
    if (!showSuggestions()) return "";
    const options = selectableOptions();
    const active = options[activeSuggestion()];
    if (active?.count !== undefined) return `${active.label}: ${active.count} in current results`;
    if (!options.length) return "";
    return `${options.length} ${options.length === 1 ? "suggestion" : "suggestions"}`;
  });

  // ---- Interpretation (plan 2026-10-15 §Slice 2): what the applied query did, and how to widen
  // it when nothing matched. Notes describe the applied query, never the unsubmitted draft.
  const zeroResults = createMemo(
    () => !loading() && !error() && !props.projectScopePending && items().length === 0,
  );
  const interpretation = createMemo(() =>
    interpretQuery(visibleSubmitted(), {
      zeroResults: zeroResults(),
      counts: countedFields(),
      strippedProjectTokens: strippedProjectTokens(),
      pinnedProjectName: props.project ? projectShortName(props.project) : null,
    }),
  );
  const queryNotes = createMemo(() => interpretation().filter((note) => !note.recovery));
  const recoveryNotes = createMemo(() => {
    const notes = interpretation().filter((note) => note.recovery);
    const widen = meaningfulRecovery(visibleSubmitted(), {
      zeroResults: zeroResults(),
      humanOnly: humanOnly(),
    });
    return widen ? [...notes, widen] : notes;
  });

  function refreshCanonicalSnapshot() {
    // Recovery can remove rows or reveal backdated captures. Reuse the full scoped load rather
    // than merging a head request bounded by the old observedAt; its tokens reject earlier reads.
    if (liveTimer) clearTimeout(liveTimer);
    liveTimer = undefined;
    canonicalRefreshNeeded = false;
    setSnapshotRevision((revision) => revision + 1);
  }
  const stopReset = live.onReset?.(refreshCanonicalSnapshot);
  onCleanup(() => stopReset?.());
  createEffect(
    on(
      live.status,
      (status, previous) => {
        if (status === "live" && previous === "down") refreshCanonicalSnapshot();
      },
      { defer: true },
    ),
  );

  createEffect<string | undefined>((previousEventId) => {
    // The live store retains at most 50 frames, so its length cannot signal later arrivals.
    const notification = live.events()[0];
    const newestEventId = notification?.id;
    if (livePaused() || !newestEventId) return newestEventId;
    if (newestEventId === previousEventId) return newestEventId;
    const head = newestObservedAt();
    // Keep this sticky across the burst: a later fresh timestamp cannot erase an earlier
    // backdated/tied append, and an empty feed has no timestamp suitable for a head request.
    canonicalRefreshNeeded ||= !head || Date.parse(notification.observedAt) <= Date.parse(head);
    if (liveTimer) clearTimeout(liveTimer);
    liveTimer = setTimeout(() => {
      liveTimer = undefined;
      if (canonicalRefreshNeeded) refreshCanonicalSnapshot();
      else void refetchHead(newestObservedAt());
    }, 500);
    return newestEventId;
  });

  function run(next: string) {
    setParams({ q: next.trim() || undefined });
  }

  function submit(event: SubmitEvent) {
    event.preventDefault();
    dismissSuggestions();
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

  // Picking a view replaces the visible q wholesale (a view IS a q string, D13); the pinned
  // project is picker state, not q, so it stays untouched.
  function applyView(q: string) {
    run(q);
    setOpenPanel(null);
  }

  function saveCurrentView() {
    const name = saveViewName().trim();
    const q = visibleSubmitted().trim();
    if (!name || !q) return;
    setSavedViews(saveSavedView(name, q));
    setSaveViewName("");
  }

  function removeView(name: string) {
    setSavedViews(removeSavedView(name));
  }

  // Counted-browser click = replace that facet's value (spec §6.5). The no-project sentinel is
  // only expressible as an exact token — a substring project: filter for it would match nothing.
  function pickCountedValue(key: FacetField["key"], value: string) {
    if (key === "project" && value === "__no_project__") {
      run(setFacet(visibleSubmitted(), "project_exact", value));
      return;
    }
    run(setFacet(visibleSubmitted(), key, value));
  }

  function dismissSuggestions() {
    batch(() => {
      setSuggestionsOpen(false);
      setExplicitOpen(false);
      setActiveSuggestionId(null);
    });
  }

  function handleQueryInput(input: HTMLInputElement) {
    batch(() => {
      setDraft(input.value);
      setCaret(input.selectionStart ?? input.value.length);
      setExplicitOpen(false);
      setActiveSuggestionId(null);
      setSuggestionsOpen(true);
    });
  }

  function trackCaret(input: HTMLInputElement) {
    setCaret(input.selectionStart ?? input.value.length);
  }

  function handleInputKeyDown(event: KeyboardEvent) {
    // Keys pressed while an IME composes text belong to the IME, not to the popover.
    if (event.isComposing || event.keyCode === 229) return;
    if (event.key === "Escape") {
      // Dismiss the closest visible popup first; a later Escape can close a surrounding panel.
      if (showSuggestions()) event.preventDefault();
      dismissSuggestions();
      return;
    }
    if (event.key === "Tab") {
      handleTab(event);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!selectableOptions().length) return;
      event.preventDefault();
      batch(() => {
        setSuggestionsOpen(true);
        setExplicitOpen(true);
      });
      const options = selectableOptions();
      if (!options.length) return;
      const index = activeSuggestion();
      const next =
        event.key === "ArrowDown"
          ? (index + 1) % options.length
          : index <= 0
            ? options.length - 1
            : index - 1;
      setActiveSuggestionId(options[next].id);
      return;
    }
    if (event.key === "Enter" && showSuggestions() && activeSuggestion() >= 0) {
      // Enter with a highlight accepts the suggestion; without one it submits the raw text.
      event.preventDefault();
      const option = selectableOptions()[activeSuggestion()];
      if (option) acceptSuggestion(option);
    }
  }

  // Tab accepts the highlighted option, or the first one once the edited token has a typed
  // prefix — a documented deviation from the APG combobox, which accepts on Tab only after a
  // selection. A value typed in full is listed first, so Tab keeps it; a whole word that names a
  // field is left as typed unless highlighted. Never with a modifier or on an empty draft;
  // otherwise focus simply moves on.
  function handleTab(event: KeyboardEvent) {
    const modified = event.shiftKey || event.ctrlKey || event.altKey || event.metaKey;
    const context = completion();
    if (!modified && draft().trim() && showSuggestions() && context) {
      const options = selectableOptions();
      const target =
        options[activeSuggestion()] ?? (tabAcceptsFirstOption(context) ? options[0] : undefined);
      if (target) {
        event.preventDefault();
        acceptSuggestion(target);
        return;
      }
    }
    dismissSuggestions();
  }

  // Replaces only the edited range. A value closes the popover until the next input; a field
  // name keeps it open on that field's values.
  function acceptSuggestion(option: CompletionOption) {
    const context = completion();
    if (!context || option.kind === "note") return;
    const next = applyCompletion(draft(), context, option);
    batch(() => {
      setDraft(next.text);
      setCaret(next.caret);
      setActiveSuggestionId(null);
      setExplicitOpen(false);
      setSuggestionsOpen(option.kind === "field");
    });
    // The value binding moved the caret to the end; put it after the inserted text.
    if (inputRef) {
      if (inputRef.value !== next.text) inputRef.value = next.text;
      if (document.activeElement !== inputRef) inputRef.focus();
      inputRef.setSelectionRange(next.caret, next.caret);
    }
    setCaret(next.caret);
  }

  function handleDocumentPointerDown(event: PointerEvent) {
    const target = event.target as Node;
    if (!inputWrapRef?.contains(target)) dismissSuggestions();
    if (openPanel() && !viewsRef?.contains(target) && !optionsRef?.contains(target)) closePanel();
  }

  function handleDocumentKeyDown(event: KeyboardEvent) {
    if (event.key !== "Escape" || event.defaultPrevented || !openPanel()) return;
    event.preventDefault();
    closePanel(true);
  }

  async function loadMore() {
    const before = nextBefore();
    if (
      props.projectScopePending ||
      loading() ||
      !before ||
      loadingMore() ||
      items().length >= MAX_ROWS
    )
      return;
    const token = loadToken;
    setLoadingMore(true);
    setError(null);
    try {
      const response = await getEventFeed({
        limit: FEED_LIMIT,
        q: apiQuery(),
        meaningful: true,
        humanOnly: humanOnly() || undefined,
        before,
      });
      if (!isCurrentStreamRequest(token)) return;
      setItems((current) => dedupe([...current, ...response.items]).slice(0, MAX_ROWS));
      setNextBefore(items().length >= MAX_ROWS ? null : (response.nextBefore ?? null));
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
      const response = await getEventFeed({
        limit: FEED_LIMIT,
        q: apiQuery(),
        meaningful: true,
        humanOnly: humanOnly() || undefined,
        since,
      });
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
  document.addEventListener("keydown", handleDocumentKeyDown);
  onCleanup(() => {
    document.removeEventListener("pointerdown", handleDocumentPointerDown);
    document.removeEventListener("keydown", handleDocumentKeyDown);
    if (liveTimer) clearTimeout(liveTimer);
    if (facetsTimer) clearTimeout(facetsTimer);
    facetsAbort?.abort();
    if (recordedTimer) clearTimeout(recordedTimer);
    recordedAbort?.abort();
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
              onInput={(event) => handleQueryInput(event.currentTarget)}
              onKeyDown={handleInputKeyDown}
              onKeyUp={(event) => trackCaret(event.currentTarget)}
              onClick={(event) => trackCaret(event.currentTarget)}
              onSelect={(event) => trackCaret(event.currentTarget)}
              onFocus={(event) => trackCaret(event.currentTarget)}
              placeholder="source:codex kind:Decision recall bug"
              aria-label="Stream query"
              role="combobox"
              autocapitalize="off"
              autocorrect="off"
              spellcheck={false}
              enterkeyhint="search"
              aria-expanded={showSuggestions()}
              aria-controls="stream-suggest-list"
              aria-autocomplete="list"
              aria-activedescendant={
                showSuggestions() && activeSuggestion() >= 0
                  ? `stream-suggest-option-${activeSuggestion()}`
                  : undefined
              }
            />
            <button type="submit">Filter</button>
            {/* Always mounted (hidden when closed) so aria-controls never points at nothing. */}
            <ul
              class="suggest-popover"
              role="listbox"
              id="stream-suggest-list"
              aria-label="Query suggestions"
              hidden={!showSuggestions()}
            >
              <For each={renderedIds()}>
                {(id) => (
                  <Show when={renderedById().get(id)}>
                    {(entry) => (
                      <Show
                        when={entry().index >= 0}
                        fallback={
                          <li
                            role="option"
                            aria-disabled="true"
                            aria-selected="false"
                            class="suggest-option suggest-option--note"
                          >
                            {entry().option.label}
                          </li>
                        }
                      >
                        <li
                          role="option"
                          id={`stream-suggest-option-${entry().index}`}
                          aria-selected={entry().index === activeSuggestion()}
                          aria-describedby={
                            entry().option.detail
                              ? `stream-suggest-detail-${entry().index}`
                              : undefined
                          }
                          classList={{
                            "suggest-option": true,
                            "suggest-option--active": entry().index === activeSuggestion(),
                          }}
                          // Keep focus (and the caret) in the query while choosing with a pointer.
                          onPointerDown={(event) => event.preventDefault()}
                          onMouseDown={(event) => event.preventDefault()}
                          onClick={() => acceptSuggestion(entry().option)}
                        >
                          <span class="suggest-option-value">{entry().option.label}</span>
                          <Show when={entry().option.count !== undefined}>
                            <span class="suggest-option-count" aria-hidden="true">
                              {entry().option.count}
                            </span>
                          </Show>
                          <Show when={entry().option.detail}>
                            <span
                              id={`stream-suggest-detail-${entry().index}`}
                              class="suggest-option-detail"
                              aria-hidden="true"
                            >
                              {entry().option.detail}
                            </span>
                          </Show>
                        </li>
                      </Show>
                    )}
                  </Show>
                )}
              </For>
            </ul>
          </div>
          <div ref={viewsRef} class="stream-options stream-views">
            <button
              type="button"
              class="stream-options-trigger"
              aria-expanded={viewsOpen()}
              aria-controls="stream-views-panel"
              onClick={() => togglePanel("views")}
            >
              Views
            </button>
            <Show when={viewsOpen()}>
              <div id="stream-views-panel" class="stream-options-panel stream-views-panel">
                <For each={VIEW_PRESETS}>
                  {(preset) => (
                    <button
                      type="button"
                      class="stream-view-option"
                      onClick={() => applyView(preset.q)}
                    >
                      <span class="stream-view-name">{preset.name}</span>
                      <code class="stream-view-q">{preset.q}</code>
                    </button>
                  )}
                </For>
                <Show when={savedViews().length}>
                  <hr class="stream-views-separator" />
                  <For each={savedViews()}>
                    {(view) => (
                      <div class="stream-view-saved">
                        <button
                          type="button"
                          class="stream-view-option"
                          title={view.q}
                          onClick={() => applyView(view.q)}
                        >
                          <span class="stream-view-name">{view.name}</span>
                        </button>
                        <button
                          type="button"
                          class="stream-view-remove"
                          aria-label={`Remove saved view ${view.name}`}
                          onClick={() => removeView(view.name)}
                        >
                          ✕
                        </button>
                      </div>
                    )}
                  </For>
                </Show>
                <hr class="stream-views-separator" />
                {/* Inline save (spec §8): enabled only while the visible q is non-empty. */}
                <div class="stream-view-save">
                  <input
                    class="stream-view-save-input"
                    value={saveViewName()}
                    placeholder="Save current view…"
                    aria-label="Saved view name"
                    disabled={!visibleSubmitted().trim()}
                    onInput={(event) => setSaveViewName(event.currentTarget.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        saveCurrentView();
                      }
                    }}
                  />
                  <button
                    type="button"
                    disabled={!visibleSubmitted().trim() || !saveViewName().trim()}
                    onClick={saveCurrentView}
                  >
                    Save
                  </button>
                </div>
              </div>
            </Show>
          </div>
          <div ref={optionsRef} class="stream-options">
            <button
              type="button"
              class="stream-options-trigger"
              aria-expanded={optionsOpen()}
              aria-controls="stream-options-panel"
              onClick={() => togglePanel("options")}
            >
              Options
            </button>
            <Show when={optionsOpen()}>
              <div id="stream-options-panel" class="stream-options-panel">
                <label class="meaningful-toggle">
                  <input
                    type="checkbox"
                    disabled={humanOnly()}
                    checked={humanOnly() || !parsed().isAll}
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
                        <button
                          type="button"
                          class="facet-chip facet-chip--active"
                          onClick={() => removeFacetChip(field.key, value)}
                        >
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
                  session: {sessionChipLabel(sessionRef(), items())} x
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
        {/* How the applied query was read (plan 2026-10-15 §Slice 2): tokens searched as text,
            grammar limits and stripped project filters, each fix rewriting one token by span. */}
        <Show when={queryNotes().length}>
          <QueryNoteList notes={queryNotes()} label="How the query was read" onFix={run} />
        </Show>
      </form>

      <Show when={error()}>{(message) => <p class="empty-state">{message()}</p>}</Show>

      <Show when={humanOnly()}>
        <p class="human-mode-banner" role="status">
          Showing only your turns · press H for everything
        </p>
      </Show>

      <Show when={matchTotal() !== null || scopePhrases().length > 0 || livePaused()}>
        <div class="stream-result-header">
          {/* The count is omitted entirely while unavailable (loading, aborted, error, FTS
              backfill) — the scope phrase stands alone, never a stale number (spec §4.6). */}
          <Show when={matchTotal() !== null}>
            <button
              type="button"
              class="stream-result-count"
              aria-expanded={browserOpen()}
              aria-controls="stream-count-browser"
              onClick={() => setBrowserOpen((open) => !open)}
            >
              {formatMatchCount(matchTotal()!)}
            </button>
          </Show>
          <Show when={scopePhrases().length}>
            <span class="stream-result-scope">{scopePhrases().join(" · ")}</span>
          </Show>
          <Show when={livePaused()}>
            <span class="stream-live-paused">live paused — historical scope</span>
          </Show>
        </div>
      </Show>

      {/* Semantic recall is linked, never blended (spec §4.6/§6.3, D9): free text earns one
          quiet affordance jumping to the Ask surface prefilled — no semantic rows in the feed. */}
      <Show when={parsed().freeTerms[0]}>
        {(phrase) => (
          <div class="stream-ask-memory">
            <A href={askMemoryHref(phrase(), props.project)}>
              Ask memory about «{phrase()}» <span aria-hidden="true">→</span>
            </A>
          </div>
        )}
      </Show>

      {/* On-demand counted browser (spec §6.5, D10): opens from the match count, no standing
          rail. It renders only while counts exist for the current q — a query change closes it
          with the counts rather than showing numbers for a different query. */}
      <Show when={browserOpen() ? countedFields() : null}>
        {(fields) => (
          <div id="stream-count-browser" class="stream-count-browser">
            <For each={FACET_FIELDS}>
              {(field) => (
                <div class="stream-count-field">
                  <span class="facet-label">{field.label}</span>
                  <For each={browserRows(fields()[field.key], parsed().facets[field.key] ?? [])}>
                    {(row) => (
                      <button
                        type="button"
                        classList={{
                          "stream-count-value": true,
                          "stream-count-value--zero": row.count === 0,
                        }}
                        onClick={() => pickCountedValue(field.key, row.value)}
                      >
                        <span class="stream-count-value-label">
                          {field.key === "project" ? truncatePath(row.value) : row.value}
                        </span>
                        <span class="stream-count-value-count">{row.count}</span>
                      </button>
                    )}
                  </For>
                </div>
              )}
            </For>
          </div>
        )}
      </Show>

      {/* Persistent live region (spec §4.6): the N-new pill mounts conditionally and cannot be
          the live region — this one always exists, announcing count changes politely. */}
      <div class="visually-hidden" aria-live="polite">
        {newCount() > 0 ? `${newCount()} new ${newCount() === 1 ? "event" : "events"}` : ""}
      </div>
      {/* The same persistent pattern for query suggestions: their counts are aria-hidden in the
          options (names stay the values), so the count and list size are announced here. */}
      <div class="visually-hidden" aria-live="polite" id="stream-suggest-status">
        {suggestionAnnouncement()}
      </div>

      <div
        ref={feedRef}
        class="stream-feed"
        role="feed"
        aria-busy={loadingMore()}
        aria-label="Activity stream"
      >
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
                      <Show when={day().gapLabel}>
                        {(gap) => <span class="stream-daybreak-gap">· {gap()}</span>}
                      </Show>
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
                            streamLink={sessionStreamLink(
                              visibleSubmitted(),
                              run().sessionId,
                              props.project?.projectKey ?? "",
                            )}
                            onFilterToSession={(sessionId) =>
                              patchQuery((state) => (state.session = sessionId))
                            }
                          />
                        }
                      />
                      <div class="stream-run-body">
                        <For each={run().rows}>
                          {(row) =>
                            row.type === "event" ? (
                              <StreamRow
                                item={withHumanText(row.item, humanOnly())}
                                humanMode={humanOnly()}
                                expanded={isExpanded(row.item.id)}
                                textExpanded={density() === "expanded"}
                                sessionHref={sessionHref(row.item, props.project)}
                                trajectoryHref={trajectoryHrefFor(row.item, props.projects)}
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
          <Show when={!items().length && !error()}>
            <p class="empty-state">
              {hasVisibleFilter() || props.project
                ? humanOnly()
                  ? "No human turns match the current filters."
                  : "No stream events match the current filters."
                : humanOnly()
                  ? "No human turns recorded yet."
                  : parsed().isAll
                    ? "No events recorded yet."
                    : "No meaningful events recorded yet."}
            </p>
            <Show when={recoveryNotes().length}>
              <QueryNoteList notes={recoveryNotes()} label="Ways to widen the query" onFix={run} />
            </Show>
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

// Counted rows plus any currently-active values the cap or the filter pushed out of the server's
// list — those render with an honest zero, dim but clickable, never silently hidden (spec §4.6).
function browserRows(listed: FacetValueCount[], active: string[]): FacetValueCount[] {
  const missing = active
    .filter((value) => !listed.some((row) => row.value === value))
    .map((value) => ({ value, count: 0 }));
  return [...listed, ...missing];
}

function formatMatchCount(total: number): string {
  return `${total.toLocaleString("en-US")} ${total === 1 ? "match" : "matches"}`;
}

// The hidden scope goes first when the visible query ends in an unclosed quote or a bare NOT:
// the quote would swallow it, and both parsers read a negated project_group as free text.
function appendProjectGroupScope(query: string, canonicalKey: string): string {
  return appendQueryToken(query, `project_group:${quoteHiddenFacet(canonicalKey)}`);
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

function shortSessionRef(value: string): string {
  return value.length > 14 ? `${value.slice(0, 12)}…` : value;
}

// A session chip names the session when a loaded row carries its title; otherwise a short ref.
function sessionChipLabel(sessionRef: string, rows: EventFeedItem[]): string {
  const title = rows
    .find(
      (row) =>
        (row.sessionId === sessionRef || row.clientSessionId === sessionRef) &&
        row.sessionTitle?.trim(),
    )
    ?.sessionTitle?.trim();
  if (!title) return shortSessionRef(sessionRef);
  return title.length > SESSION_CHIP_TITLE_LIMIT
    ? `${title.slice(0, SESSION_CHIP_TITLE_LIMIT - 1)}…`
    : title;
}

// Interpretation notes and zero-result fixes; a fix rewrites one token and runs the query.
function QueryNoteList(props: {
  notes: QueryNote[];
  label: string;
  onFix: (query: string) => void;
}) {
  return (
    <ul class="stream-query-notes" aria-label={props.label}>
      <For each={props.notes}>
        {(note) => (
          <li class="stream-query-note">
            <Show when={note.token}>
              {(token) => (
                <>
                  <code>{token()}</code>{" "}
                </>
              )}
            </Show>
            <span>{note.text}</span>
            <Show when={note.fix}>
              {(fix) => (
                <button
                  type="button"
                  class="stream-query-fix"
                  onClick={() => props.onFix(fix().query)}
                >
                  {fix().label}
                </button>
              )}
            </Show>
          </li>
        )}
      </For>
    </ul>
  );
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

// "Ask memory about «text» →" (spec §4.6/§6.3): navigates to the Ask surface prefilled. The
// prefill parameter is ?q= — the one ActivityPage/SearchPage already carry and the ask panel
// seeds its question from — with view=ask mounting the panel. The pinned project rides along so
// the host page keeps its scope chrome.
function askMemoryHref(phrase: string, project: ProjectSummary | null | undefined): string {
  const query = new URLSearchParams({ view: "ask", q: phrase });
  if (project) query.set("project", project.projectKey);
  return `/?${query.toString()}`;
}

// "Trajectory →" for expanded landmark cards (spec §9, D14): projectKey resolves from the row's
// cwd via the already-loaded catalog (exact/canonical scope match only). Unresolvable cwds get
// no link — never guess.
function trajectoryHrefFor(
  item: EventFeedItem,
  projects: ProjectSummary[] | undefined,
): string | undefined {
  if (!TRAJECTORY_LINK_KINDS.has(item.eventType ?? "")) return undefined;
  if (!item.cwd || !projects?.length) return undefined;
  const project = findProjectByIdentifier(projects, item.cwd);
  if (!project) return undefined;
  const query = new URLSearchParams({ focus: `capture:${item.id}` });
  return `/projects/${encodeURIComponent(project.projectKey)}?${query.toString()}`;
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
