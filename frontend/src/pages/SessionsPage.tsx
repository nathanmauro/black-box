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
  let timelineRef: HTMLDivElement | undefined;
  const params = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const [showToolEvents, setShowToolEvents] = createSignal(false);
  const [sessions] = createSessionsResource(2_000);
  const selectedId = () => params.sessionId || "";
  const selectedSession = createMemo(() => sessions().find((session) => session.id === selectedId()));
  const [events] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
    initialValue: [] as AgentEvent[],
  });
  const timelineEvents = createMemo(() => [...events()].reverse());
  const visibleEvents = createMemo(() =>
    showToolEvents() ? timelineEvents() : timelineEvents().filter((event) => event.eventType !== "PostToolUse"),
  );
  const hiddenToolEventCount = createMemo(
    () => timelineEvents().filter((event) => event.eventType === "PostToolUse").length,
  );
  const sessionVirtualizer = createVirtualizer({
    get count() {
      return sessions().length;
    },
    getScrollElement: () => listRef || null,
    estimateSize: () => 72,
    overscan: 12,
  });
  const eventVirtualizer = createVirtualizer({
    get count() {
      return visibleEvents().length;
    },
    getScrollElement: () => timelineRef || null,
    estimateSize: () => 176,
    overscan: 8,
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
                  <Show when={hiddenToolEventCount() > 0}>
                    <label class="reading-toggle">
                      <input
                        type="checkbox"
                        checked={showToolEvents()}
                        onChange={(event) => setShowToolEvents(event.currentTarget.checked)}
                      />
                      <span>Show tool events</span>
                      <span aria-hidden="true" class="reading-toggle-count">
                        {hiddenToolEventCount().toLocaleString()}
                      </span>
                    </label>
                  </Show>
                </div>
              </header>

              <div class="detail-body">
                <div class="timeline-pane" ref={timelineRef}>
                  <Show when={!events.loading} fallback={<p class="empty-state">Loading events...</p>}>
                    <div class="virtual-spacer" style={{ height: `${eventVirtualizer.getTotalSize()}px` }}>
                      <For each={eventVirtualizer.getVirtualItems()}>
                        {(row) => {
                          const event = () => visibleEvents()[row.index];
                          return (
                            <div class="event-virtual-row" style={{ transform: `translateY(${row.start}px)` }}>
                              <EventRenderer event={event()} />
                            </div>
                          );
                        }}
                      </For>
                    </div>
                  </Show>
                </div>
              </div>
            </>
          )}
        </Show>
      </section>
    </section>
  );
}

function formatDate(iso: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(iso));
}
