import { useNavigate, useParams, useSearchParams } from "@solidjs/router";
import {
  createEffect,
  createMemo,
  createResource,
  createSignal,
  For,
  onCleanup,
  Show,
  useContext,
} from "solid-js";
import ConversationNavigator, {
  type ConversationNavigatorTurn,
} from "../components/ConversationNavigator";
import DagView from "../components/DagView";
import SessionLineage from "../components/SessionLineage";
import SourceDot from "../components/SourceDot";
import SteerBox from "../components/SteerBox";
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
  getTaskDag,
  type AgentEvent,
  type AgentSession,
  type ProjectSummary,
  type SessionLink,
  type SessionTranscriptResponse,
} from "../lib/api";
import { sourceColor, sourceLabel, timeAgo, truncatePath } from "../lib/format";
import { projectMatchesSession } from "../lib/projects";
import { parseQuery } from "../lib/query";
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
};

type SessionTranscriptState = SessionTranscriptResponse & {
  query: string;
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
  legacyFallback: false,
};

export default function SessionsPage(props: SessionsPageProps = {}) {
  const params = useParams<{ sessionId?: string }>();
  const [searchParams] = useSearchParams<{ task?: string }>();
  const navigate = useNavigate();
  const live = useContext(LiveStoreContext);
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
  const [dagExpanded, setDagExpanded] = createSignal(false);
  const [taskContext, { refetch: refetchTaskContext }] = createResource(
    () => searchParams.task,
    (taskId) => getTaskDag(taskId),
  );
  const [allSessions] = createResource(
    () => (props.project ? null : sourceFilter.key()),
    async () => sourceFilter.matches(await getSessions(RECENT_SESSION_LIMIT)),
    { initialValue: [] as AgentSession[] },
  );
  const [projectSessions] = createResource(
    () => props.project?.projectKey,
    async (projectKey): Promise<ProjectSessionResult | null> =>
      projectKey
        ? {
            projectKey,
            sessions: (await getProjectSessions(projectKey, RECENT_SESSION_LIMIT)).filter(
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
  const [requestedSession] = createResource(
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
    const listed = props.project ? scopedProjectSessions() : allSessions();
    const requested = requestedSession();
    if (!requested || listed.some((session) => session.id === requested.id)) return listed;
    if (props.project && !projectMatchesSession(props.project, requested)) return listed;
    if (!sourceFilter.matches([requested]).length) return listed;
    return [requested, ...listed];
  });
  const filteredSessions = createMemo(() => filterSessions(sessions(), sessionFilter()));
  const selectedId = createMemo(() => {
    const scopedSessions = filteredSessions();
    const requestedId = requestedSessionId();
    if (requestedId && scopedSessions.some((session) => session.id === requestedId))
      return requestedId;
    if (requestedId && requestedSession.loading) return "";
    return props.defaultToFirst ? (scopedSessions[0]?.id ?? "") : "";
  });
  const selectedSession = createMemo(() =>
    filteredSessions().find((session) => session.id === selectedId()),
  );
  const taskNode = createMemo(() =>
    taskContext()?.nodes.find((node) => node.type === "task" && node.id === searchParams.task),
  );
  const specNode = createMemo(() => taskContext()?.nodes.find((node) => node.type === "spec"));
  const [sessionDag] = createResource(
    () => (dagExpanded() && selectedId() ? selectedId() : undefined),
    (sessionId) => getSessionDag(sessionId),
  );
  const [lineageDag, { refetch: refetchLineageDag }] = createResource(
    () => (!searchParams.task && selectedId() ? selectedId() : undefined),
    (sessionId) => getSessionDag(sessionId),
  );
  const lineageDagData = createMemo(() => {
    if (searchParams.task || lineageDag.error) return null;
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
        ? { sessionId, targetEventId: props.targetEventId, query: debouncedTranscriptQuery() }
        : undefined;
    },
    async ({ sessionId, targetEventId, query }): Promise<SessionTranscriptState> => {
      try {
        const response = await getSessionTranscript(sessionId, {
          limit: TRANSCRIPT_PAGE_LIMIT,
          q: query || undefined,
        });
        const listed =
          !query && targetEventId
            ? await mergeExactTarget(response.events, sessionId, targetEventId)
            : response.events;
        return {
          ...response,
          count: listed.length,
          events: listed,
          query,
          legacyFallback: false,
        };
      } catch {
        const recorded = await getSessionEvents(sessionId, 2_000);
        const listed =
          !query && targetEventId
            ? await mergeExactTarget(recorded, sessionId, targetEventId)
            : recorded;
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

  createEffect(() => {
    if (!live || !selectedId()) return;
    let refetchTimer: number | undefined;
    const unsubscribe = live.onSessionUpdated((update) => {
      if (update.sessionId !== selectedId()) return;
      window.clearTimeout(refetchTimer);
      refetchTimer = window.setTimeout(() => {
        void refetchEvents();
        void refetchChildCounts();
        if (searchParams.task) void refetchTaskContext();
        if (!searchParams.task) void refetchLineageDag();
      }, 180);
    });
    onCleanup(() => {
      window.clearTimeout(refetchTimer);
      unsubscribe();
    });
  });

  createEffect(() => {
    const targetId = props.targetEventId;
    if (!targetId || transcript.loading) return;
    const exists = timelineEvents().some((event) => event.id === targetId);
    if (!exists) return;
    queueMicrotask(() => {
      const element = document.getElementById(`event-${targetId}`);
      element?.scrollIntoView?.({ block: "center" });
    });
  });

  function selectSession(id: string) {
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

    const sessionId = current.sessionId;
    const query = current.query;
    setOlderEventsLoading(true);
    setOlderEventsError("");
    try {
      const response = await getSessionTranscript(sessionId, {
        limit: TRANSCRIPT_PAGE_LIMIT,
        before,
        q: query || undefined,
      });
      if (selectedId() !== sessionId || debouncedTranscriptQuery() !== query) return;
      mutateTranscript((latest) => {
        if (latest.sessionId !== sessionId || latest.query !== query) return latest;
        const merged = mergeSessionEvents(latest.events, response.events);
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
      if (selectedId() === sessionId && debouncedTranscriptQuery() === query) {
        setOlderEventsError("Older transcript events could not be loaded. Try again.");
      }
    } finally {
      setOlderEventsLoading(false);
    }
  }

  return (
    <>
      <Show when={searchParams.task}>
        {(taskId) => (
          <header class="tendril-header">
            <div class="tendril-context">
              <p class="eyebrow">worker tendril</p>
              <div class="tendril-title-row">
                <div>
                  <span>Story</span>
                  <strong>{specNode()?.label ?? "Loading task context…"}</strong>
                  <Show when={taskNode()?.label}>{(label) => <small>{label()}</small>}</Show>
                </div>
                <Show when={taskNode()?.status}>
                  {(status) => (
                    <span class={`tendril-status tendril-status--${status()}`}>
                      {statusLabel(status())}
                    </span>
                  )}
                </Show>
              </div>
            </div>

            <SteerBox
              taskId={taskId()}
              actor="session"
              enabled={taskNode()?.status === "in_progress"}
            />

            <div class="tendril-dag-panel">
              <button
                type="button"
                class="tendril-dag-toggle"
                aria-expanded={dagExpanded()}
                aria-controls={`session-dag-${selectedId() || "pending"}`}
                onClick={() => setDagExpanded((expanded) => !expanded)}
              >
                <span aria-hidden="true">{dagExpanded() ? "−" : "+"}</span>
                {dagExpanded() ? "Hide session DAG" : "View session DAG"}
              </button>
              <Show when={dagExpanded()}>
                <div id={`session-dag-${selectedId() || "pending"}`} class="tendril-dag-body">
                  <Show when={sessionDag.loading}>
                    <p>Loading session DAG…</p>
                  </Show>
                  <Show when={sessionDag.error}>
                    <p class="tendril-dag-error">Session DAG could not be loaded.</p>
                  </Show>
                  <Show when={!sessionDag.loading && !sessionDag.error}>
                    <DagView
                      dag={sessionDag() ?? { nodes: [], edges: [] }}
                      currentSessionId={selectedId()}
                      currentTaskId={taskId()}
                    />
                  </Show>
                </div>
              </Show>
            </div>
          </header>
        )}
      </Show>

      <section class="sessions-page">
        <aside class="session-list-pane">
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
                {sessionFilter().trim() || sourceFilter.key() || props.project
                  ? "No sessions match the active filters."
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
                          <strong>{session.title || session.clientSessionId}</strong>
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

        <section class="session-detail-pane">
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
                    <h1 title={session().title || session().clientSessionId}>
                      {session().title || session().clientSessionId}
                    </h1>
                    <p>{truncatePath(session().cwd)}</p>
                  </div>
                  <div class="detail-summary">
                    <span class="eyebrow">summary</span>
                    <p>{session().summary || "No summary captured yet."}</p>
                    <small>
                      {formatDate(session().startedAt)} → {formatDate(session().lastSeenAt)}
                    </small>
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
                <strong>{link.session.title.trim() || link.session.id}</strong>
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

function agentTypeLabel(link: SessionLink): string {
  return link.session.title.trim() || "subagent";
}

function filterSessions<
  T extends { source: string; title?: string | null; clientSessionId: string; cwd?: string | null },
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
    const haystack = [session.title, session.clientSessionId, session.cwd, session.source]
      .map(normalizeSessionText)
      .join(" ");
    return textTerms.every((term) => haystack.includes(term));
  });
}

function normalizeSessionText(value: unknown): string {
  return String(value ?? "").toLowerCase();
}

function statusLabel(status: string): string {
  if (status === "in_progress" || status === "claimed") return "In progress";
  return status.charAt(0).toUpperCase() + status.slice(1);
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
        {(text) => <ReaderText text={text()} />}
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
