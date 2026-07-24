import { createEffect, createMemo, createSignal, For, onCleanup, onMount, Show } from "solid-js";
import type { DagEdgeType, DagNode, DagResponse } from "../lib/api";
import DagView, { layoutLineageDag } from "./DagView";

const MAX_RAIL_AGENTS = 5;

export type LineageRailItem = {
  node: DagNode;
  sessionId: string;
  depth: number;
  current: boolean;
  root: boolean;
  relation: DagEdgeType | null;
};

type SessionLineageProps = {
  dag: DagResponse;
  currentSessionId: string;
  onSelectSession: (sessionId: string) => void;
};

export function buildLineageRail(dag: DagResponse, currentSessionId: string): LineageRailItem[] {
  const layout = layoutLineageDag(dag);
  const incoming = new Map<string, DagEdgeType>();
  for (const edge of layout.edges) {
    if (!incoming.has(edge.to.id)) incoming.set(edge.to.id, edge.type);
  }

  return [...layout.nodes]
    .sort((left, right) => left.column - right.column || left.y - right.y)
    .map((node) => ({
      node,
      sessionId: rawSessionId(node),
      depth: node.column,
      current: rawSessionId(node) === currentSessionId,
      root: !incoming.has(node.id),
      relation: incoming.get(node.id) ?? null,
    }));
}

export function visibleLineageRailItems(
  items: LineageRailItem[],
  limit = MAX_RAIL_AGENTS,
): LineageRailItem[] {
  if (items.length <= limit) return items;
  const current = items.find((item) => item.current);
  if (!current || items.slice(0, limit).includes(current)) return items.slice(0, limit);
  const included = new Set([...items.slice(0, Math.max(0, limit - 1)), current]);
  return items.filter((item) => included.has(item)).slice(0, limit);
}

export default function SessionLineage(props: SessionLineageProps) {
  const [expanded, setExpanded] = createSignal(false);
  const items = createMemo(() => buildLineageRail(props.dag, props.currentSessionId));
  const visibleItems = createMemo(() => visibleLineageRailItems(items()));
  const hiddenCount = createMemo(() => Math.max(0, items().length - visibleItems().length));
  const lensId = () => `lineage-lens-${props.currentSessionId.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
  let root!: HTMLElement;
  let toggle!: HTMLButtonElement;
  let lens!: HTMLDivElement;
  let previousSessionId = props.currentSessionId;

  const closeLens = (restoreFocus = false) => {
    setExpanded(false);
    if (restoreFocus) queueMicrotask(() => toggle?.focus());
  };

  createEffect(() => {
    const sessionId = props.currentSessionId;
    if (sessionId !== previousSessionId) {
      previousSessionId = sessionId;
      setExpanded(false);
    }
  });

  createEffect(() => {
    const open = expanded();
    if (lens) lens.toggleAttribute("inert", !open);
  });

  onMount(() => {
    const handlePointerDown = (event: PointerEvent) => {
      if (!expanded() || !(event.target instanceof Node) || root.contains(event.target)) return;
      closeLens();
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || !expanded()) return;
      event.preventDefault();
      closeLens(true);
    };
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    onCleanup(() => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    });
  });

  const selectSession = (sessionId: string) => {
    setExpanded(false);
    props.onSelectSession(sessionId);
  };

  return (
    <section class="session-lineage" ref={root}>
      <div class="lineage-dock">
        <span class="eyebrow">lineage</span>
        <nav class="lineage-agent-rail" aria-label="Agent lineage">
          <For each={visibleItems()}>
            {(item, index) => (
              <>
                <Show when={index() > 0}>
                  <span class="lineage-rail-connector" aria-hidden="true">
                    {connectorFor(item)}
                  </span>
                </Show>
                <button
                  type="button"
                  classList={{
                    "lineage-agent-chip": true,
                    "lineage-agent-chip--current": item.current,
                    "lineage-agent-chip--root": item.root,
                    "lineage-agent-chip--subagent": !item.root,
                    "lineage-agent-chip--continued": item.relation === "continued",
                  }}
                  aria-current={item.current ? "page" : undefined}
                  aria-label={`${roleLabel(item)}: ${item.node.label}`}
                  title={`${roleLabel(item)} · ${item.node.label}`}
                  onClick={() => selectSession(item.sessionId)}
                >
                  <LineageMark item={item} />
                  <span class="lineage-agent-label">{item.node.label}</span>
                </button>
              </>
            )}
          </For>
          <Show when={hiddenCount() > 0}>
            <span class="lineage-agent-overflow" title={`${hiddenCount()} more connected agents`}>
              +{hiddenCount()}
            </span>
          </Show>
        </nav>
        <button
          ref={toggle}
          type="button"
          class="lineage-lens-toggle"
          aria-expanded={expanded()}
          aria-controls={lensId()}
          aria-label={expanded() ? "Collapse lineage map" : "Expand lineage map"}
          onClick={() => setExpanded((open) => !open)}
        >
          <span>{items().length} {items().length === 1 ? "agent" : "agents"}</span>
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="m4 6 4 4 4-4" />
          </svg>
        </button>
      </div>

      <div
        ref={lens}
        id={lensId()}
        classList={{ "lineage-lens": true, "lineage-lens--open": expanded() }}
        role="region"
        aria-label="Agent lineage map"
        aria-hidden={!expanded()}
      >
        <div class="lineage-lens-surface">
          <header class="lineage-lens-head">
            <div>
              <span class="eyebrow">lineage map</span>
              <span>{items().length} connected sessions</span>
            </div>
            <button type="button" aria-label="Collapse lineage map" onClick={() => closeLens(true)}>
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" />
              </svg>
            </button>
          </header>
          <DagView
            dag={props.dag}
            currentSessionId={props.currentSessionId}
            layout="lineage"
            onSelectSession={selectSession}
          />
        </div>
      </div>
    </section>
  );
}

function LineageMark(props: { item: LineageRailItem }) {
  if (props.item.current) {
    return (
      <svg class="lineage-agent-mark" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="5.25" />
        <circle class="lineage-agent-mark-core" cx="8" cy="8" r="2" />
      </svg>
    );
  }
  if (props.item.root) {
    return (
      <svg class="lineage-agent-mark" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="4.75" />
        <path d="M8 1.5v2" />
      </svg>
    );
  }
  if (props.item.relation === "continued") {
    return (
      <svg class="lineage-agent-mark" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="5" cy="8" r="2.5" />
        <circle cx="11" cy="8" r="2.5" />
        <path d="M7.5 8h1" />
      </svg>
    );
  }
  return (
    <svg class="lineage-agent-mark" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M3 3v4.5C3 9.7 4.8 11 7 11h2.5" />
      <circle cx="12" cy="11" r="2.5" />
    </svg>
  );
}

function roleLabel(item: LineageRailItem): string {
  if (item.current && item.root) return "Current agent";
  if (item.current) return "Current subagent";
  if (item.root) return "Parent agent";
  if (item.relation === "continued") return "Continued agent";
  return "Subagent";
}

function connectorFor(item: LineageRailItem): string {
  if (item.root) return "·";
  if (item.relation === "continued") return "→";
  return "↳";
}

function rawSessionId(node: DagNode): string {
  return node.ref || node.id.replace(/^session:/, "");
}
