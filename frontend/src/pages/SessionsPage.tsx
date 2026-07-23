import { useNavigate, useParams, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, Show, useContext } from "solid-js";
import ConversationNavigator, { type ConversationNavigatorTurn } from "../components/ConversationNavigator";
import DagView from "../components/DagView";
import SourceDot from "../components/SourceDot";
import SteerBox from "../components/SteerBox";
import { EventRenderer, ReaderText } from "../components/events/EventRow";
import {
  getProjectSessions,
  getSession,
  getSessionChildCounts,
  getSessionDag,
  getSessionEvents,
  getSessionLinks,
  getSessions,
  getTaskDag,
  type AgentEvent,
  type AgentSession,
  type ProjectSummary,
  type SessionLink,
} from "../lib/api";
import { sourceColor, sourceLabel, timeAgo, truncatePath } from "../lib/format";
import { projectMatchesSession } from "../lib/projects";
import { parseQuery } from "../lib/query";
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

const DUPLICATE_PROMPT_WINDOW_MS = 2 * 60 * 1_000;

export default function SessionsPage(props: SessionsPageProps = {}) {
  const params = useParams<{ sessionId?: string }>();
  const [searchParams] = useSearchParams<{ task?: string }>();
  const navigate = useNavigate();
  const live = useContext(LiveStoreContext);
  const [sessionFilter, setSessionFilter] = createSignal("");
  const [showMemoryEvents, setShowMemoryEvents] = createSignal(false);
  const [dagExpanded, setDagExpanded] = createSignal(false);
  const [taskContext, { refetch: refetchTaskContext }] = createResource(
    () => searchParams.task,
    (taskId) => getTaskDag(taskId),
  );
  const [allSessions] = createResource(
    () => (props.project ? null : sourceFilter.key()),
    async () => sourceFilter.matches(await getSessions(2_000)),
    { initialValue: [] as AgentSession[] },
  );
  const [projectSessions] = createResource(
    () => props.project?.projectKey,
    async (projectKey): Promise<ProjectSessionResult | null> =>
      projectKey
        ? {
            projectKey,
            sessions: (await getProjectSessions(projectKey, 2_000)).filter((s) => !s.spawnedBy),
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
    if (requestedId && scopedSessions.some((session) => session.id === requestedId)) return requestedId;
    if (requestedId && requestedSession.loading) return "";
    return props.defaultToFirst ? scopedSessions[0]?.id ?? "" : "";
  });
  const selectedSession = createMemo(() => filteredSessions().find((session) => session.id === selectedId()));
  const taskNode = createMemo(() => taskContext()?.nodes.find((node) => (
    node.type === "task" && node.id === searchParams.task
  )));
  const specNode = createMemo(() => taskContext()?.nodes.find((node) => node.type === "spec"));
  const [sessionDag] = createResource(
    () => (dagExpanded() && selectedId() ? selectedId() : undefined),
    (sessionId) => getSessionDag(sessionId),
  );
  const railSessionIds = createMemo(() => sessions().map((session) => session.id));
  const [childCounts, { refetch: refetchChildCounts }] = createResource(
    () => railSessionIds().join(","),
    async () => getSessionChildCounts(railSessionIds()),
    { initialValue: {} as Record<string, number> },
  );
  const [expandedParents, setExpandedParents] = createSignal<ReadonlySet<string>>(new Set<string>());
  const isExpanded = (id: string) => expandedParents().has(id);
  const toggleExpanded = (id: string) => {
    setExpandedParents((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };
  const [events, { refetch: refetchEvents }] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
    initialValue: [] as AgentEvent[],
  });
  const timelineEvents = createMemo(() => [...events()].reverse());
  const visibleEvents = createMemo(() =>
    timelineEvents().filter(
      (event) =>
        event.id === props.targetEventId ||
        isPrimaryReaderEvent(event) ||
        (showMemoryEvents() && isMemoryEvent(event)),
    ),
  );
  const promptTurns = createMemo(() => groupPromptTurns(visibleEvents()));
  const memoryEventCount = createMemo(() => timelineEvents().filter(isMemoryEvent).length);
  const navigatorTurns = createMemo<ConversationNavigatorTurn[]>(() =>
    promptTurns().flatMap((turn) => turn.prompt
      ? [{
          id: turn.id,
          prompt: turn.prompt,
          responses: turn.events.filter((event) => conversationRole(event) === "assistant"),
        }]
      : []),
  );

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
      }, 180);
    });
    onCleanup(() => {
      window.clearTimeout(refetchTimer);
      unsubscribe();
    });
  });

  createEffect(() => {
    const targetId = props.targetEventId;
    if (!targetId || events.loading) return;
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

            <SteerBox taskId={taskId()} actor="session" enabled={taskNode()?.status === "in_progress"} />

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
          <Show when={filteredSessions().length} fallback={<p class="empty-state session-list-empty">No sessions match the active filters.</p>}>
            <div class="session-rows">
              <For each={filteredSessions()}>
                {(session) => (
                  <div class="session-row-block">
                    <div class="session-row-line">
                      <button
                        type="button"
                        classList={{ "session-row": true, "session-row--active": session.id === selectedId() }}
                        onClick={() => selectSession(session.id)}
                      >
                        <SourceDot source={session.source} />
                        <span class="session-row-main">
                          <strong>{session.title || session.clientSessionId}</strong>
                          <small>
                            {session.eventCount.toLocaleString()} · {truncatePath(session.cwd)} · {timeAgo(session.lastSeenAt)}
                          </small>
                        </span>
                      </button>
                      <Show when={(childCounts()[session.id] ?? 0) > 0}>
                        <button
                          type="button"
                          class="session-expander"
                          aria-expanded={isExpanded(session.id)}
                          aria-label={`Toggle ${childCounts()[session.id]} subagent sessions`}
                          onClick={() => toggleExpanded(session.id)}
                        >
                          <span aria-hidden="true">{isExpanded(session.id) ? "−" : "+"}</span>
                          {childCounts()[session.id]}
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
                      <span>{session().eventCount.toLocaleString()} events</span>
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

                <div class="detail-body">
                  <div class="timeline-pane">
                    <Show when={!events.loading} fallback={<p class="empty-state">Loading events...</p>}>
                      <For each={promptTurns()}>
                        {(turn) => (
                          <section
                            id={turn.id}
                            classList={{
                              "prompt-turn": true,
                              "conversation-turn": true,
                              "prompt-turn--preamble": !turn.prompt,
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
                                    {(role) => <ConversationMessage event={event} role={role()} />}
                                  </Show>
                                </div>
                              )}
                            </For>
                            <Show when={turn.prompt && !turn.events.some((event) => conversationRole(event) === "assistant")}>
                              <p class="conversation-response-missing">Agent response not captured for this turn.</p>
                            </Show>
                          </section>
                        )}
                      </For>
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

function SessionChildRows(props: { parentId: string; onSelect: (id: string) => void }) {
  const [links] = createResource(
    () => props.parentId,
    async (parentId) => (await getSessionLinks(parentId)).children,
    { initialValue: [] as SessionLink[] },
  );

  return (
    <div class="session-children" role="group" aria-label="Subagent sessions">
      <Show when={!links.loading} fallback={<p class="session-children-loading">Loading subagents…</p>}>
        <For each={links()}>
          {(link) => (
            <button type="button" class="session-row session-row--child" onClick={() => props.onSelect(link.session.id)}>
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

function filterSessions<T extends { source: string; title?: string | null; clientSessionId: string; cwd?: string | null }>(
  sessions: T[],
  query: string,
): T[] {
  const parsed = parseQuery(query);
  const sourceFacet = parsed.facets.source?.toLowerCase();
  const projectFacet = parsed.facets.project?.toLowerCase();
  const excludedSourceFacet = parsed.excludeFacets.source?.toLowerCase();
  const excludedProjectFacet = parsed.excludeFacets.project?.toLowerCase();
  const textTerms = parsed.text.map((term) => term.toLowerCase());

  return sessions.filter((session) => {
    if (sourceFacet && !normalizeSessionText(session.source).includes(sourceFacet)) return false;
    if (excludedSourceFacet && normalizeSessionText(session.source).includes(excludedSourceFacet)) return false;
    if (projectFacet && !normalizeSessionText(session.cwd).includes(projectFacet)) return false;
    if (excludedProjectFacet && normalizeSessionText(session.cwd).includes(excludedProjectFacet)) return false;

    if (!textTerms.length) return true;
    const haystack = [
      session.title,
      session.clientSessionId,
      session.cwd,
      session.source,
    ].map(normalizeSessionText).join(" ");
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
  const author = () => props.role === "user" ? "You" : sourceLabel(props.event.source);

  return (
    <article
      class={`conversation-message conversation-message--${props.role}`}
      style={{ "--conversation-source": sourceColor(props.event.source) }}
    >
      <header class="conversation-message-header">
        <span class="conversation-message-author">
          <Show
            when={props.role === "assistant"}
            fallback={<span class="conversation-message-you" aria-hidden="true">Y</span>}
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
      <Show when={props.event.text?.trim()} fallback={<p class="conversation-message-empty">Message text was not captured.</p>}>
        {(text) => <ReaderText text={text()} />}
      </Show>
    </article>
  );
}

function isPromptEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return normalizedRole(event) === "user"
    || type === "userpromptsubmit"
    || type === "beforesubmitprompt";
}

function isAssistantEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  if (normalizedRole(event) === "assistant") return Boolean(event.text?.trim());
  return Boolean(event.text?.trim()) && (
    type === "assistantmessage"
    || type === "agentmessage"
    || type === "agentresponse"
    || type === "finalresponse"
    || type === "stop"
  );
}

function isMemoryEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return type === "decision" || type === "observation" || type === "handoff";
}

function isToolEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return Boolean(
    event.toolName
    || event.toolInputJson
    || event.toolOutputJson
    || normalizedRole(event) === "tool"
    || type.includes("tooluse")
    || type.includes("toolresult")
    || type.includes("tooloutput"),
  );
}

function isPrimaryReaderEvent(event: AgentEvent): boolean {
  return !isMemoryEvent(event) && !isToolEvent(event) && (isPromptEvent(event) || isAssistantEvent(event));
}

function groupPromptTurns(events: AgentEvent[]): PromptTurn[] {
  const turns: PromptTurn[] = [];
  for (const event of events) {
    if (isPromptEvent(event)) {
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
  if (!prompt || turn.events.some((candidate) => conversationRole(candidate) === "assistant")) return false;

  const promptText = normalizedConversationText(prompt.text);
  const eventText = normalizedConversationText(event.text);
  if (!promptText || promptText !== eventText) return false;

  const promptTurnId = prompt.turnId?.trim();
  const eventTurnId = event.turnId?.trim();
  if (promptTurnId && eventTurnId && promptTurnId === eventTurnId) return true;

  const promptTime = Date.parse(prompt.observedAt);
  const eventTime = Date.parse(event.observedAt);
  return Number.isFinite(promptTime)
    && Number.isFinite(eventTime)
    && Math.abs(eventTime - promptTime) <= DUPLICATE_PROMPT_WINDOW_MS;
}

function appendUniqueEvent(turn: PromptTurn, event: AgentEvent) {
  const identity = conversationIdentity(event);
  if (identity && turn.events.some((candidate) => conversationIdentity(candidate) === identity)) return;
  turn.events.push(event);
}

function conversationIdentity(event: AgentEvent): string | null {
  const role = conversationRole(event);
  const text = event.text?.replace(/\s+/g, " ").trim();
  return role && text ? `${role}:${text}` : null;
}

function normalizedConversationText(value: string | null | undefined): string {
  return String(value ?? "").replace(/\s+/g, " ").trim().toLowerCase();
}

function conversationRole(event: AgentEvent): "user" | "assistant" | null {
  if (isMemoryEvent(event) || isToolEvent(event)) return null;
  if (isPromptEvent(event)) return "user";
  if (isAssistantEvent(event)) return "assistant";
  return null;
}

function normalizedEventType(event: AgentEvent): string {
  return String(event.eventType ?? "").replace(/[^a-z0-9]/gi, "").toLowerCase();
}

function normalizedRole(event: AgentEvent): string {
  return String(event.role ?? "").trim().toLowerCase();
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
