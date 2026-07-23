import { createMemo, For, Show } from "solid-js";
import { useNavigate } from "@solidjs/router";
import type { DagEdgeType, DagNode, DagResponse } from "../lib/api";

const COLUMN_SPACING = 220;
const ROW_SPACING = 90;
const HORIZONTAL_PADDING = 96;
const VERTICAL_PADDING = 56;
const MIN_WIDTH = 420;
const MIN_HEIGHT = 240;

export type DagLayoutNode = DagNode & { x: number; y: number; column: number };
export type DagLayoutEdge = { id: string; type: DagEdgeType; from: DagLayoutNode; to: DagLayoutNode };
export type DagLayout = { nodes: DagLayoutNode[]; edges: DagLayoutEdge[]; width: number; height: number };

export type DagViewProps = {
  dag: DagResponse;
  currentTaskId?: string;
  currentSessionId?: string;
};

export function layoutDag(dag: DagResponse): DagLayout {
  const columns: DagNode[][] = [[], [], []];
  for (const node of dag.nodes) columns[columnFor(node)].push(node);

  const maxRows = Math.max(0, ...columns.map((column) => column.length));
  const maxColumnSpan = Math.max(0, maxRows - 1) * ROW_SPACING;
  const nodes = columns.flatMap((column, columnIndex) => {
    const columnSpan = Math.max(0, column.length - 1) * ROW_SPACING;
    const startY = VERTICAL_PADDING + (maxColumnSpan - columnSpan) / 2;
    return column.map((node, rowIndex): DagLayoutNode => ({
      ...node,
      x: HORIZONTAL_PADDING + columnIndex * COLUMN_SPACING,
      y: startY + rowIndex * ROW_SPACING,
      column: columnIndex,
    }));
  });

  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const edges = dag.edges.flatMap((edge, edgeIndex): DagLayoutEdge[] => {
    const from = nodesById.get(edge.from);
    const to = nodesById.get(edge.to);
    if (!from || !to) return [];
    return [{
      id: `${edge.type}:${edge.from}:${edge.to}:${edgeIndex}`,
      type: edge.type,
      from,
      to,
    }];
  });

  const maxX = nodes.length ? Math.max(...nodes.map((node) => node.x)) : 0;
  const maxY = nodes.length ? Math.max(...nodes.map((node) => node.y)) : 0;
  return {
    nodes,
    edges,
    width: Math.max(MIN_WIDTH, maxX + HORIZONTAL_PADDING),
    height: Math.max(MIN_HEIGHT, maxY + VERTICAL_PADDING),
  };
}

export default function DagView(props: DagViewProps) {
  const layout = createMemo(() => layoutDag(props.dag));

  return (
    <Show when={props.dag.nodes.length > 0} fallback={<p class="dag-empty">No DAG data yet.</p>}>
      <section class="dag-stage" aria-label="Task DAG">
        <svg class="dag-svg" viewBox={`0 0 ${layout().width} ${layout().height}`} role="img" aria-label="Task DAG">
          <g class="dag-edges" aria-hidden="true">
            <For each={layout().edges}>
              {(edge) => (
                <line
                  class={`dag-edge dag-edge--${edge.type}`}
                  x1={edge.from.x}
                  y1={edge.from.y}
                  x2={edge.to.x}
                  y2={edge.to.y}
                />
              )}
            </For>
          </g>
          <g class="dag-nodes">
            <For each={layout().nodes}>
              {(node) => (
                <DagNodeView
                  node={node}
                  current={
                    (node.type === "task" && props.currentTaskId === node.id)
                    || (node.type === "session" && props.currentSessionId === sessionRef(node))
                  }
                />
              )}
            </For>
          </g>
        </svg>
      </section>
    </Show>
  );
}

function DagNodeView(props: { node: DagLayoutNode; current: boolean }) {
  const tooltip = () => tooltipFor(props.node);
  const href = () => nodeHref(props.node);
  const navigate = useNavigate();
  const open = () => {
    const target = href();
    if (target) navigate(target);
  };
  // An <a> here compiles to an HTML-namespace anchor inside the SVG tree, which
  // lays out its SVG children at zero size in real browsers; navigate from the
  // <g> instead.
  return (
    <g
      class={`dag-node dag-node--${props.node.type}${props.current ? " dag-node--current" : ""}${href() ? " dag-node--link" : ""}`}
      transform={`translate(${props.node.x} ${props.node.y})`}
      style={{ "--node-color": nodeColor(props.node) }}
      data-node-id={props.node.id}
      data-node-type={props.node.type}
      data-node-href={href() ?? undefined}
      aria-label={href() ? `Open ${props.node.type}: ${props.node.label}` : tooltip()}
      role={href() ? "link" : undefined}
      tabindex={href() ? 0 : undefined}
      onClick={open}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          open();
        }
      }}
    >
      <title>{tooltip()}</title>
      <DagNodeContent node={props.node} />
    </g>
  );
}

function DagNodeContent(props: { node: DagLayoutNode }) {
  return (
    <>
      {props.node.type === "spec" ? (
        <rect class="dag-node-shape" x="-15" y="-15" width="30" height="30" transform="rotate(45)" />
      ) : props.node.type === "task" ? (
        <rect class="dag-node-shape" x="-44" y="-18" width="88" height="36" rx="8" />
      ) : (
        <circle class="dag-node-shape" r="18" />
      )}
      <text class="dag-label" x="0" y={props.node.type === "spec" ? 39 : 36} text-anchor="middle">
        {clamp(props.node.label, 28)}
      </text>
    </>
  );
}

function columnFor(node: DagNode): number {
  if (node.type === "spec") return 0;
  if (node.type === "task") return 1;
  return 2;
}

function nodeHref(node: DagNode): string | null {
  if (node.type === "task") return `/board?task=${encodeURIComponent(node.id)}`;
  if (node.type === "session") return `/sessions/${encodeURIComponent(sessionRef(node))}`;
  return null;
}

// Backend session node ids are wire-prefixed ("session:<uuid>"), never the raw session id a
// session href or a currentSessionId comparison needs. `ref` carries that raw id; fall back to
// stripping the prefix from `id` if `ref` is ever absent.
function sessionRef(node: DagNode): string {
  return node.ref || node.id.replace(/^session:/, "");
}

function nodeColor(node: DagNode): string {
  if (node.type === "spec") return "var(--accent)";
  if (node.type === "session") return "var(--text-dim)";
  if (node.status === "open") return "var(--blue)";
  if (node.status === "claimed" || node.status === "in_progress") return "var(--accent)";
  if (node.status === "blocked") return "var(--red)";
  if (node.status === "done") return "var(--green)";
  if (node.status === "cancelled") return "var(--text-faint)";
  return "var(--text-dim)";
}

function tooltipFor(node: DagNode): string {
  const details = [node.label, titleCase(node.type)];
  if (node.status) details.push(titleCase(node.status.replaceAll("_", " ")));
  return details.join(" - ");
}

function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function clamp(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 3)}...`;
}
