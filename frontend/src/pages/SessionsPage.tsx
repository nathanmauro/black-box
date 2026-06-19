import { useNavigate, useParams } from "@solidjs/router";
import { createMemo, createResource, For, Show } from "solid-js";
import { createVirtualizer } from "@tanstack/solid-virtual";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import { parseJsonObject } from "../components/events/eventData";
import { getSessionEvents, type AgentEvent } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { createSessionsResource } from "../lib/stores";

type OutlineItem = {
  label: string;
  meta: string;
  eventId: string;
};

const EDIT_TOOL = /multiedit|edit|write|patch/i;
const READ_TOOL = /read|grep|search|glob|list|ls|cat|sed/i;

export default function SessionsPage() {
  let listRef: HTMLDivElement | undefined;
  let timelineRef: HTMLDivElement | undefined;
  const params = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const [sessions] = createSessionsResource(2_000);
  const selectedId = () => params.sessionId || "";
  const selectedSession = createMemo(() => sessions().find((session) => session.id === selectedId()));
  const [events] = createResource(selectedId, async (id) => (id ? getSessionEvents(id, 2_000) : []), {
    initialValue: [] as AgentEvent[],
  });
  const timelineEvents = createMemo(() => [...events()].reverse());
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
      return timelineEvents().length;
    },
    getScrollElement: () => timelineRef || null,
    estimateSize: () => 176,
    overscan: 8,
  });
  const outline = createMemo(() => buildOutline(events()));

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
                </div>
              </header>

              <div class="detail-body">
                <div class="timeline-pane" ref={timelineRef}>
                  <Show when={!events.loading} fallback={<p class="empty-state">Loading events...</p>}>
                    <div class="virtual-spacer" style={{ height: `${eventVirtualizer.getTotalSize()}px` }}>
                      <For each={eventVirtualizer.getVirtualItems()}>
                        {(row) => {
                          const event = () => timelineEvents()[row.index];
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
                <aside class="outline-pane" aria-label="Session outline">
                  <SessionOutline outline={outline()} />
                </aside>
              </div>
            </>
          )}
        </Show>
      </section>
    </section>
  );
}

function SessionOutline(props: { outline: ReturnType<typeof buildOutline> }) {
  return (
    <>
      <div class="pane-head">
        <span class="eyebrow">outline</span>
      </div>
      <OutlineSection title="Files edited" items={props.outline.edited} />
      <OutlineSection title="Files read" items={props.outline.read} />
      <OutlineSection title="Tools used" items={props.outline.tools} />
    </>
  );
}

function OutlineSection(props: { title: string; items: OutlineItem[] }) {
  return (
    <section class="outline-section">
      <div class="outline-section-head">
        <span>{props.title}</span>
        <strong>{props.items.length}</strong>
      </div>
      <Show when={props.items.length} fallback={<p class="outline-empty">None</p>}>
        <For each={props.items.slice(0, 8)}>
          {(item) => (
            <div class="outline-item">
              <span>{truncatePath(item.label)}</span>
              <small>{item.meta}</small>
            </div>
          )}
        </For>
      </Show>
    </section>
  );
}

function buildOutline(events: AgentEvent[]) {
  const edited = unique(events.flatMap((event) => fileItems(event, EDIT_TOOL)));
  const read = unique(events.flatMap((event) => fileItems(event, READ_TOOL)));
  const counts = new Map<string, { count: number; eventId: string }>();
  for (const event of events) {
    if (!event.toolName) continue;
    const current = counts.get(event.toolName) || { count: 0, eventId: event.id };
    counts.set(event.toolName, { count: current.count + 1, eventId: current.eventId });
  }
  const tools = [...counts.entries()]
    .sort((a, b) => b[1].count - a[1].count)
    .map(([label, value]) => ({ label, meta: `${value.count.toLocaleString()} uses`, eventId: value.eventId }));
  return { edited, read, tools };
}

function fileItems(event: AgentEvent, matcher: RegExp): OutlineItem[] {
  if (!event.toolName || !matcher.test(event.toolName)) return [];
  return toolPaths(event).map((path) => ({ label: path, meta: event.toolName || "tool", eventId: event.id }));
}

function toolPaths(event: AgentEvent): string[] {
  const args = parseJsonObject(event.toolInputJson);
  if (!args) return [];
  const paths: string[] = [];
  for (const key of ["file_path", "filePath", "target_file", "targetFile", "path", "cwd"]) {
    if (typeof args[key] === "string" && args[key].trim()) paths.push(args[key].trim());
  }
  if (Array.isArray(args.files)) {
    for (const file of args.files) {
      if (typeof file === "string" && file.trim()) paths.push(file.trim());
    }
  }
  return paths;
}

function unique(items: OutlineItem[]): OutlineItem[] {
  const seen = new Set<string>();
  const result: OutlineItem[] = [];
  for (const item of items) {
    const key = `${item.label}\0${item.meta}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(item);
  }
  return result;
}

function formatDate(iso: string): string {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(iso));
}
