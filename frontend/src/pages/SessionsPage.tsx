import { useNavigate, useParams } from "@solidjs/router";
import { createMemo, createResource, createSignal, For, Show } from "solid-js";
import { createVirtualizer } from "@tanstack/solid-virtual";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import { getSessionEvents, type AgentEvent } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { createSessionsResource } from "../lib/stores";

export default function SessionsPage() {
  let listRef: HTMLDivElement | undefined;
  const params = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const [showMemoryEvents, setShowMemoryEvents] = createSignal(false);
  const [sessions] = createSessionsResource(2_000);
  const selectedId = () => params.sessionId || "";
  const selectedSession = createMemo(() => sessions().find((session) => session.id === selectedId()));
  const [events] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
    initialValue: [] as AgentEvent[],
  });
  const timelineEvents = createMemo(() => [...events()].reverse());
  const visibleEvents = createMemo(() =>
    timelineEvents().filter((event) => isPrimaryReaderEvent(event) || (showMemoryEvents() && isMemoryEvent(event))),
  );
  const memoryEventCount = createMemo(() => timelineEvents().filter(isMemoryEvent).length);
  const promptOutline = createMemo(() => timelineEvents().filter(isPromptEvent));
  const sessionVirtualizer = createVirtualizer({
    get count() {
      return sessions().length;
    },
    getScrollElement: () => listRef || null,
    estimateSize: () => 72,
    overscan: 12,
  });

  return (
    <section class="sessions-page">
      <aside class="session-list-pane">
        <div class="pane-head">
          <span class="eyebrow">sessions</span>
          <span>{sessions().length.toLocaleString()}</span>
        </div>
        <div class="virtual-list" ref={listRef}>
          <div class="virtual-spacer" style={{ height: `${sessionVirtualizer.getTotalSize()}px` }}>
            <For each={sessionVirtualizer.getVirtualItems()}>
              {(row) => {
                const session = () => sessions()[row.index];
                return (
                  <button
                    type="button"
                    classList={{ "session-row": true, "session-row--active": session()?.id === selectedId() }}
                    style={{ transform: `translateY(${row.start}px)` }}
                    onClick={() => navigate(`/sessions/${encodeURIComponent(session().id)}`)}
                  >
                    <SourceDot source={session().source} />
                    <span class="session-row-main">
                      <strong>{session().title || session().clientSessionId}</strong>
                      <small>
                        {session().eventCount.toLocaleString()} · {truncatePath(session().cwd)} · {timeAgo(session().lastSeenAt)}
                      </small>
                    </span>
                  </button>
                );
              }}
            </For>
          </div>
        </div>
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
                    <For each={visibleEvents()}>
                      {(event) => (
                        <div id={`event-${event.id}`} class="event-flow-row">
                          <EventRenderer event={event} />
                        </div>
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
  );
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
              <a href={`#event-${event.id}`} class="prompt-outline-item">
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

function eventTitle(event: AgentEvent): string {
  const text = event.text?.trim();
  if (text) return text.length > 92 ? `${text.slice(0, 89)}...` : text;
  return event.eventType || "User prompt";
}

function formatDate(iso: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(iso));
}
