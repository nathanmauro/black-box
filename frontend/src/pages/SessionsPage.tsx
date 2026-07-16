import { useNavigate, useParams, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, Show, useContext } from "solid-js";
import DagView from "../components/DagView";
import SourceDot from "../components/SourceDot";
import SteerBox from "../components/SteerBox";
import { EventRenderer } from "../components/events/EventRow";
import {
  getProjectSessions,
  getSessionDag,
  getSessionEvents,
  getSessions,
  getTaskDag,
  type AgentEvent,
  type AgentSession,
  type ProjectSummary,
} from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
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
      projectKey ? { projectKey, sessions: await getProjectSessions(projectKey, 2_000) } : null,
    { initialValue: null as ProjectSessionResult | null },
  );
  const scopedProjectSessions = createMemo(() => {
    const projectKey = props.project?.projectKey;
    const result = projectSessions();
    if (!projectKey || result?.projectKey !== projectKey) return [];
    return sourceFilter.matches(result.sessions);
  });
  const sessions = createMemo(() => (props.project ? scopedProjectSessions() : allSessions()));
  const filteredSessions = createMemo(() => filterSessions(sessions(), sessionFilter()));
  const selectedId = createMemo(() => {
    const scopedSessions = filteredSessions();
    const requestedId = props.selectedSessionId || params.sessionId || "";
    if (requestedId && scopedSessions.some((session) => session.id === requestedId)) return requestedId;
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
  const [events] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
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
  const promptOutline = createMemo(() => timelineEvents().filter(isPromptEvent));

  createEffect(() => {
    if (!live || !searchParams.task) return;
    const unsubscribe = live.onSessionUpdated((update) => {
      if (update.sessionId === selectedId()) void refetchTaskContext();
    });
    onCleanup(unsubscribe);
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
                          <section id={turn.id} classList={{ "prompt-turn": true, "prompt-turn--preamble": !turn.prompt }}>
                            <For each={turn.events}>
                              {(event) => (
                                <div
                                  id={`event-${event.id}`}
                                  classList={{
                                    "event-flow-row": true,
                                    "event-flow-row--target": props.targetEventId === event.id,
                                  }}
                                >
                                  <EventRenderer event={event} />
                                </div>
                              )}
                            </For>
                          </section>
                        )}
                      </For>
                    </Show>
                  </div>
                  <PromptOutline prompts={promptOutline()} />
                </div>
              </>
            )}
          </Show>
        </section>
      </section>
    </>
  );
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

function PromptOutline(props: { prompts: AgentEvent[] }) {
  return (
    <div class="prompt-outline" aria-label="Prompt outline">
      <button type="button" class="prompt-outline-trigger" aria-haspopup="true">
        <span>Prompts</span>
        <strong>{props.prompts.length.toLocaleString()}</strong>
      </button>
      <nav class="prompt-outline-panel" aria-label="User prompt outline">
        <Show when={props.prompts.length} fallback={<p>No user prompts captured.</p>}>
          <For each={props.prompts}>
            {(event, index) => (
              <a href={`#prompt-${event.id}`} class="prompt-outline-item">
                <span>{index() + 1}</span>
                <strong>{eventTitle(event)}</strong>
                <small>{timeAgo(event.observedAt)}</small>
              </a>
            )}
          </For>
        </Show>
      </nav>
    </div>
  );
}

function isPromptEvent(event: AgentEvent): boolean {
  return event.eventType === "UserPromptSubmit" || event.role === "user";
}

function isAssistantEvent(event: AgentEvent): boolean {
  return event.eventType === "AssistantMessage" || event.role === "assistant";
}

function isMemoryEvent(event: AgentEvent): boolean {
  return event.eventType === "Decision" || event.eventType === "Observation" || event.eventType === "Handoff";
}

function isToolEvent(event: AgentEvent): boolean {
  return Boolean(event.toolName || event.toolInputJson || event.toolOutputJson || event.role === "tool" || /ToolUse$/.test(event.eventType));
}

function isPrimaryReaderEvent(event: AgentEvent): boolean {
  return !isMemoryEvent(event) && !isToolEvent(event) && (isPromptEvent(event) || isAssistantEvent(event));
}

function groupPromptTurns(events: AgentEvent[]): PromptTurn[] {
  const turns: PromptTurn[] = [];
  for (const event of events) {
    if (isPromptEvent(event)) {
      turns.push({ id: `prompt-${event.id}`, prompt: event, events: [event] });
      continue;
    }

    const current = turns[turns.length - 1];
    if (current) {
      current.events.push(event);
    } else {
      turns.push({ id: `prompt-preamble-${event.id}`, prompt: null, events: [event] });
    }
  }
  return turns;
}

function eventTitle(event: AgentEvent): string {
  const text = event.text?.trim();
  if (text) return text.length > 92 ? `${text.slice(0, 89)}...` : text;
  return event.eventType || "User prompt";
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
