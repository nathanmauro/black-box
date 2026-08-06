import { createMemo, For, onMount, Show } from "solid-js";
import {
  layoutTrajectory,
  type TrajectoryEdgeType,
  type TrajectoryGraph,
  type TrajectoryLayoutEdge,
  type TrajectoryLayoutNode,
  type TrajectoryNodeKind,
} from "../lib/trajectory";

export type TrajectoryViewProps = {
  graph: TrajectoryGraph;
  selectedNodeId?: string | null;
  onSelect: (node: TrajectoryLayoutNode) => void;
};

export default function TrajectoryView(props: TrajectoryViewProps) {
  let stageRef: HTMLElement | undefined;
  const layout = createMemo(() => layoutTrajectory(props.graph));

  onMount(() => {
    const stage = stageRef;
    if (!stage) return;
    const head = layout().nodes.find((node) => node.id === props.graph.headId);
    const maxScroll = Math.max(0, stage.scrollWidth - stage.clientWidth);
    const target = head ? head.x - stage.clientWidth / 3 : maxScroll;
    stage.scrollLeft = Math.min(maxScroll, Math.max(0, target));
  });

  return (
    <Show when={layout().nodes.length > 0} fallback={<p class="traj-empty">No trajectory captures yet</p>}>
      <section ref={stageRef} class="traj-stage" aria-label="Project trajectory graph">
        <svg
          class="traj-svg"
          viewBox={`0 0 ${layout().width} ${layout().height}`}
          width={layout().width}
          height={layout().height}
          role="img"
          aria-label="Project trajectory graph"
        >
          <g class="traj-edges" aria-hidden="true">
            <For each={layout().edges}>
              {(edge) => <path class={`traj-edge traj-edge--${edge.type}`} d={edgePath(edge)} />}
            </For>
          </g>
          <g class="traj-nodes">
            <For each={layout().nodes}>
              {(node) => (
                <TrajectoryNodeView
                  node={node}
                  stale={props.graph.stale}
                  selected={props.selectedNodeId === node.id}
                  onSelect={props.onSelect}
                />
              )}
            </For>
          </g>
        </svg>
      </section>
    </Show>
  );
}

function TrajectoryNodeView(props: {
  node: TrajectoryLayoutNode;
  stale: boolean;
  selected: boolean;
  onSelect: (node: TrajectoryLayoutNode) => void;
}) {
  const select = () => props.onSelect(props.node);
  return (
    <g
      class={nodeClass(props.node, props.stale, props.selected)}
      transform={`translate(${props.node.x} ${props.node.y})`}
      style={nodeStyle(props.node)}
      data-node-id={props.node.id}
      data-node-kind={props.node.kind}
      aria-label={tooltipFor(props.node)}
      role="button"
      tabindex={0}
      onClick={select}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          select();
        }
      }}
    >
      <title>{tooltipFor(props.node)}</title>
      <TrajectoryNodeShape node={props.node} />
      <TrajectoryNodeLabel node={props.node} />
    </g>
  );
}

function TrajectoryNodeShape(props: { node: TrajectoryLayoutNode }) {
  return (
    <>
      {props.node.kind === "future-task" ? (
        <rect class="traj-node-shape" x="-42" y="-15" width="84" height="30" rx="6" />
      ) : props.node.kind === "future-more" ? (
        <rect class="traj-node-shape" x="-36" y="-14" width="72" height="28" rx="14" />
      ) : (
        <circle class="traj-node-shape" r={radiusFor(props.node.kind)} />
      )}
      <Show when={props.node.kind === "burst" && props.node.hasDecision}>
        <rect class="traj-decision-stud" x="-4" y="-18" width="8" height="8" transform="rotate(45)" />
      </Show>
    </>
  );
}

function TrajectoryNodeLabel(props: { node: TrajectoryLayoutNode }) {
  if (props.node.kind === "future-more") {
    return <text class="traj-label traj-label--inside" x="0" y="4" text-anchor="middle">{props.node.label}</text>;
  }
  if (props.node.kind === "future-task") {
    return (
      <>
        <text class="traj-label traj-label--inside" x="0" y="4" text-anchor="middle">{clamp(props.node.label, 16)}</text>
        <text class="traj-eyebrow" x="0" y="30" text-anchor="middle">{props.node.eyebrow}</text>
      </>
    );
  }
  if (props.node.kind.startsWith("future-")) {
    return (
      <>
        <text class="traj-label" x="0" y="30" text-anchor="middle">{clamp(props.node.label, 24)}</text>
        <text class="traj-eyebrow" x="0" y="44" text-anchor="middle">{props.node.eyebrow}</text>
      </>
    );
  }
  if (props.node.kind === "head") {
    return (
      <>
        <text class="traj-eyebrow" x="0" y="-24" text-anchor="middle">{props.node.eyebrow}</text>
        <text class="traj-label traj-label--head" x="0" y="38" text-anchor="middle">{clamp(props.node.label, 28)}</text>
      </>
    );
  }
  if (props.node.kind === "stub") {
    return (
      <>
        <text class="traj-label" x="0" y="23" text-anchor="middle">{props.node.label}</text>
        <text class="traj-eyebrow" x="0" y="37" text-anchor="middle">{props.node.eyebrow}</text>
      </>
    );
  }

  const above = props.node.labelSide !== "below";
  return (
    <>
      <text class="traj-label" x="0" y={above ? -20 : 30} text-anchor="middle">{props.node.label}</text>
      <text class="traj-eyebrow" x="0" y={above ? -34 : 44} text-anchor="middle">{props.node.eyebrow}</text>
    </>
  );
}

function edgePath(edge: TrajectoryLayoutEdge): string {
  if (edge.type === "possible" || edge.type === "projected") {
    const midpoint = edge.from.x + (edge.to.x - edge.from.x) / 2;
    return `M ${edge.from.x} ${edge.from.y} C ${midpoint} ${edge.from.y}, ${midpoint} ${edge.to.y}, ${edge.to.x} ${edge.to.y}`;
  }
  return `M ${edge.from.x} ${edge.from.y} L ${edge.to.x} ${edge.to.y}`;
}

function nodeClass(node: TrajectoryLayoutNode, stale: boolean, selected: boolean): string {
  const futureStale = stale && node.kind.startsWith("future-");
  return [
    "traj-node",
    `traj-node--${node.kind}`,
    node.stale ? "traj-node--stale" : "",
    futureStale ? "traj-node--future-stale" : "",
    selected ? "traj-node--selected" : "",
  ].filter(Boolean).join(" ");
}

function nodeStyle(node: TrajectoryLayoutNode): Record<string, string> {
  const style: Record<string, string> = { "--node-color": nodeColor(node) };
  if (node.kind === "future-ghost") {
    const confidence = typeof node.confidence === "number" ? node.confidence : 0.5;
    style["--ghost-opacity"] = String(0.45 + 0.4 * confidence);
  }
  return style;
}

function nodeColor(node: TrajectoryLayoutNode): string {
  if (node.kind === "head") return "var(--accent)";
  if (node.kind === "future-next") return "var(--green)";
  if (node.kind === "future-loop") return "var(--yellow)";
  if (node.kind === "future-ghost") return "var(--accent)";
  if (node.kind === "future-task") return taskColor(node.task?.status);
  if (node.kind === "future-more") return "var(--text-dim)";
  if (node.kind === "stub") return "var(--text-faint)";
  return "var(--text-dim)";
}

function taskColor(status: string | null | undefined): string {
  if (status === "open") return "var(--blue)";
  if (status === "claimed" || status === "in_progress") return "var(--accent)";
  if (status === "blocked") return "var(--red)";
  if (status === "done") return "var(--green)";
  if (status === "cancelled") return "var(--text-faint)";
  return "var(--text-dim)";
}

function radiusFor(kind: TrajectoryNodeKind): number {
  if (kind === "head") return 14;
  if (kind === "burst") return 9;
  if (kind === "deep-past") return 7;
  if (kind === "stub") return 6;
  if (kind === "future-ghost") return 11;
  return 10;
}

function tooltipFor(node: TrajectoryLayoutNode): string {
  const parts = [node.label, titleCase(node.kind.replace("future-", "").replace("-", " "))];
  if (node.eyebrow) parts.push(node.eyebrow);
  return parts.join(" - ");
}

function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function clamp(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 3)}...`;
}
