import { createMemo, createResource, For, Show } from "solid-js";
import KindBadge from "../components/KindBadge";
import SourceDot from "../components/SourceDot";
import { getRecall, type RecalledItem } from "../lib/api";
import { sourceColor, sourceLabel, timeAgo, truncatePath } from "../lib/format";
import { sourceFilter } from "../lib/stores";

const GRAPH_WITHIN_HOURS = 720;
const GRAPH_KINDS = ["decision", "handoff", "observation"];
const CLUSTER_RADIUS = 220;
const LEAF_RADIUS = 132;

export type ConstellationNodeType = "origin" | "cluster" | "leaf";

export type ConstellationNode = {
  id: string;
  type: ConstellationNodeType;
  label: string;
  tooltip: string;
  x: number;
  y: number;
  size: number;
  angle: number;
  color: string;
  kind?: string;
  source?: string;
  count?: number;
  item?: RecalledItem;
};

export type ConstellationEdge = {
  id: string;
  from: string;
  to: string;
};

export type ConstellationGraph = {
  nodes: ConstellationNode[];
  edges: ConstellationEdge[];
  bounds: GraphBounds;
};

type GraphBounds = {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
};

type Cluster = {
  id: string;
  key: string;
  label: string;
  tooltip: string;
  source: string;
  items: RecalledItem[];
};

export default function GraphPage() {
  const [result, { refetch }] = createResource(() => getRecall("", GRAPH_WITHIN_HOURS, GRAPH_KINDS));
  const visibleItems = createMemo(() => sourceFilter.matches(result()?.items || []));
  const graph = createMemo(() => buildConstellation(visibleItems()));

  return (
    <section class="page graph-page">
      <header class="graph-hero">
        <div>
          <p class="eyebrow">graph constellation</p>
          <h1>Map recalled intent by project</h1>
          <p>Decisions, handoffs, and observations form project clusters from the existing structured recall API.</p>
        </div>
      </header>

      <Show when={result.error}>
        {(error) => (
          <p class="inline-error graph-error">
            Graph failed: {errorMessage(error())}
            <button type="button" onClick={() => void refetch()}>
              Retry
            </button>
          </p>
        )}
      </Show>

      <Show when={!result.loading} fallback={<GraphSkeleton />}>
        <Show when={!result.error}>
          <Show
            when={visibleItems().length}
            fallback={
              <div class="graph-empty">
                <p class="eyebrow">empty</p>
                <h2>No recalled intent in range</h2>
                <p>Capture decisions or handoffs, then return here to see their project constellation.</p>
              </div>
            }
          >
            <GraphSummary items={visibleItems()} graph={graph()} />
            <Constellation graph={graph()} />
          </Show>
        </Show>
      </Show>
    </section>
  );
}

export function buildConstellation(items: RecalledItem[]): ConstellationGraph {
  const safeItems = Array.isArray(items) ? items : [];
  const clusters = buildClusters(safeItems);
  const nodes: ConstellationNode[] = [
    {
      id: "origin",
      type: "origin",
      label: "Black Box",
      tooltip: "origin - recalled structured intent",
      x: 0,
      y: 0,
      size: 30,
      angle: -Math.PI / 2,
      color: "var(--accent)",
    },
  ];
  const edges: ConstellationEdge[] = [];
  const step = clusters.length ? (Math.PI * 2) / clusters.length : 0;
  const start = -Math.PI / 2;

  clusters.forEach((cluster, clusterIndex) => {
    const angle = start + step * clusterIndex;
    const clusterNode: ConstellationNode = {
      id: cluster.id,
      type: "cluster",
      label: cluster.label,
      tooltip: cluster.tooltip,
      x: round(Math.cos(angle) * CLUSTER_RADIUS),
      y: round(Math.sin(angle) * CLUSTER_RADIUS),
      size: clusterSize(cluster.items.length),
      angle,
      color: sourceColor(cluster.source),
      source: cluster.source,
      count: cluster.items.length,
    };
    nodes.push(clusterNode);
    edges.push({ id: `edge:origin:${cluster.id}`, from: "origin", to: cluster.id });

    const spread = memberSpread(cluster.items.length);
    const base = angle - spread / 2;
    cluster.items.forEach((item, memberIndex) => {
      const memberAngle = cluster.items.length === 1 ? angle : base + (spread * memberIndex) / (cluster.items.length - 1);
      const id = leafId(item, memberIndex);
      const leafNode: ConstellationNode = {
        id,
        type: "leaf",
        label: labelForItem(item),
        tooltip: tooltipForItem(item, cluster.label),
        x: round(clusterNode.x + Math.cos(memberAngle) * LEAF_RADIUS),
        y: round(clusterNode.y + Math.sin(memberAngle) * LEAF_RADIUS),
        size: leafSize(item),
        angle: memberAngle,
        color: sourceColor(item.source),
        source: item.source,
        kind: normalizeKind(item.kind),
        item,
      };
      nodes.push(leafNode);
      edges.push({ id: `edge:${cluster.id}:${id}`, from: cluster.id, to: id });
    });
  });

  return { nodes, edges, bounds: boundsFor(nodes) };
}

function Constellation(props: { graph: ConstellationGraph }) {
  return (
    <section class="graph-stage" aria-label="Recall constellation">
      <svg class="graph-svg" viewBox={viewBoxFor(props.graph.bounds)} role="img" aria-label="Recall constellation graph">
        <g class="graph-edges" aria-hidden="true">
          <For each={props.graph.edges}>
            {(edge) => {
              const from = () => props.graph.nodes.find((node) => node.id === edge.from);
              const to = () => props.graph.nodes.find((node) => node.id === edge.to);
              return (
                <Show when={from() && to()}>
                  <line x1={from()!.x} y1={from()!.y} x2={to()!.x} y2={to()!.y} />
                </Show>
              );
            }}
          </For>
        </g>
        <g class="graph-nodes">
          <For each={props.graph.nodes}>{(node) => <ConstellationNodeView node={node} />}</For>
        </g>
      </svg>
    </section>
  );
}

function ConstellationNodeView(props: { node: ConstellationNode }) {
  const node = () => props.node;
  return (
    <g
      class={`graph-node graph-node--${node().type}${node().kind ? ` graph-node--${node().kind}` : ""}`}
      transform={`translate(${node().x} ${node().y})`}
      style={{ "--node-color": node().color }}
      aria-label={node().tooltip}
    >
      <title>{node().tooltip}</title>
      <Show
        when={node().type === "leaf"}
        fallback={
          <circle class="graph-node-shape" r={node().size / 2}>
            <title>{node().tooltip}</title>
          </circle>
        }
      >
        <rect
          class="graph-node-shape"
          x={-node().size / 2}
          y={-node().size / 2}
          width={node().size}
          height={node().size}
          transform="rotate(45)"
        />
      </Show>
      <Show when={node().type === "cluster" && node().count != null}>
        <text class="graph-node-count" text-anchor="middle" dominant-baseline="central">
          {node().count}
        </text>
      </Show>
      <text class={`graph-label graph-label--${node().type}`} x={labelPosition(node()).x} y={labelPosition(node()).y} text-anchor={labelPosition(node()).anchor}>
        {node().label}
      </text>
    </g>
  );
}

function GraphSummary(props: { items: RecalledItem[]; graph: ConstellationGraph }) {
  const clusters = () => props.graph.nodes.filter((node) => node.type === "cluster");
  const leaves = () => props.graph.nodes.filter((node) => node.type === "leaf");
  const kinds = () => summarizeKinds(props.items);

  return (
    <section class="graph-summary" aria-label="Constellation summary">
      <div class="graph-stat">
        <span>clusters</span>
        <strong>{clusters().length.toLocaleString()}</strong>
      </div>
      <div class="graph-stat">
        <span>intent nodes</span>
        <strong>{leaves().length.toLocaleString()}</strong>
      </div>
      <div class="graph-stat graph-stat--wide">
        <span>visible kinds</span>
        <div class="graph-kind-strip">
          <For each={kinds()}>
            {(kind) => (
              <span>
                <KindBadge kind={titleKind(kind.kind)} />
                {kind.count.toLocaleString()}
              </span>
            )}
          </For>
        </div>
      </div>
      <div class="graph-stat graph-stat--wide">
        <span>sources</span>
        <div class="graph-source-strip">
          <For each={summarizeSources(props.items)}>
            {(source) => (
              <span>
                <SourceDot source={source.source} />
                {sourceLabel(source.source)} {source.count.toLocaleString()}
              </span>
            )}
          </For>
        </div>
      </div>
    </section>
  );
}

function GraphSkeleton() {
  return (
    <>
      <section class="graph-summary" aria-label="Loading graph">
        <div class="graph-skeleton graph-skeleton--stat" />
        <div class="graph-skeleton graph-skeleton--stat" />
        <div class="graph-skeleton graph-skeleton--stat graph-stat--wide" />
        <div class="graph-skeleton graph-skeleton--stat graph-stat--wide" />
      </section>
      <div class="graph-skeleton graph-skeleton--stage" />
    </>
  );
}

function buildClusters(items: RecalledItem[]): Cluster[] {
  const clusters = new Map<string, Cluster>();
  for (const item of items) {
    const target = clusterTarget(item);
    const existing = clusters.get(target.key);
    if (existing) {
      existing.items.push(item);
      existing.source = dominantSource(existing.items);
    } else {
      clusters.set(target.key, {
        id: `cluster:${stableId(target.key)}`,
        key: target.key,
        label: target.label,
        tooltip: target.tooltip,
        source: item.source || "manual",
        items: [item],
      });
    }
  }

  return [...clusters.values()]
    .map((cluster) => ({ ...cluster, items: [...cluster.items].sort(sortItems) }))
    .sort((a, b) => b.items.length - a.items.length || a.label.localeCompare(b.label));
}

function clusterTarget(item: RecalledItem): { key: string; label: string; tooltip: string } {
  const repo = humanText(item.repo);
  if (repo) {
    const label = projectLabel(repo);
    return {
      key: `project:${repo}`,
      label,
      tooltip: `${label} - ${truncatePath(repo)}`,
    };
  }
  const source = humanText(item.source) || "unknown";
  const label = sourceLabel(source);
  return {
    key: `source:${source.toLowerCase()}`,
    label,
    tooltip: `${label} source`,
  };
}

function labelForItem(item: RecalledItem): string {
  const headline = humanText(item.headline);
  if (headline) return clamp(headline, 46);
  return `${titleKind(item.kind)} from ${item.source || "unknown"}`;
}

function tooltipForItem(item: RecalledItem, clusterLabel: string): string {
  const details = [
    titleKind(item.kind),
    sourceLabel(item.source),
    clusterLabel,
    item.confidence != null ? `${Math.round(Number(item.confidence) * 100)}% confidence` : "",
    timeAgo(item.observedAt),
  ].filter(Boolean);
  const body = humanText(item.headline) || humanText(item.rationale) || humanText(item.nextAction) || item.clientSessionId || "";
  return `${details.join(" - ")}${body ? `\n${clamp(body, 120)}` : ""}`;
}

function projectLabel(repo: string): string {
  const trimmed = repo.replace(/\/+$/, "");
  const segment = trimmed.split("/").filter(Boolean).at(-1);
  return segment || truncatePath(repo);
}

function summarizeKinds(items: RecalledItem[]) {
  const counts = new Map<string, number>();
  for (const item of items) {
    const kind = normalizeKind(item.kind);
    counts.set(kind, (counts.get(kind) || 0) + 1);
  }
  return [...counts.entries()].map(([kind, count]) => ({ kind, count })).sort((a, b) => b.count - a.count || a.kind.localeCompare(b.kind));
}

function summarizeSources(items: RecalledItem[]) {
  const counts = new Map<string, number>();
  for (const item of items) {
    const source = item.source || "unknown";
    counts.set(source, (counts.get(source) || 0) + 1);
  }
  return [...counts.entries()].map(([source, count]) => ({ source, count })).sort((a, b) => b.count - a.count || a.source.localeCompare(b.source));
}

function dominantSource(items: RecalledItem[]): string {
  return summarizeSources(items)[0]?.source || "unknown";
}

function sortItems(a: RecalledItem, b: RecalledItem): number {
  return Date.parse(b.observedAt || "") - Date.parse(a.observedAt || "") || labelForItem(a).localeCompare(labelForItem(b));
}

function normalizeKind(kind: string | null | undefined): string {
  const normalized = String(kind || "intent").toLowerCase();
  if (normalized === "decisions") return "decision";
  if (normalized === "handoffs") return "handoff";
  if (normalized === "observations") return "observation";
  return normalized || "intent";
}

function titleKind(kind: string | null | undefined): string {
  const normalized = normalizeKind(kind);
  if (normalized === "decision") return "Decision";
  if (normalized === "handoff") return "Handoff";
  if (normalized === "observation") return "Observation";
  return normalized.charAt(0).toUpperCase() + normalized.slice(1);
}

function humanText(value: string | null | undefined): string {
  const trimmed = String(value || "").trim().replace(/\s+/g, " ");
  if (!trimmed || looksLikeJson(trimmed)) return "";
  return trimmed;
}

function looksLikeJson(value: string): boolean {
  return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"));
}

function leafId(item: RecalledItem, index: number): string {
  return `leaf:${stableId(item.eventId || `${item.kind}:${item.clientSessionId || index}`)}`;
}

function stableId(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9:_-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 96);
}

function memberSpread(count: number): number {
  if (count <= 1) return 0;
  return Math.min(Math.PI * 1.35, Math.max(Math.PI / 3, (count - 1) * 0.26));
}

function clusterSize(count: number): number {
  return Math.min(42, 24 + Math.sqrt(count) * 5);
}

function leafSize(item: RecalledItem): number {
  const confidence = Number(item.confidence);
  if (!Number.isFinite(confidence)) return 15;
  return Math.max(12, Math.min(22, 12 + confidence * 10));
}

function labelPosition(node: ConstellationNode): { x: number; y: number; anchor: "start" | "middle" | "end" } {
  if (node.type === "origin") return { x: 0, y: -28, anchor: "middle" };
  const dx = Math.cos(node.angle);
  const dy = Math.sin(node.angle);
  const offset = node.size + 12;
  if (Math.abs(dx) < 0.28) return { x: 0, y: dy < 0 ? -offset : offset + 10, anchor: "middle" };
  return { x: dx > 0 ? offset : -offset, y: 5, anchor: dx > 0 ? "start" : "end" };
}

function boundsFor(nodes: ConstellationNode[]): GraphBounds {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const node of nodes) {
    const pad = node.type === "origin" ? 90 : node.type === "cluster" ? 130 : 170;
    minX = Math.min(minX, node.x - pad);
    minY = Math.min(minY, node.y - pad);
    maxX = Math.max(maxX, node.x + pad);
    maxY = Math.max(maxY, node.y + pad);
  }
  return { minX, minY, maxX, maxY };
}

function viewBoxFor(bounds: GraphBounds): string {
  const pad = 24;
  const width = Math.max(420, bounds.maxX - bounds.minX + pad * 2);
  const height = Math.max(360, bounds.maxY - bounds.minY + pad * 2);
  return `${round(bounds.minX - pad)} ${round(bounds.minY - pad)} ${round(width)} ${round(height)}`;
}

function clamp(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 3)}...`;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
