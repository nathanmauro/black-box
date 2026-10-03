import { useNavigate, useParams } from "@solidjs/router";
import {
  batch,
  createEffect,
  createMemo,
  createResource,
  createSignal,
  createUniqueId,
  For,
  onCleanup,
  onMount,
  on,
  Show,
  useContext,
  untrack,
} from "solid-js";
import ConversationNavigator, {
  type ConversationNavigatorTurn,
} from "../components/ConversationNavigator";
import SessionLineage from "../components/SessionLineage";
import SourceDot from "../components/SourceDot";
import { EventRenderer, ReaderText } from "../components/events/EventRow";
import {
  getEvent,
  getProjectSessions,
  getSession,
  getSessionChildCounts,
  getSessionDag,
  getSessionEvents,
  getSessionLinks,
  getSessionTranscript,
  getSessions,
  type AgentEvent,
  type AgentSession,
  type ProjectSummary,
  type SessionLink,
  type SessionTranscriptResponse,
} from "../lib/api";
import { sourceColor, sourceLabel, timeAgo, truncatePath } from "../lib/format";
import { distinctTitle, humanOnly, leadLine, withHumanText } from "../lib/humanOnly";
import { projectMatchesSession } from "../lib/projects";
import { parseQuery } from "../lib/query";
import { readerTextPreview } from "../lib/payloadPreview";
import {
  filterSessionTranscriptTurns,
  isSessionMemoryEvent as isMemoryEvent,
  isSessionReaderEvent as isPrimaryReaderEvent,
  mergeSessionEvents,
  sessionConversationRole as conversationRole,
} from "../lib/sessionTranscript";
import { LiveStoreContext } from "../lib/sse";
import { sourceFilter } from "../lib/stores";

type SessionsPageProps = {
  selectedSessionId?: string;
  targetEventId?: string;
  project?: ProjectSummary | null;
  defaultToFirst?: boolean;
  onSelectSession?: (id: string) => void;
  params?: unknown;
  location?: unknown;
  data?: unknown;
  children?: unknown;
};

type PromptTurn = {
  id: string;
  prompt: AgentEvent | null;
  events: AgentEvent[];
};

type ProjectSessionResult = {
  projectKey: string;
  sessions: AgentSession[];
};

type SessionTranscriptRequest = {
  sessionId: string;
  targetEventId?: string;
  query: string;
  humanOnly: boolean;
};

type SessionTranscriptState = SessionTranscriptResponse & {
  query: string;
  humanOnly: boolean;
  legacyFallback: boolean;
};

const DUPLICATE_PROMPT_WINDOW_MS = 2 * 60 * 1_000;
const RECENT_SESSION_LIMIT = 120;
const TRANSCRIPT_PAGE_LIMIT = 50;
const TRANSCRIPT_SEARCH_DEBOUNCE_MS = 240;
const EMPTY_TRANSCRIPT: SessionTranscriptState = {
  sessionId: "",
  available: true,
  complete: true,
  reason: null,
  limit: TRANSCRIPT_PAGE_LIMIT,
  count: 0,
  events: [],
  nextBefore: null,
  query: "",
  humanOnly: false,
  legacyFallback: false,
};

export default function SessionsPage(props: SessionsPageProps = {}) {
  const params = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const live = useContext(LiveStoreContext);
  let transcriptGeneration = 0;
  const [sessionFilter, setSessionFilter] = createSignal("");
  const [transcriptQuery, setTranscriptQuery] = createSignal("");
  const [debouncedTranscriptQuery, setDebouncedTranscriptQuery] = createSignal("");
  const [activeSearchTurnId, setActiveSearchTurnId] = createSignal("");
  const [baselineEvents, setBaselineEvents] = createSignal<{
    sessionId: string;
    events: AgentEvent[];
  }>({
    sessionId: "",
    events: [],
  });
  const [olderEventsLoading, setOlderEventsLoading] = createSignal(false);
  const [olderEventsError, setOlderEventsError] = createSignal("");
  const [showMemoryEvents, setShowMemoryEvents] = createSignal(false);
  const compactMedia =
    typeof window.matchMedia === "function" ? window.matchMedia("(max-width: 880px)") : null;
  const [compactReader, setCompactReader] = createSignal(compactMedia?.matches ?? false);
  const [chooserOpen, setChooserOpen] = createSignal(false);
  const [detailsOpen, setDetailsOpen] = createSignal(false);
  const chooserId = createUniqueId();
  const titleDetailsId = createUniqueId();
  const summaryDetailsId = createUniqueId();
  let pageElement: HTMLElement | undefined;
  let chooserButton: HTMLButtonElement | undefined;
  let detailsButton: HTMLButtonElement | undefined;
  let sessionSearch: HTMLInputElement | undefined;
  let readerHeading: HTMLHeadingElement | undefined;
  let focusedExactTarget = "";

  onMount(() => {
    const update = () => {
      const focused = document.activeElement as HTMLElement | null;
      const compact = compactMedia?.matches ?? false;
      const selected = selectedId();
      // A dismissed mobile chooser filter must not remove the current reader on desktop.
      if (!compact && selected && !filteredSessions().some((session) => session.id === selected)) {
        setSessionFilter("");
      }
      setCompactReader(compact);
      setChooserOpen(false);
      setDetailsOpen(false);
      queueMicrotask(() => {
        if (focused && pageElement?.contains(focused) && focused.closest("[hidden]")) {
          (compact ? chooserButton : (readerHeading ?? sessionSearch))?.focus({
            preventScroll: true,
          });
        }
      });
    };
    compactMedia?.addEventListener("change", update);
    onCleanup(() => compactMedia?.removeEventListener("change", update));
  });
  const [allSessions, { refetch: refetchSessions }] = createResource(
    () => (props.project ? null : { source: sourceFilter.key(), human: humanOnly() }),
    async ({ human }) =>
      sourceFilter.matches(await getSessions(RECENT_SESSION_LIMIT, false, human)),
    { initialValue: [] as AgentSession[] },
  );
  const [projectSessions, { refetch: refetchProjectSessions }] = createResource(
    () => ({ projectKey: props.project?.projectKey, human: humanOnly() }),
    async ({ projectKey, human }): Promise<ProjectSessionResult | null> =>
      projectKey
        ? {
            projectKey,
            sessions: (await getProjectSessions(projectKey, RECENT_SESSION_LIMIT, human)).filter(
              (s) => !s.spawnedBy,
            ),
          }
        : null,
    { initialValue: null as ProjectSessionResult | null },
  );
  const scopedProjectSessions = createMemo(() => {
    const projectKey = props.project?.projectKey;
    const result = projectSessions();
    if (!projectKey || result?.projectKey !== projectKey) return [];
    return sourceFilter.matches(result.sessions);
  });
  const requestedSessionId = createMemo(() => props.selectedSessionId || params.sessionId || "");
  const [requestedSession, { refetch: refetchRequestedSession }] = createResource(
    requestedSessionId,
    async (id) => {
      if (!id) return null;
      try {
        return await getSession(id);
      } catch {
        return null;
      }
    },
    { initialValue: null as AgentSession | null },
  );
  const sessions = createMemo(() => {
    // Hide stale nonhuman rows immediately while the server-filtered request is refreshing.
    const listed = (props.project ? scopedProjectSessions() : allSessions()).filter(
      (session) => !humanOnly() || Boolean(session.firstHumanTurn?.trim()),
    );
    const requested = requestedSession();
    if (humanOnly() && !props.targetEventId && !requested?.firstHumanTurn?.trim()) return listed;
    if (!requested || listed.some((session) => session.id === requested.id)) return listed;
    if (props.project && !projectMatchesSession(props.project, requested)) return listed;
    if (!sourceFilter.matches([requested]).length) return listed;
    return [requested, ...listed];
  });
  const filteredSessions = createMemo(() => filterSessions(sessions(), sessionFilter()));
  const requestedExcludedByHumanMode = createMemo(() => {
    const requested = requestedSession();
    return (
      humanOnly() &&
      !props.targetEventId &&
      requested?.id === requestedSessionId() &&
      !requested.firstHumanTurn?.trim() &&
      (!props.project || projectMatchesSession(props.project, requested)) &&
      sourceFilter.matches([requested]).length > 0
    );
  });
  const selectedId = createMemo(() => {
    // Searching the mobile chooser must not switch the reader before a session is chosen.
    const scopedSessions = compactReader() ? sessions() : filteredSessions();
    const requestedId = requestedSessionId();
    if (requestedId && scopedSessions.some((session) => session.id === requestedId))
      return requestedId;
    if (requestedId && requestedSession.loading) return "";
    return props.defaultToFirst || requestedExcludedByHumanMode()
      ? (scopedSessions[0]?.id ?? "")
      : "";
  });
  const selectedSession = createMemo(() =>
    (compactReader() ? sessions() : filteredSessions()).find(
      (session) => session.id === selectedId(),
    ),
  );
  createEffect(() => {
    if (!requestedExcludedByHumanMode() || allSessions.loading || projectSessions.loading) return;
    const requested = requestedSession();
    const selected = selectedId();
    if (
      requested?.id === requestedSessionId() &&
      !requested.firstHumanTurn?.trim() &&
      selected &&
      selected !== requested.id
    )
      selectSession(selected);
  });

  const chooserVisible = () => !compactReader() || chooserOpen() || !selectedSession();

  function toggleChooser() {
    const open = !chooserVisible();
    setChooserOpen(open);
    if (open) setDetailsOpen(false);
    queueMicrotask(() => (open ? sessionSearch : chooserButton)?.focus({ preventScroll: true }));
  }

  function handleDisclosureEscape(event: KeyboardEvent) {
    if (event.key !== "Escape" || event.defaultPrevented || !compactReader()) return;
    if (chooserOpen() && selectedSession()) {
      event.preventDefault();
      setChooserOpen(false);
      chooserButton?.focus({ preventScroll: true });
    } else if (detailsOpen()) {
      event.preventDefault();
      setDetailsOpen(false);
      detailsButton?.focus({ preventScroll: true });
    }
  }

  const [lineageDag, { refetch: refetchLineageDag }] = createResource(
    () => (selectedId() ? selectedId() : undefined),
    (sessionId) => getSessionDag(sessionId),
  );
  const lineageDagData = createMemo(() => {
    if (lineageDag.error) return null;
    const dag = lineageDag();
    return dag && dag.nodes.filter((node) => node.type === "session").length > 1 ? dag : null;
  });
  const railSessionIds = createMemo(() => sessions().map((session) => session.id));
  const [childCounts, { refetch: refetchChildCounts }] = createResource(
    () => railSessionIds().join(","),
    async () => getSessionChildCounts(railSessionIds()),
    { initialValue: {} as Record<string, number> },
  );
  // Reading a resource in the errored state throws — guard the same way lineageDagData guards
  // lineageDag.error, so a rejected batch (e.g. a chunk request failing) degrades the rail to "no
  // expanders" instead of crashing the whole session list (there is no ErrorBoundary above this).
  const childCountsData = createMemo<Record<string, number>>(() =>
    childCounts.error ? {} : childCounts(),
  );
  const [expandedParents, setExpandedParents] = createSignal<ReadonlySet<string>>(
    new Set<string>(),
  );
  const isExpanded = (id: string) => expandedParents().has(id);
  const toggleExpanded = (id: string) => {
    setExpandedParents((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const [transcript, { refetch: refetchEvents, mutate: mutateTranscript }] = createResource(
    (): SessionTranscriptRequest | undefined => {
      const sessionId = selectedId();
      return sessionId
        ? {
            sessionId,
            targetEventId: props.targetEventId,
            query: debouncedTranscriptQuery(),
            humanOnly: humanOnly(),
          }
        : undefined;
    },
    async ({
      sessionId,
      targetEventId,
      query,
      humanOnly: human,
    }): Promise<SessionTranscriptState> => {
      try {
        const response = await getSessionTranscript(sessionId, {
          limit: TRANSCRIPT_PAGE_LIMIT,
          q: query || undefined,
          humanOnly: human || undefined,
        });
        const listed = (
          !query && targetEventId
            ? await mergeExactTarget(response.events, sessionId, targetEventId)
            : response.events
        ).map((event) => withHumanText(event, human));
        return {
          ...response,
          count: listed.length,
          events: listed,
          query,
          humanOnly: human,
          legacyFallback: false,
        };
      } catch {
        const recorded = await (human
          ? getSessionEvents(sessionId, 2_000, true)
          : getSessionEvents(sessionId, 2_000));
        const listed = (
          !query && targetEventId
            ? await mergeExactTarget(recorded, sessionId, targetEventId)
            : recorded
        ).map((event) => withHumanText(event, human));
        return {
          sessionId,
          available: false,
          complete: false,
          reason: null,
          limit: 2_000,
          count: listed.length,
          events: listed,
          nextBefore: null,
          query,
          humanOnly: human,
          legacyFallback: true,
        };
      }
    },
    { initialValue: EMPTY_TRANSCRIPT },
  );
  const transcriptData = createMemo<SessionTranscriptState>(() =>
    transcript.error ? EMPTY_TRANSCRIPT : transcript(),
  );
  const searchPending = createMemo(
    () =>
      transcriptQuery().trim() !== debouncedTranscriptQuery() ||
      (Boolean(debouncedTranscriptQuery()) &&
        transcriptData().query !== debouncedTranscriptQuery()),
  );
  const transcriptLoading = createMemo(() => transcript.loading || searchPending());
  const newestEvents = createMemo(() => {
    const data = transcriptData();
    if (!data.query) return data.events;
    const baseline = baselineEvents();
    return mergeSessionEvents(
      baseline.sessionId === data.sessionId ? baseline.events : [],
      data.events,
    );
  });
  const timelineEvents = createMemo(() => [...newestEvents()].reverse());
  const visibleEvents = createMemo(() =>
    timelineEvents().filter(
      (event) =>
        event.id === props.targetEventId ||
        isPrimaryReaderEvent(event) ||
        (showMemoryEvents() && isMemoryEvent(event)),
    ),
  );
  const groupedPromptTurns = createMemo(() => groupPromptTurns(visibleEvents()));
  const searchResultIds = createMemo(
    () => new Set(transcriptData().query ? transcriptData().events.map((event) => event.id) : []),
  );
  const promptTurns = createMemo(() => {
    const turns = groupedPromptTurns();
    if (!transcriptData().query) return turns;
    if (transcriptData().legacyFallback) {
      return filterSessionTranscriptTurns(turns, transcriptData().query);
    }
    const matchingIds = searchResultIds();
    return turns.filter(
      (turn) =>
        turn.events.some((event) => matchingIds.has(event.id)) ||
        filterSessionTranscriptTurns([turn], transcriptData().query).length > 0,
    );
  });
  const matchingPromptTurns = createMemo(() => (transcriptData().query ? promptTurns() : []));
  const displayedPromptTurns = createMemo(() => promptTurns());
  const memoryEventCount = createMemo(() => timelineEvents().filter(isMemoryEvent).length);
  const transcriptStatusNote = createMemo(() => {
    if (transcript.error) return "Session events could not be loaded.";
    const data = transcriptData();
    const reason = data.reason?.trim() ? ` (${data.reason.trim()})` : "";
    if (data.legacyFallback)
      return "Full transcript service could not be reached; showing recorded events.";
    if (!data.available) return `Source transcript unavailable; showing recorded events${reason}.`;
    if (!data.complete) return `Source transcript may be incomplete${reason}.`;
    return "";
  });
  const activeSearchPosition = createMemo(() =>
    matchingPromptTurns().findIndex((turn) => turn.id === activeSearchTurnId()),
  );
  const navigatorTurns = createMemo<ConversationNavigatorTurn[]>(() =>
    displayedPromptTurns().flatMap((turn) =>
      turn.prompt
        ? [
            {
              id: turn.id,
              prompt: turn.prompt,
              responses: turn.events.filter((event) => conversationRole(event) === "assistant"),
            },
          ]
        : [],
    ),
  );

  createEffect(() => {
    const query = transcriptQuery().trim();
    const timer = window.setTimeout(
      () => setDebouncedTranscriptQuery(query),
      TRANSCRIPT_SEARCH_DEBOUNCE_MS,
    );
    onCleanup(() => window.clearTimeout(timer));
  });

  createEffect(() => {
    const data = transcriptData();
    if (
      !transcript.loading &&
      !transcript.error &&
      data.sessionId === selectedId() &&
      !data.query
    ) {
      setBaselineEvents({ sessionId: data.sessionId, events: data.events });
    }
  });

  let searchSessionId = "";
  createEffect(() => {
    const nextSessionId = selectedId();
    if (searchSessionId && nextSessionId !== searchSessionId) {
      setTranscriptQuery("");
      setDebouncedTranscriptQuery("");
      setActiveSearchTurnId("");
      setOlderEventsError("");
    }
    searchSessionId = nextSessionId;
  });

  function refreshReader() {
    void refetchSessions();
    void refetchProjectSessions();
    void refetchRequestedSession();
    void refetchEvents();
    void refetchChildCounts();
    void refetchLineageDag();
  }

  createEffect(() => {
    if (!live) return;
    let refetchTimer: number | undefined;
    const schedule = () => {
      if (refetchTimer !== undefined) return;
      refetchTimer = window.setTimeout(() => {
        refetchTimer = undefined;
        void refetchEvents();
        void refetchChildCounts();
        void refetchLineageDag();
      }, 180);
    };
    const stopSessions = live.onSessionUpdated((update) =>
      untrack(() => {
        if (update.sessionId === selectedId()) schedule();
      }),
    );
    // Replay contains event.appended frames; it need not repeat transient session.updated frames.
    const stopEvents = live.onEventAppended((event) =>
      untrack(() => {
        if (event.sessionId === selectedId()) schedule();
      }),
    );
    const stopReset = live.onReset?.(() => {
      transcriptGeneration++;
      batch(() => {
        setBaselineEvents({ sessionId: "", events: [] });
        mutateTranscript({ ...EMPTY_TRANSCRIPT });
        setOlderEventsLoading(false);
        setOlderEventsError("");
        setActiveSearchTurnId("");
        refreshReader();
      });
    });
    onCleanup(() => {
      window.clearTimeout(refetchTimer);
      stopSessions();
      stopEvents();
      stopReset?.();
    });
  });
  createEffect(
    on(
      () => live?.status(),
      (status, previous) => {
        if (status === "live" && previous === "down") refreshReader();
      },
      { defer: true },
    ),
  );

  createEffect(() => {
    const targetId = props.targetEventId;
    if (!targetId) focusedExactTarget = "";
    if (!targetId || transcript.loading || (compactReader() && (chooserVisible() || detailsOpen())))
      return;
    const exists = timelineEvents().some((event) => event.id === targetId);
    if (!exists) return;
    queueMicrotask(() => {
      const element = document.getElementById(`event-${targetId}`);
      if (compactReader() && element) {
        // Scroll the reader itself; scrolling every ancestor can hide the mobile app controls.
        const pane = element.closest<HTMLElement>(".timeline-pane");
        if (pane) {
          const target = element.getBoundingClientRect();
          const bounds = pane.getBoundingClientRect();
          pane.scrollTop +=
            target.top - bounds.top - Math.max(0, (pane.clientHeight - target.height) / 2);
        }
        if (focusedExactTarget !== targetId) element.focus({ preventScroll: true });
        focusedExactTarget = targetId;
      } else element?.scrollIntoView?.({ block: "center" });
    });
  });

  function selectSession(id: string) {
    if (compactReader()) {
      setChooserOpen(false);
      setDetailsOpen(false);
      queueMicrotask(() => readerHeading?.focus({ preventScroll: true }));
    }
    if (props.onSelectSession) {
      props.onSelectSession(id);
      return;
    }
    navigate(`/sessions/${encodeURIComponent(id)}`);
  }

  function updateTranscriptQuery(value: string) {
    setTranscriptQuery(value);
    setActiveSearchTurnId("");
    setOlderEventsError("");
  }

  function moveTranscriptSearch(direction: -1 | 1) {
    const turns = matchingPromptTurns();
    if (transcriptLoading() || !transcriptQuery().trim() || !turns.length) return;
    const current = activeSearchPosition();
    const next =
      current < 0
        ? direction > 0
          ? 0
          : turns.length - 1
        : (current + direction + turns.length) % turns.length;
    const turn = turns[next];
    setActiveSearchTurnId(turn.id);
    queueMicrotask(() => {
      document.getElementById(turn.id)?.scrollIntoView?.({
        block: "center",
        behavior: prefersReducedMotion() ? "auto" : "smooth",
      });
    });
  }

  async function loadOlderTranscriptEvents() {
    const current = transcriptData();
    const before = current.nextBefore;
    if (!before || olderEventsLoading() || transcriptLoading()) return;

    const generation = transcriptGeneration;
    const sessionId = current.sessionId;
    const query = current.query;
    const human = current.humanOnly;
    setOlderEventsLoading(true);
    setOlderEventsError("");
    try {
      const response = await getSessionTranscript(sessionId, {
        limit: TRANSCRIPT_PAGE_LIMIT,
        before,
        q: query || undefined,
        humanOnly: human || undefined,
      });
      if (
        generation !== transcriptGeneration ||
        selectedId() !== sessionId ||
        debouncedTranscriptQuery() !== query
      )
        return;
      const olderEvents = response.events.map((event) => withHumanText(event, human));
      mutateTranscript((latest) => {
        if (latest.sessionId !== sessionId || latest.query !== query || latest.humanOnly !== human)
          return latest;
        const merged = mergeSessionEvents(latest.events, olderEvents);
        return {
          ...latest,
          available: latest.available && response.available,
          complete: latest.complete && response.complete,
          reason: latest.reason ?? response.reason,
          limit: response.limit,
          count: merged.length,
          events: merged,
          nextBefore: response.nextBefore,
        };
      });
    } catch {
      if (
        generation === transcriptGeneration &&
        selectedId() === sessionId &&
        debouncedTranscriptQuery() === query
      ) {
        setOlderEventsError("Older transcript events could not be loaded. Try again.");
      }
    } finally {
      if (generation === transcriptGeneration) setOlderEventsLoading(false);
    }
  }

  return (
    <>
      <section class="sessions-page" ref={pageElement} onKeyDown={handleDisclosureEscape}>
        <div class="session-mobile-controls" hidden={!compactReader()}>
          <button
            type="button"
            ref={chooserButton}
            aria-controls={chooserId}
            aria-expanded={chooserVisible()}
            disabled={!selectedSession()}
            onClick={toggleChooser}
          >
            Sessions <span>{sessions().length.toLocaleString()}</span>
          </button>
          <button
            type="button"
            ref={detailsButton}
            aria-controls={`${titleDetailsId} ${summaryDetailsId}`}
            aria-expanded={detailsOpen()}
            disabled={!selectedSession() || chooserVisible()}
            onClick={() => setDetailsOpen((open) => !open)}
          >
            Session details
          </button>
        </div>
        <aside
          id={chooserId}
          class="session-list-pane"
          aria-label="Session chooser"
          hidden={!chooserVisible()}
        >
          <div class="pane-head">
            <span class="eyebrow">sessions</span>
            <span>
              {filteredSessions().length.toLocaleString()} / {sessions().length.toLocaleString()}
            </span>
          </div>
          <div class="session-filter-bar">
            <label for="session-filter">Find sessions</label>
            <div class="session-filter-row">
              <input
                id="session-filter"
                ref={sessionSearch}
                onFocus={() => {
                  if (compactReader()) setChooserOpen(true);
                }}
                value={sessionFilter()}
                onInput={(event) => setSessionFilter(event.currentTarget.value)}
                placeholder="source:codex project:sba-agentic prompt text"
                autocomplete="off"
              />
              <button
                type="button"
                aria-label="Clear session filters"
                disabled={!sessionFilter().trim()}
                onClick={() => setSessionFilter("")}
              >
                Clear
              </button>
            </div>
          </div>
          <Show
            when={filteredSessions().length}
            fallback={
              <p class="empty-state session-list-empty">
                {allSessions.loading || projectSessions.loading
                  ? "Loading sessions..."
                  : sessionFilter().trim() || sourceFilter.key() || props.project
                    ? "No sessions match the active filters."
                    : humanOnly()
                      ? "No sessions with human turns recorded yet."
                      : "No sessions recorded yet."}
              </p>
            }
          >
            <div class="session-rows">
              <For each={filteredSessions()}>
                {(session) => (
                  <div class="session-row-block">
                    <div class="session-row-line">
                      <button
                        type="button"
                        classList={{
                          "session-row": true,
                          "session-row--active": session.id === selectedId(),
                        }}
                        onClick={() => selectSession(session.id)}
                      >
                        <SourceDot source={session.source} />
                        <span class="session-row-main">
                          <strong>
                            {leadLine(session.firstHumanTurn) ||
                              session.title ||
                              session.clientSessionId}
                          </strong>
                          <Show
                            when={
                              leadLine(session.firstHumanTurn) &&
                              distinctTitle(session.title, session.firstHumanTurn)
                            }
                          >
                            {(secondary) => (
                              <span class="session-row-title-secondary">{secondary()}</span>
                            )}
                          </Show>
                          <small>
                            {session.eventCount.toLocaleString()} · {truncatePath(session.cwd)} ·{" "}
                            {timeAgo(session.lastSeenAt)}
                          </small>
                        </span>
                      </button>
                      <Show when={(childCountsData()[session.id] ?? 0) > 0}>
                        <button
                          type="button"
                          class="session-expander"
                          aria-expanded={isExpanded(session.id)}
                          aria-label={`Toggle ${childCountsData()[session.id]} subagent sessions`}
                          onClick={() => toggleExpanded(session.id)}
                        >
                          <span aria-hidden="true">{isExpanded(session.id) ? "−" : "+"}</span>
                          {childCountsData()[session.id]}
                        </button>
                      </Show>
                    </div>
                    <Show when={isExpanded(session.id)}>
                      <SessionChildRows parentId={session.id} onSelect={selectSession} />
                    </Show>
                  </div>
                )}
              </For>
            </div>
          </Show>
        </aside>

        <section class="session-detail-pane" hidden={compactReader() && chooserVisible()}>
          <Show
            when={selectedSession()}
            fallback={
              <div class="empty-detail">
                <p class="eyebrow">session detail</p>
                <h1>Select a session</h1>
                <p>Use the list or ⌘K to jump into a recorded trace.</p>
              </div>
            }
          >
            {(session) => (
              <>
                <header class="detail-header">
                  <div class="detail-title-block">
                    <div class="detail-kicker">
                      <SourceDot source={session().source} label />
                      <span>{session().eventCount.toLocaleString()} recorded</span>
                      <span>{timeAgo(session().lastSeenAt)}</span>
                    </div>
                    <h1
                      ref={readerHeading}
                      tabIndex={-1}
                      title={session().title || session().clientSessionId}
                    >
                      {session().title || session().clientSessionId}
                    </h1>
                    <div
                      id={titleDetailsId}
                      class="session-title-extra"
                      hidden={compactReader() && !detailsOpen()}
                    >
                      <p>{truncatePath(session().cwd)}</p>
                      <Show when={session().firstHumanTurn?.trim()}>
                        {(turn) => <FirstTurnLead text={turn()} />}
                      </Show>
                    </div>
                  </div>
                  <div class="detail-summary">
                    <div
                      id={summaryDetailsId}
                      class="session-summary-content"
                      hidden={compactReader() && !detailsOpen()}
                    >
                      <span class="eyebrow">summary</span>
                      <p>{session().summary || "No summary captured yet."}</p>
                      <small>
                        {formatDate(session().startedAt)} → {formatDate(session().lastSeenAt)}
                      </small>
                    </div>
                    <Show when={memoryEventCount() > 0}>
                      <label class="reading-toggle">
                        <input
                          type="checkbox"
                          checked={showMemoryEvents()}
                          onChange={(event) => setShowMemoryEvents(event.currentTarget.checked)}
                        />
                        <span>Show memory events</span>
                        <span aria-hidden="true" class="reading-toggle-count">
                          {memoryEventCount().toLocaleString()}
                        </span>
                      </label>
                    </Show>
                  </div>
                </header>

                <Show when={lineageDagData()}>
                  {(dag) => (
                    <SessionLineage
                      dag={dag()}
                      currentSessionId={selectedId()}
                      onSelectSession={selectSession}
                    />
                  )}
                </Show>

                <div
                  class="session-transcript-search"
                  role="search"
                  aria-label="Search this session transcript"
                >
                  <label for="session-transcript-search">
                    <span>Find in session</span>
                    <input
                      id="session-transcript-search"
                      type="search"
                      value={transcriptQuery()}
                      placeholder="Messages, tools, inputs, outputs"
                      autocomplete="off"
                      onInput={(event) => updateTranscriptQuery(event.currentTarget.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          event.preventDefault();
                          moveTranscriptSearch(event.shiftKey ? -1 : 1);
                        } else if (event.key === "Escape" && transcriptQuery()) {
                          event.preventDefault();
                          updateTranscriptQuery("");
                        }
                      }}
                    />
                  </label>
                  <output for="session-transcript-search" aria-live="polite">
                    <Show
                      when={transcriptQuery().trim()}
                      fallback="Search user and agent messages plus tool activity"
                    >
                      <Show when={!transcriptLoading()} fallback="Searching…">
                        <Show when={matchingPromptTurns().length} fallback="No matching turns">
                          {activeSearchPosition() >= 0
                            ? `${activeSearchPosition() + 1} of ${matchingPromptTurns().length} matching ${matchingPromptTurns().length === 1 ? "turn" : "turns"}`
                            : `${matchingPromptTurns().length} matching ${matchingPromptTurns().length === 1 ? "turn" : "turns"}`}
                        </Show>
                      </Show>
                    </Show>
                  </output>
                  <div class="session-transcript-search-actions">
                    <button
                      type="button"
                      aria-label="Previous transcript match"
                      disabled={
                        transcriptLoading() ||
                        !transcriptQuery().trim() ||
                        !matchingPromptTurns().length
                      }
                      onClick={() => moveTranscriptSearch(-1)}
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      aria-label="Next transcript match"
                      disabled={
                        transcriptLoading() ||
                        !transcriptQuery().trim() ||
                        !matchingPromptTurns().length
                      }
                      onClick={() => moveTranscriptSearch(1)}
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      aria-label="Clear transcript search"
                      disabled={!transcriptQuery()}
                      onClick={() => updateTranscriptQuery("")}
                    >
                      Clear
                    </button>
                  </div>
                </div>

                <Show when={transcriptStatusNote()}>
                  {(note) => (
                    <p class="session-transcript-status" role="status">
                      {note()}
                    </p>
                  )}
                </Show>

                <div class="detail-body">
                  <div class="timeline-pane">
                    <Show
                      when={!transcriptLoading()}
                      fallback={<p class="empty-state">Loading transcript…</p>}
                    >
                      <Show
                        when={
                          transcriptData().nextBefore &&
                          transcriptData().query === debouncedTranscriptQuery()
                        }
                      >
                        <div class="transcript-page-controls">
                          <button
                            type="button"
                            disabled={olderEventsLoading()}
                            onClick={() => void loadOlderTranscriptEvents()}
                          >
                            {olderEventsLoading()
                              ? "Loading older…"
                              : transcriptData().query
                                ? "Load older matches"
                                : "Load older events"}
                          </button>
                          <span>{transcriptData().events.length.toLocaleString()} loaded</span>
                        </div>
                      </Show>
                      <Show when={olderEventsError()}>
                        {(message) => (
                          <p class="transcript-page-error" role="status">
                            {message()}
                          </p>
                        )}
                      </Show>
                      <Show
                        when={displayedPromptTurns().length}
                        fallback={
                          <p class="empty-state transcript-empty-state">
                            {transcriptQuery().trim()
                              ? "No transcript turns match this search."
                              : "No readable transcript events were captured."}
                          </p>
                        }
                      >
                        <For each={displayedPromptTurns()}>
                          {(turn) => (
                            <section
                              id={turn.id}
                              classList={{
                                "prompt-turn": true,
                                "conversation-turn": true,
                                "prompt-turn--preamble": !turn.prompt,
                                "prompt-turn--search-active": activeSearchTurnId() === turn.id,
                              }}
                            >
                              <For each={turn.events}>
                                {(event) => (
                                  <div
                                    id={`event-${event.id}`}
                                    tabIndex={props.targetEventId === event.id ? -1 : undefined}
                                    classList={{
                                      "event-flow-row": true,
                                      "event-flow-row--target": props.targetEventId === event.id,
                                    }}
                                  >
                                    <Show
                                      when={conversationRole(event)}
                                      fallback={<EventRenderer event={event} />}
                                    >
                                      {(role) => (
                                        <ConversationMessage event={event} role={role()} />
                                      )}
                                    </Show>
                                  </div>
                                )}
                              </For>
                              <Show
                                when={
                                  turn.prompt &&
                                  !humanOnly() &&
                                  !turn.events.some(
                                    (event) => conversationRole(event) === "assistant",
                                  )
                                }
                              >
                                <p class="conversation-response-missing">
                                  Agent response not captured for this turn.
                                </p>
                              </Show>
                            </section>
                          )}
                        </For>
                      </Show>
                    </Show>
                  </div>
                  <ConversationNavigator turns={navigatorTurns()} />
                </div>
              </>
            )}
          </Show>
        </section>
      </section>
    </>
  );
}

async function mergeExactTarget(
  events: AgentEvent[],
  sessionId: string,
  targetEventId: string,
): Promise<AgentEvent[]> {
  if (events.some((event) => event.id === targetEventId)) return events;
  try {
    const target = await getEvent(targetEventId);
    return target.sessionId === sessionId ? mergeSessionEvents([target], events) : events;
  } catch {
    return events;
  }
}

function SessionChildRows(props: { parentId: string; onSelect: (id: string) => void }) {
  const [links] = createResource(
    () => props.parentId,
    async (parentId) => (await getSessionLinks(parentId)).children,
    { initialValue: [] as SessionLink[] },
  );

  return (
    <div class="session-children" role="group" aria-label="Subagent sessions">
      <Show
        when={!links.loading}
        fallback={<p class="session-children-loading">Loading subagents…</p>}
      >
        <For each={links()}>
          {(link) => (
            <button
              type="button"
              class="session-row session-row--child"
              onClick={() => props.onSelect(link.session.id)}
            >
              <SourceDot source={link.session.source} />
              <span class="session-row-main">
                <strong>
                  {leadLine(link.session.firstHumanTurn) ||
                    link.session.title.trim() ||
                    link.session.id}
                </strong>
                <Show
                  when={
                    leadLine(link.session.firstHumanTurn) &&
                    distinctTitle(link.session.title, link.session.firstHumanTurn)
                  }
                >
                  {(secondary) => <span class="session-row-title-secondary">{secondary()}</span>}
                </Show>
                <small>
                  <span class="agent-type-badge">{agentTypeLabel(link)}</span>
                </small>
              </span>
            </button>
          )}
        </For>
      </Show>
    </div>
  );
}

function FirstTurnLead(props: { text: string }) {
  const [expanded, setExpanded] = createSignal(false);
  const textId = createUniqueId();
  const preview = createMemo(() => readerTextPreview(props.text, { chars: 280, nonemptyLines: 4 }));
  return (
    <blockquote class="detail-first-turn">
      <span class="detail-first-turn-label">First turn</span>
      <p
        id={textId}
        classList={{
          "detail-first-turn-text": true,
          "detail-first-turn-text--clamped": preview().truncated && !expanded(),
        }}
      >
        {expanded() ? props.text : preview().text}
      </p>
      <Show when={preview().truncated}>
        <button
          type="button"
          class="detail-first-turn-toggle"
          aria-expanded={expanded()}
          aria-controls={textId}
          onClick={() => setExpanded((open) => !open)}
        >
          {expanded() ? "Show less" : "Show all"}
        </button>
      </Show>
    </blockquote>
  );
}

function agentTypeLabel(link: SessionLink): string {
  return link.session.title.trim() || "subagent";
}

function filterSessions<
  T extends {
    source: string;
    title?: string | null;
    firstHumanTurn?: string | null;
    clientSessionId: string;
    cwd?: string | null;
  },
>(sessions: T[], query: string): T[] {
  const parsed = parseQuery(query);
  const lower = (values: string[] | undefined) =>
    (values ?? []).map((value) => value.toLowerCase());
  const sourceFacets = lower(parsed.facets.source);
  const projectFacets = lower(parsed.facets.project);
  const excludedSourceFacets = lower(parsed.excludeFacets.source);
  const excludedProjectFacets = lower(parsed.excludeFacets.project);
  const textTerms = parsed.freeTerms.map((term) => term.toLowerCase());

  return sessions.filter((session) => {
    const source = normalizeSessionText(session.source);
    const cwd = normalizeSessionText(session.cwd);
    if (sourceFacets.length && !sourceFacets.some((facet) => source.includes(facet))) return false;
    if (excludedSourceFacets.some((facet) => source.includes(facet))) return false;
    if (projectFacets.length && !projectFacets.some((facet) => cwd.includes(facet))) return false;
    if (excludedProjectFacets.some((facet) => cwd.includes(facet))) return false;

    if (!textTerms.length) return true;
    const haystack = [
      session.title,
      session.firstHumanTurn,
      session.clientSessionId,
      session.cwd,
      session.source,
    ]
      .map(normalizeSessionText)
      .join(" ");
    return textTerms.every((term) => haystack.includes(term));
  });
}

function normalizeSessionText(value: unknown): string {
  return String(value ?? "").toLowerCase();
}

function ConversationMessage(props: { event: AgentEvent; role: "user" | "assistant" }) {
  const author = () => (props.role === "user" ? "You" : sourceLabel(props.event.source));

  return (
    <article
      class={`conversation-message conversation-message--${props.role}`}
      style={{ "--conversation-source": sourceColor(props.event.source) }}
    >
      <header class="conversation-message-header">
        <span class="conversation-message-author">
          <Show
            when={props.role === "assistant"}
            fallback={
              <span class="conversation-message-you" aria-hidden="true">
                Y
              </span>
            }
          >
            <SourceDot source={props.event.source} />
          </Show>
          <strong>{author()}</strong>
          <small>{props.role === "user" ? "prompt" : "agent response"}</small>
        </span>
        <time datetime={props.event.observedAt} title={formatDate(props.event.observedAt)}>
          {timeAgo(props.event.observedAt)}
        </time>
      </header>
      <Show
        when={props.event.text?.trim()}
        fallback={<p class="conversation-message-empty">Message text was not captured.</p>}
      >
        {(text) =>
          humanOnly() && props.event.humanText ? (
            <div class="human-verbatim">
              <ReaderText text={text()} />
            </div>
          ) : (
            <ReaderText text={text()} />
          )
        }
      </Show>
    </article>
  );
}

function groupPromptTurns(events: AgentEvent[]): PromptTurn[] {
  const turns: PromptTurn[] = [];
  for (const event of events) {
    if (conversationRole(event) === "user") {
      const current = turns[turns.length - 1];
      if (current && isDuplicatePrompt(current, event)) continue;
      turns.push({ id: `prompt-${event.id}`, prompt: event, events: [event] });
      continue;
    }

    const current = turns[turns.length - 1];
    if (current) {
      appendUniqueEvent(current, event);
    } else {
      turns.push({ id: `prompt-preamble-${event.id}`, prompt: null, events: [event] });
    }
  }
  return turns;
}

function isDuplicatePrompt(turn: PromptTurn, event: AgentEvent): boolean {
  const prompt = turn.prompt;
  if (!prompt || turn.events.some((candidate) => conversationRole(candidate) === "assistant"))
    return false;

  const promptText = normalizedConversationText(prompt.text);
  const eventText = normalizedConversationText(event.text);
  if (!promptText || promptText !== eventText) return false;

  const promptTurnId = prompt.turnId?.trim();
  const eventTurnId = event.turnId?.trim();
  if (promptTurnId && eventTurnId && promptTurnId === eventTurnId) return true;

  const promptTime = Date.parse(prompt.observedAt);
  const eventTime = Date.parse(event.observedAt);
  return (
    Number.isFinite(promptTime) &&
    Number.isFinite(eventTime) &&
    Math.abs(eventTime - promptTime) <= DUPLICATE_PROMPT_WINDOW_MS
  );
}

function appendUniqueEvent(turn: PromptTurn, event: AgentEvent) {
  if (turn.events.some((candidate) => candidate.id === event.id)) return;
  turn.events.push(event);
}

function normalizedConversationText(value: string | null | undefined): string {
  return String(value ?? "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return "Unknown time";
  const date = new Date(iso);
  if (Number.isNaN(date.valueOf())) return "Unknown time";
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(date);
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}
