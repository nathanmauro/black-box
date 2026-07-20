import { createEffect, createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import type { AgentEvent } from "../lib/api";
import { sourceLabel, timeAgo } from "../lib/format";

export type ConversationNavigatorTurn = {
  id: string;
  prompt: AgentEvent;
  responses: AgentEvent[];
};

type ConversationNavigatorProps = {
  turns: ConversationNavigatorTurn[];
};

const PREVIEW_CHARS = 260;

export default function ConversationNavigator(props: ConversationNavigatorProps) {
  const [activeId, setActiveId] = createSignal("");
  const [hoveredIndex, setHoveredIndex] = createSignal<number | null>(null);
  const [focusedIndex, setFocusedIndex] = createSignal<number | null>(null);
  const [rovingIndex, setRovingIndex] = createSignal(0);
  const links: HTMLAnchorElement[] = [];
  let navigator: HTMLElement | undefined;

  const previewIndex = createMemo(() => hoveredIndex() ?? focusedIndex());
  const previewTurn = createMemo(() => {
    const index = previewIndex();
    if (index === null) return null;
    const turn = props.turns[index];
    return turn ? { index, turn } : null;
  });

  createEffect(() => {
    const turns = props.turns;
    if (!turns.length) {
      setActiveId("");
      setRovingIndex(0);
      return;
    }
    if (!turns.some((turn) => turn.id === activeId())) setActiveId(turns[0].id);
    if (rovingIndex() >= turns.length) setRovingIndex(turns.length - 1);
  });

  createEffect(() => {
    const turns = props.turns;
    if (!turns.length || typeof IntersectionObserver === "undefined") return;

    const root = navigator?.closest(".detail-body")?.querySelector<HTMLElement>(".timeline-pane") ?? null;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((left, right) => Math.abs(left.boundingClientRect.top) - Math.abs(right.boundingClientRect.top));
        const next = visible[0]?.target.id;
        if (!next) return;
        setActiveId(next);
        const index = turns.findIndex((turn) => turn.id === next);
        if (index >= 0) setRovingIndex(index);
      },
      { root, rootMargin: "-12% 0px -68%", threshold: [0, 0.15, 0.5] },
    );

    for (const turn of turns) {
      const element = document.getElementById(turn.id);
      if (element) observer.observe(element);
    }
    onCleanup(() => observer.disconnect());
  });

  function focusTurn(index: number) {
    if (!props.turns.length) return;
    const next = Math.max(0, Math.min(index, props.turns.length - 1));
    setRovingIndex(next);
    links[next]?.focus();
  }

  function handleKeyDown(event: KeyboardEvent, index: number) {
    if (event.key === "ArrowUp" || event.key === "ArrowLeft") {
      event.preventDefault();
      focusTurn(index - 1);
    } else if (event.key === "ArrowDown" || event.key === "ArrowRight") {
      event.preventDefault();
      focusTurn(index + 1);
    } else if (event.key === "Home") {
      event.preventDefault();
      focusTurn(0);
    } else if (event.key === "End") {
      event.preventDefault();
      focusTurn(props.turns.length - 1);
    } else if (event.key === "Escape") {
      event.preventDefault();
      setFocusedIndex(null);
      links[index]?.blur();
    }
  }

  function selectTurn(turn: ConversationNavigatorTurn, index: number) {
    setActiveId(turn.id);
    setRovingIndex(index);
    const target = document.getElementById(turn.id);
    target?.scrollIntoView?.({
      block: "start",
      behavior: prefersReducedMotion() ? "auto" : "smooth",
    });
  }

  return (
    <nav
      ref={navigator}
      class="conversation-navigator"
      aria-label="Conversation outline"
      onMouseLeave={() => setHoveredIndex(null)}
    >
      <div class="conversation-navigator-label" aria-hidden="true">
        <span>turns</span>
        <strong>{props.turns.length.toLocaleString()}</strong>
      </div>
      <Show when={props.turns.length} fallback={<p class="conversation-navigator-empty">No prompts</p>}>
        <ol class="conversation-navigator-list">
          <For each={props.turns}>
            {(turn, index) => {
              const distance = () => {
                const preview = previewIndex();
                return preview === null ? 3 : Math.min(Math.abs(preview - index()), 3);
              };
              return (
                <li
                  class="conversation-navigator-item"
                  data-distance={distance()}
                  data-preview={previewIndex() === index() ? "true" : undefined}
                >
                  <a
                    ref={(element) => { links[index()] = element; }}
                    href={`#${turn.id}`}
                    class="conversation-navigator-link"
                    aria-label={`Turn ${index() + 1}: ${previewText(turn.prompt.text, 92) || "User prompt"}`}
                    aria-current={activeId() === turn.id ? "location" : undefined}
                    tabIndex={rovingIndex() === index() ? 0 : -1}
                    onMouseEnter={() => setHoveredIndex(index())}
                    onFocus={() => {
                      setFocusedIndex(index());
                      setRovingIndex(index());
                    }}
                    onBlur={() => setFocusedIndex(null)}
                    onKeyDown={(event) => handleKeyDown(event, index())}
                    onClick={() => selectTurn(turn, index())}
                  >
                    <span class="conversation-navigator-bar" aria-hidden="true" />
                  </a>
                </li>
              );
            }}
          </For>
        </ol>
      </Show>
      <Show when={previewTurn()}>
        {(preview) => {
          const response = () => preview().turn.responses[preview().turn.responses.length - 1];
          return (
            <article class="conversation-navigator-preview" aria-hidden="true">
              <header>
                <span>Turn {preview().index + 1}</span>
                <time>{timeAgo(preview().turn.prompt.observedAt)}</time>
              </header>
              <div class="conversation-preview-message conversation-preview-message--user">
                <strong>You</strong>
                <p>{previewText(preview().turn.prompt.text, PREVIEW_CHARS) || "Prompt text was not captured."}</p>
              </div>
              <Show
                when={response()}
                fallback={<p class="conversation-preview-missing">Agent response not captured.</p>}
              >
                {(event) => (
                  <div class="conversation-preview-message conversation-preview-message--assistant">
                    <strong>{sourceLabel(event().source)} response</strong>
                    <p>{previewText(event().text, PREVIEW_CHARS) || "Response text was not captured."}</p>
                  </div>
                )}
              </Show>
            </article>
          );
        }}
      </Show>
    </nav>
  );
}

function previewText(value: string | null | undefined, maxLength: number): string {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

function prefersReducedMotion(): boolean {
  return typeof window !== "undefined"
    && typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
