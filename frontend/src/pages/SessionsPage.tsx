import { useNavigate, useParams } from "@solidjs/router";
import { createMemo, createResource, createSignal, For, Show } from "solid-js";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import { getProjectTimeline, getSessionEvents, type AgentEvent, type AgentSession, type ProjectTimelineBlock } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { parseQuery } from "../lib/query";
import { createSessionsResource } from "../lib/stores";

type SessionsPageProps = {
  selectedSessionId?: string;
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

type ProjectGroup = {
  key: string;
  label: string;
  sessions: AgentSession[];
};

type CombinedLogKind = "Decision" | "Handoff" | "Observation";

type CombinedLogEntry = {
  id: string;
  kind: CombinedLogKind;
  source: string;
  clientSessionId?: string | null;
  sessionId?: string | null;
  sessionTitle?: string | null;
  headline?: string | null;
  text?: string | null;
  observedAt?: string | null;
};

export default function SessionsPage(props: SessionsPageProps = {}) {
  const params = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const [sessionFilter, setSessionFilter] = createSignal("");
  const [showMemoryEvents, setShowMemoryEvents] = createSignal(false);
  const [collapsedProjectKeys, setCollapsedProjectKeys] = createSignal<Set<string>>(new Set());
  const [combinedProject, setCombinedProject] = createSignal<ProjectGroup | null>(null);
  const [sessions] = createSessionsResource(2_000);
  const filteredSessions = createMemo(() => filterSessions(sessions(), sessionFilter()));
  const projectGroups = createMemo(() => groupSessionsByProject(filteredSessions()));
  const selectedId = () => props.selectedSessionId || params.sessionId || (props.defaultToFirst ? filteredSessions()[0]?.id ?? "" : "");
  const selectedSession = createMemo(() => sessions().find((session) => session.id === selectedId()));
  const [events] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
    initialValue: [] as AgentEvent[],
  });
  const [combinedLog] = createResource(combinedProject, async (project) => (project ? loadCombinedLog(project) : []), {
    initialValue: [] as CombinedLogEntry[],
  });
  const timelineEvents = createMemo(() => [...events()].reverse());
  const visibleEvents = createMemo(() =>
    timelineEvents().filter((event) => isPrimaryReaderEvent(event) || (showMemoryEvents() && isMemoryEvent(event))),
  );
  const promptTurns = createMemo(() => groupPromptTurns(visibleEvents()));
  const memoryEventCount = createMemo(() => timelineEvents().filter(isMemoryEvent).length);
  const promptOutline = createMemo(() => timelineEvents().filter(isPromptEvent));

  function selectSession(id: string) {
    setCombinedProject(null);
    if (props.onSelectSession) {
      props.onSelectSession(id);
      return;
    }
    navigate(`/sessions/${encodeURIComponent(id)}`);
  }

  function toggleProject(key: string) {
    setCollapsedProjectKeys((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function openCombinedLog(group: ProjectGroup) {
    setCombinedProject({ ...group, sessions: [...group.sessions] });
  }

  return (
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
            <For each={projectGroups()}>
              {(group) => (
                <section class="session-group">
                  <header class="session-group-header">
                    <button
                      type="button"
                      classList={{
                        "session-group-head": true,
                        "session-group-head--active": combinedProject()?.key === group.key,
                      }}
                      aria-expanded={!collapsedProjectKeys().has(group.key)}
                      title={group.key}
                      onClick={() => toggleProject(group.key)}
                      onContextMenu={(event) => {
                        event.preventDefault();
                        openCombinedLog(group);
                      }}
                    >
                      <span class="session-group-caret" aria-hidden="true">
                        {collapsedProjectKeys().has(group.key) ? "›" : "⌄"}
                      </span>
                      <strong>{group.label}</strong>
                      <span>{group.sessions.length.toLocaleString()}</span>
                    </button>
                    <button
                      type="button"
                      class="session-group-log-button"
                      aria-label={`Open combined log for ${group.label}`}
                      title="Open combined log"
                      onClick={(event) => {
                        event.stopPropagation();
                        openCombinedLog(group);
                      }}
                    >
                      Log
                    </button>
                  </header>
                  <Show when={!collapsedProjectKeys().has(group.key)}>
                    <div class="session-group-rows">
                      <For each={group.sessions}>
                        {(session) => (
                          <button
                            type="button"
                            classList={{ "session-row": true, "session-row--active": !combinedProject() && session.id === selectedId() }}
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
                </section>
              )}
            </For>
          </div>
        </Show>
      </aside>

      <section class="session-detail-pane">
        <Show
          when={combinedProject()}
          fallback={
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
                                  <div id={`event-${event.id}`} class="event-flow-row">
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
          }
        >
          {(project) => (
            <CombinedLogView
              project={project()}
              entries={combinedLog()}
              loading={combinedLog.loading}
              onClose={() => setCombinedProject(null)}
            />
          )}
        </Show>
      </section>
    </section>
  );
}

function CombinedLogView(props: { project: ProjectGroup; entries: CombinedLogEntry[]; loading: boolean; onClose: () => void }) {
  return (
    <section class="combined-log-view" role="region" aria-label={`Project combined log for ${props.project.label}`}>
      <header class="detail-header combined-log-header">
        <div class="detail-title-block">
          <p class="eyebrow">project combined log</p>
          <h1>Combined log</h1>
          <p title={props.project.key}>
            {props.project.label} · {props.project.sessions.length.toLocaleString()} sessions
          </p>
        </div>
        <div class="combined-log-actions">
          <button type="button" class="combined-log-close" onClick={props.onClose}>
            Back to sessions
          </button>
        </div>
      </header>

      <div class="combined-log-body">
        <Show when={!props.loading} fallback={<p class="empty-state combined-log-empty">Loading combined log...</p>}>
          <Show
            when={props.entries.length}
            fallback={<p class="empty-state combined-log-empty">No Decision, Handoff, or Observation entries for this project.</p>}
          >
            <For each={props.entries}>
              {(entry) => (
                <article class={`combined-log-entry combined-log-entry--${entry.kind.toLowerCase()}`}>
                  <div class="combined-log-entry-head">
                    <span class={`combined-log-tag kind-badge kind-badge--${entry.kind.toLowerCase()}`}>{entry.kind}</span>
                    <SourceDot source={entry.source} label />
                    <span class="combined-log-session" title={sessionLabel(entry)}>
                      {sessionLabel(entry)}
                    </span>
                    <time dateTime={entry.observedAt || undefined}>{formatDate(entry.observedAt)}</time>
                  </div>
                  <h2>{entry.headline || entry.kind}</h2>
                  <p>{entry.text || "No text captured."}</p>
                </article>
              )}
            </For>
          </Show>
        </Show>
      </div>
    </section>
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

function groupSessionsByProject(sessions: AgentSession[]): ProjectGroup[] {
  const groups = new Map<string, ProjectGroup>();
  for (const session of sessions) {
    const key = projectKey(session.cwd);
    const existing = groups.get(key);
    if (existing) {
      existing.sessions.push(session);
    } else {
      groups.set(key, {
        key,
        label: projectLabel(session.cwd),
        sessions: [session],
      });
    }
  }
  return [...groups.values()];
}

function projectKey(cwd: string | null | undefined): string {
  const trimmed = cwd?.trim();
  return trimmed || "unknown";
}

function projectLabel(cwd: string | null | undefined): string {
  const key = projectKey(cwd);
  if (key === "unknown") return "Unknown project";
  const parts = key.split("/").filter(Boolean);
  return parts[parts.length - 1] || key;
}

async function loadCombinedLog(project: ProjectGroup): Promise<CombinedLogEntry[]> {
  try {
    const timeline = await getProjectTimeline(project.key, 2_000);
    const entries = timeline.items.map(combinedEntryFromTimelineBlock).filter(isCombinedLogEntry);
    if (entries.length) return sortCombinedLog(entries);
  } catch {
    // Raw cwd project keys can fail to resolve through the backend codec; session events are the fallback source.
  }

  const eventResults = await Promise.allSettled(project.sessions.map((session) => getSessionEvents(session.id, 2_000)));
  const sessionById = new Map(project.sessions.map((session) => [session.id, session]));
  const entries = eventResults.flatMap((result, index) => {
    if (result.status !== "fulfilled") return [];
    const owningSession = project.sessions[index];
    return result.value
      .map((event) => combinedEntryFromEvent(event, sessionById.get(event.sessionId) || owningSession))
      .filter(isCombinedLogEntry);
  });
  return sortCombinedLog(entries);
}

function combinedEntryFromTimelineBlock(block: ProjectTimelineBlock): CombinedLogEntry | null {
  const kind = signalKind(block.eventType, block.blockType);
  if (!kind) return null;
  return {
    id: block.id,
    kind,
    source: block.source,
    clientSessionId: block.clientSessionId,
    sessionId: block.sessionId,
    sessionTitle: block.sessionTitle,
    headline: block.headline || firstTextLine(block.text) || kind,
    text: block.text,
    observedAt: block.observedAt,
  };
}

function combinedEntryFromEvent(event: AgentEvent, session?: AgentSession): CombinedLogEntry | null {
  const kind = signalKind(event.eventType);
  if (!kind) return null;
  const headline = metadataString(event.metadata, event.eventType.toLowerCase()) || metadataString(event.metadata, "title") || firstTextLine(event.text);
  return {
    id: event.id,
    kind,
    source: event.source || session?.source || "unknown",
    clientSessionId: event.clientSessionId || session?.clientSessionId,
    sessionId: event.sessionId || session?.id,
    sessionTitle: session?.title,
    headline: headline || kind,
    text: event.text,
    observedAt: event.observedAt,
  };
}

function signalKind(eventType?: string | null, blockType?: string | null): CombinedLogKind | null {
  return normalizeSignalKind(eventType) || normalizeSignalKind(blockType);
}

function normalizeSignalKind(value?: string | null): CombinedLogKind | null {
  switch (value?.trim().toLowerCase()) {
    case "decision":
      return "Decision";
    case "handoff":
      return "Handoff";
    case "observation":
      return "Observation";
    default:
      return null;
  }
}

function isCombinedLogEntry(entry: CombinedLogEntry | null): entry is CombinedLogEntry {
  return Boolean(entry);
}

function sortCombinedLog(entries: CombinedLogEntry[]): CombinedLogEntry[] {
  return [...entries].sort((left, right) => timestampValue(left.observedAt) - timestampValue(right.observedAt));
}

function timestampValue(iso: string | null | undefined): number {
  if (!iso) return 0;
  const value = Date.parse(iso);
  return Number.isNaN(value) ? 0 : value;
}

function firstTextLine(text: string | null | undefined): string | null {
  return text?.trim().split(/\r?\n/).find(Boolean) || null;
}

function metadataString(metadata: unknown, key: string): string | null {
  if (!metadata || typeof metadata !== "object" || Array.isArray(metadata)) return null;
  const value = (metadata as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function sessionLabel(entry: CombinedLogEntry): string {
  const sessionId = entry.clientSessionId || entry.sessionId || "unknown session";
  return entry.sessionTitle ? `${entry.sessionTitle} (${sessionId})` : sessionId;
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
