import { useNavigate } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, Show } from "solid-js";
import { getSessions, search, type AgentSession } from "../lib/api";
import { sourceLabel, timeAgo, truncatePath } from "../lib/format";
import KindBadge from "./KindBadge";
import SourceDot from "./SourceDot";

type CommandPaletteProps = {
  open: boolean;
  onClose: () => void;
};

type CommandItem = {
  id: string;
  label: string;
  meta: string;
  kind: "nav" | "session" | "search" | "event";
  eventKind?: string;
  run: () => void;
};

const NAV_ITEMS = [
  { id: "nav-activity", label: "Activity", path: "/", meta: "browse sessions, find events, or ask memory" },
  { id: "nav-recall", label: "Recall", path: "/recall", meta: "structured decisions and handoffs" },
];

export default function CommandPalette(props: CommandPaletteProps) {
  let inputRef: HTMLInputElement | undefined;
  const navigate = useNavigate();
  const [query, setQuery] = createSignal("");
  const [active, setActive] = createSignal(0);
  const [sessions] = createResource(() => (props.open ? "open" : ""), async (key) => (key ? getSessions(120) : []), {
    initialValue: [] as AgentSession[],
  });
  const [fallback] = createResource(
    () => (props.open && query().trim().length >= 2 ? query().trim() : ""),
    async (q) => (q ? search(q, 8) : null),
  );

  const items = createMemo<CommandItem[]>(() => {
    const q = normalize(query());
    const nav = NAV_ITEMS.filter((item) => !q || normalize(`${item.label} ${item.meta}`).includes(q)).map((item) => ({
      id: item.id,
      label: item.label,
      meta: item.meta,
      kind: "nav" as const,
      run: () => {
        navigate(item.path);
        close();
      },
    }));
    const matchedSessions = sessions()
      .filter((session) => !q || fuzzy(session, q))
      .slice(0, 7)
      .map((session) => sessionItem(session, navigate, close));
    const remoteEvents =
      fallback()?.local?.slice(0, 5).map((event) => ({
        id: `event-${event.id}`,
        label: event.text && event.text.length < 120 ? event.text : event.toolName || event.eventType,
        meta: `${sourceLabel(event.source)} · ${timeAgo(event.observedAt)}`,
        kind: "event" as const,
        eventKind: event.eventType,
        run: () => {
          navigate(`/sessions/${encodeURIComponent(event.sessionId)}`);
          close();
        },
      })) || [];
    const searchItem = query().trim()
      ? [
          {
            id: "search-query",
            label: `Search for "${query().trim()}"`,
            meta: "open Activity Find",
            kind: "search" as const,
            run: () => {
              navigate(`/?view=find&q=${encodeURIComponent(query().trim())}`);
              close();
            },
          },
        ]
      : [];
    return [...nav, ...matchedSessions, ...remoteEvents, ...searchItem];
  });

  createEffect(() => {
    if (!props.open) return;
    setActive(0);
    queueMicrotask(() => inputRef?.focus());
  });

  function close() {
    setQuery("");
    props.onClose();
  }

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive((index) => Math.min(index + 1, Math.max(items().length - 1, 0)));
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive((index) => Math.max(index - 1, 0));
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      items()[active()]?.run();
      return;
    }
    if (event.key === "Tab") {
      event.preventDefault();
      inputRef?.focus();
    }
  }

  return (
    <Show when={props.open}>
      <div class="palette-backdrop" onMouseDown={close}>
        <section
          class="command-palette"
          role="dialog"
          aria-modal="true"
          aria-label="Command palette"
          onMouseDown={(event) => event.stopPropagation()}
          onKeyDown={onKeyDown}
        >
          <input
            ref={inputRef}
            class="palette-input"
            value={query()}
            onInput={(event) => setQuery(event.currentTarget.value)}
            placeholder="Jump to session or search..."
            autocomplete="off"
          />
          <div class="palette-results" role="listbox">
            <For each={items()}>
              {(item, index) => (
                <button
                  type="button"
                  role="option"
                  aria-selected={active() === index()}
                  classList={{ "palette-item": true, "palette-item--active": active() === index() }}
                  onMouseEnter={() => setActive(index())}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={item.run}
                >
                  <PaletteIcon item={item} />
                  <span class="palette-item-text">
                    <strong>{item.label}</strong>
                    <small>{item.meta}</small>
                  </span>
                </button>
              )}
            </For>
            <Show when={!items().length}>
              <p class="empty-state">No command matches.</p>
            </Show>
          </div>
        </section>
      </div>
    </Show>
  );
}

function PaletteIcon(props: { item: CommandItem }) {
  if (props.item.kind === "event") return <KindBadge kind={props.item.eventKind} />;
  if (props.item.kind === "session") return <SourceDot source={props.item.meta.split(" · ")[0].toLowerCase()} />;
  return <span class={`palette-glyph palette-glyph--${props.item.kind}`}>{props.item.kind === "search" ? "⌕" : "↗"}</span>;
}

function sessionItem(session: AgentSession, navigate: ReturnType<typeof useNavigate>, close: () => void): CommandItem {
  return {
    id: `session-${session.id}`,
    label: session.title || session.clientSessionId,
    meta: `${sourceLabel(session.source)} · ${truncatePath(session.cwd)} · ${timeAgo(session.lastSeenAt)}`,
    kind: "session",
    run: () => {
      navigate(`/sessions/${encodeURIComponent(session.id)}`);
      close();
    },
  };
}

function fuzzy(session: AgentSession, q: string): boolean {
  return normalize(`${session.title} ${session.cwd || ""} ${session.source} ${session.clientSessionId}`).includes(q);
}

function normalize(value: string): string {
  return value.toLowerCase().replace(/\s+/g, " ").trim();
}
