import type {
  ProjectTrajectoryResponse,
  TrajectoryCapture,
  TrajectoryPath,
  TrajectoryTask,
} from "./api";

export const GAP_HOURS = 48;
export const SPINE_MAX = 5;
export const STALE_DAYS = 14;
export const FRONTIER_DAYS = 7;
export const FUTURE_MAX = 7;
export const PROJECTION_TTL = 30;
export const GHOST_MAX = 3;
export const JACCARD_THRESHOLD = 0.5;
export const TRAIL_SPACING = 84;
export const FUTURE_ROW = 64;
export const HEAD_PULL = 40;
export const GHOST_SHELL_OFFSET = 36;
export const TRAJECTORY_MIN_WIDTH = 560;
export const TRAJECTORY_MIN_HEIGHT = 260;

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;
const TRAIL_START_X = 64;
const TRAIL_TOP_PADDING = 60;
const TRAIL_BOTTOM_PADDING = 78;
const FUTURE_BASE_X = 134;
const FUTURE_ARC_STEP = 42;
const FUTURE_TOP_PADDING = 58;
const STUB_X_OFFSET = 48;
const STUB_Y_OFFSET = 76;

export type TrajectoryNodeKind =
  | "deep-past"
  | "burst"
  | "head"
  | "future-next"
  | "future-loop"
  | "future-task"
  | "future-ghost"
  | "future-more"
  | "stub";

export type TrajectoryEdgeType = "trail" | "possible" | "projected" | "rejected";

export type TrajectoryFutureSource = "nextAction" | "openLoop" | "task" | "ghost";

export type TrajectoryFutureItem = {
  id: string;
  source: TrajectoryFutureSource;
  label: string;
  text: string;
  sourceCapture?: TrajectoryCapture;
  sessionId?: string | null;
  observedAt?: string | null;
  task?: TrajectoryTask;
  path?: TrajectoryPath;
  confidence?: number | null;
};

export type TrajectoryGraphNode = {
  id: string;
  kind: TrajectoryNodeKind;
  label: string;
  eyebrow?: string;
  fullText?: string;
  stale?: boolean;
  spineIndex?: number;
  rank?: number;
  labelSide?: "above" | "below";
  memberCount?: number;
  hasDecision?: boolean;
  members?: TrajectoryCapture[];
  alternatives?: string[];
  items?: TrajectoryFutureItem[];
  sourceCapture?: TrajectoryCapture;
  sessionId?: string | null;
  observedAt?: string | null;
  task?: TrajectoryTask;
  path?: TrajectoryPath;
  confidence?: number | null;
  sourceBurstId?: string;
};

export type TrajectoryGraphEdge = {
  id: string;
  from: string;
  to: string;
  type: TrajectoryEdgeType;
};

export type TrajectoryGraph = {
  nodes: TrajectoryGraphNode[];
  edges: TrajectoryGraphEdge[];
  headId?: string;
  stale: boolean;
};

export type TrajectoryLayoutNode = TrajectoryGraphNode & {
  x: number;
  y: number;
  labelHalfWidth: number;
  labelLeft: number;
  labelRight: number;
};

export type TrajectoryLayoutEdge = {
  id: string;
  type: TrajectoryEdgeType;
  from: TrajectoryLayoutNode;
  to: TrajectoryLayoutNode;
};

export type TrajectoryLayout = {
  nodes: TrajectoryLayoutNode[];
  edges: TrajectoryLayoutEdge[];
  width: number;
  height: number;
};

type Burst = {
  id: string;
  captures: TrajectoryCapture[];
  startMs: number;
  endMs: number;
  label: string;
  hasDecision: boolean;
};

type SolidCandidate = TrajectoryFutureItem & {
  kind: Exclude<TrajectoryNodeKind, "deep-past" | "burst" | "head" | "future-ghost" | "future-more" | "stub">;
  sortMs: number;
};

type GhostCandidate = TrajectoryFutureItem & {
  kind: "future-ghost";
  sortMs: number;
};

type RankedTaskTier = string | string[];
type PositionedNode = TrajectoryGraphNode & { x: number; y: number };

export function buildTrajectory(feed: ProjectTrajectoryResponse, nowMs: number): TrajectoryGraph {
  const captures = sortedCaptures(feed.captures ?? []);
  if (!captures.length) return { nodes: [], edges: [], stale: false };
  const hiddenCount = hiddenCaptureCount(feed.totalCaptures, captures.length);

  const bursts = buildBursts(captures);
  const newestBurst = bursts[bursts.length - 1];
  const headCapture = latestHandoff(newestBurst.captures) ?? newestBurst.captures[newestBurst.captures.length - 1];
  const headMs = timestampValue(headCapture.observedAt);
  const stale = headMs > 0 && nowMs - headMs > STALE_DAYS * DAY_MS;

  const nodes: TrajectoryGraphNode[] = [];
  const edges: TrajectoryGraphEdge[] = [];
  const historicalBursts = bursts.slice(0, -1);
  const collapsedBursts = historicalBursts.slice(0, Math.max(0, historicalBursts.length - SPINE_MAX));
  const visibleBursts = historicalBursts.slice(Math.max(0, historicalBursts.length - SPINE_MAX));
  if (hiddenCount > 0 && visibleBursts.length) {
    collapsedBursts.push(visibleBursts.shift()!);
  }
  let spineIndex = 0;

  if (collapsedBursts.length || hiddenCount > 0) {
    const collapsedCaptures = collapsedBursts.flatMap((burst) => burst.captures);
    const memberCount = collapsedCaptures.length + hiddenCount;
    const capped = hiddenCount > 0;
    const firstVisibleStartMs = visibleBursts[0]?.startMs ?? newestBurst.startMs;
    nodes.push({
      id: "deep-past",
      kind: "deep-past",
      label: capped
        ? `+${memberCount} earlier ${plural("capture", memberCount)}`
        : `+${collapsedBursts.length} earlier · ${collapsedCaptures.length} ${plural("capture", collapsedCaptures.length)}`,
      eyebrow: capped
        ? `before ${dateRangeLabel(firstVisibleStartMs, firstVisibleStartMs)}`
        : dateRangeLabel(collapsedBursts[0].startMs, collapsedBursts[collapsedBursts.length - 1].endMs),
      fullText: capped
        ? `${memberCount} older trajectory ${plural("capture", memberCount)} collapsed into the deep past. ${hiddenCount} older ${plural("capture", hiddenCount)} ${hiddenCount === 1 ? "lies" : "lie"} beyond the feed cap.`
        : `${collapsedCaptures.length} older trajectory ${plural("capture", collapsedCaptures.length)} collapsed into the deep past.`,
      spineIndex,
      labelSide: spineIndex % 2 === 0 ? "above" : "below",
      memberCount,
      hasDecision: collapsedCaptures.some((capture) => capture.kind === "decision"),
      members: collapsedCaptures,
    });
    spineIndex += 1;
  }

  for (const burst of visibleBursts) {
    nodes.push(burstNode(burst, spineIndex));
    spineIndex += 1;
  }

  const headId = `head:${headCapture.id}`;
  nodes.push({
    id: headId,
    kind: "head",
    label: textValue(headCapture.headline, headCapture.text, "Current state"),
    eyebrow: `${stale ? "IDLE" : "NOW"} · ${ageLabel(headCapture.observedAt, nowMs)}`,
    fullText: textValue(headCapture.text, headCapture.headline, "No handoff text captured."),
    stale,
    spineIndex,
    labelSide: spineIndex % 2 === 0 ? "above" : "below",
    memberCount: newestBurst.captures.length,
    hasDecision: newestBurst.hasDecision,
    sourceCapture: headCapture,
    sessionId: headCapture.sessionId,
    observedAt: headCapture.observedAt,
  });

  const spineNodes = nodes.filter((node) => node.spineIndex !== undefined).sort((left, right) => (left.spineIndex ?? 0) - (right.spineIndex ?? 0));
  for (let index = 1; index < spineNodes.length; index += 1) {
    edges.push({
      id: `trail:${spineNodes[index - 1].id}:${spineNodes[index].id}`,
      from: spineNodes[index - 1].id,
      to: spineNodes[index].id,
      type: "trail",
    });
  }

  const futures = rankedFutures(captures, feed.tasks ?? [], newestBurst, headCapture, nowMs);
  for (const future of futures.visible) {
    const node = futureNode(future, futures.rankById.get(future.id) ?? 0);
    nodes.push(node);
    edges.push({
      id: `${node.kind === "future-ghost" ? "projected" : "possible"}:${headId}:${node.id}`,
      from: headId,
      to: node.id,
      type: node.kind === "future-ghost" ? "projected" : "possible",
    });
  }
  if (futures.overflow.length) {
    const overflowNode: TrajectoryGraphNode = {
      id: "future:more",
      kind: "future-more",
      label: `+${futures.overflow.length} more`,
      eyebrow: "ranked",
      fullText: `${futures.overflow.length} additional ranked ${plural("future", futures.overflow.length)}.`,
      rank: futures.visible.length,
      items: futures.overflow,
    };
    nodes.push(overflowNode);
    edges.push({
      id: `possible:${headId}:${overflowNode.id}`,
      from: headId,
      to: overflowNode.id,
      type: "possible",
    });
  }

  const stubSources = collapsedBursts.length
    ? [{ id: "deep-past", captures: collapsedBursts.flatMap((burst) => burst.captures) }]
    : [];
  stubSources.push(...visibleBursts.map((burst) => ({ id: burst.id, captures: burst.captures })));
  stubSources.push({ id: headId, captures: newestBurst.captures });
  for (const source of stubSources) {
    const alternatives = source.captures.flatMap((capture) => cleanList(capture.alternatives));
    if (!alternatives.length) continue;
    const stub: TrajectoryGraphNode = {
      id: `stub:${source.id}`,
      kind: "stub",
      label: `${alternatives.length} not taken`,
      eyebrow: "rejected",
      fullText: alternatives.join("\n"),
      alternatives,
      sourceCapture: source.captures.find((capture) => cleanList(capture.alternatives).length),
      sourceBurstId: source.id,
    };
    nodes.push(stub);
    edges.push({
      id: `rejected:${source.id}:${stub.id}`,
      from: source.id,
      to: stub.id,
      type: "rejected",
    });
  }

  return { nodes, edges, headId, stale };
}

export function layoutTrajectory(graph: TrajectoryGraph): TrajectoryLayout {
  if (!graph.nodes.length) {
    return { nodes: [], edges: [], width: TRAJECTORY_MIN_WIDTH, height: TRAJECTORY_MIN_HEIGHT };
  }

  const spine = graph.nodes
    .filter((node) => node.spineIndex !== undefined)
    .sort((left, right) => (left.spineIndex ?? 0) - (right.spineIndex ?? 0));
  const futures = graph.nodes
    .filter((node) => node.kind.startsWith("future-"))
    .sort((left, right) => (left.rank ?? 0) - (right.rank ?? 0));
  const futureRows = futures.length;
  const spineY = FUTURE_TOP_PADDING + Math.max(0, futureRows - 1) * FUTURE_ROW;
  const positions = new Map<string, PositionedNode>();

  for (const node of spine) {
    const index = node.spineIndex ?? 0;
    positions.set(node.id, {
      ...node,
      x: TRAIL_START_X + index * TRAIL_SPACING + (node.kind === "head" ? HEAD_PULL : 0),
      y: spineY,
    });
  }

  const head = graph.headId ? positions.get(graph.headId) : undefined;
  const centerSlot = Math.max(0, Math.floor((futureRows - 1) / 2));
  futures.forEach((node, index) => {
    const distanceFromCenter = Math.abs(index - centerSlot);
    const shellOffset = node.kind === "future-ghost" ? GHOST_SHELL_OFFSET : 0;
    positions.set(node.id, {
      ...node,
      x: (head?.x ?? TRAIL_START_X) + FUTURE_BASE_X + (centerSlot - distanceFromCenter) * FUTURE_ARC_STEP + shellOffset,
      y: spineY - index * FUTURE_ROW,
    });
  });

  const stubs = graph.nodes.filter((node) => node.kind === "stub");
  for (const stub of stubs) {
    const source = stub.sourceBurstId ? positions.get(stub.sourceBurstId) : undefined;
    positions.set(stub.id, {
      ...stub,
      x: (source?.x ?? TRAIL_START_X) + STUB_X_OFFSET,
      y: (source?.y ?? spineY) + STUB_Y_OFFSET,
    });
  }

  for (const node of graph.nodes) {
    if (!positions.has(node.id)) {
      positions.set(node.id, { ...node, x: TRAIL_START_X, y: spineY });
    }
  }

  const positionedNodes = graph.nodes.map((node) => positions.get(node.id)).filter(Boolean) as PositionedNode[];
  const minLabelLeft = Math.min(...positionedNodes.map((node) => node.x - labelHalfWidth(node)));
  const xOffset = Math.max(0, Math.ceil(-minLabelLeft));
  const nodes = positionedNodes.map((node) => withLabelExtents(node, xOffset));
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const edges = graph.edges.flatMap((edge): TrajectoryLayoutEdge[] => {
    const from = nodesById.get(edge.from);
    const to = nodesById.get(edge.to);
    if (!from || !to) return [];
    return [{ ...edge, from, to }];
  });

  const maxX = Math.max(...nodes.map((node) => node.x));
  const maxY = Math.max(...nodes.map((node) => node.y));
  const maxLabelRight = Math.max(...nodes.map((node) => node.labelRight));
  return {
    nodes,
    edges,
    width: Math.max(TRAJECTORY_MIN_WIDTH, maxX + 116, maxLabelRight + 16),
    height: Math.max(TRAJECTORY_MIN_HEIGHT, maxY + TRAIL_BOTTOM_PADDING),
  };
}

function sortedCaptures(captures: TrajectoryCapture[]): TrajectoryCapture[] {
  return [...captures].sort((left, right) => {
    const delta = timestampValue(left.observedAt) - timestampValue(right.observedAt);
    if (delta !== 0) return delta;
    return left.id.localeCompare(right.id);
  });
}

function hiddenCaptureCount(totalCaptures: number, visibleCaptureCount: number): number {
  if (!Number.isSafeInteger(totalCaptures) || totalCaptures < 0) return 0;
  return Math.max(0, totalCaptures - visibleCaptureCount);
}

function buildBursts(captures: TrajectoryCapture[]): Burst[] {
  const bursts: Burst[] = [];
  for (const capture of captures) {
    const captureMs = timestampValue(capture.observedAt);
    const previous = bursts[bursts.length - 1];
    if (!previous || captureMs - previous.endMs > GAP_HOURS * HOUR_MS) {
      bursts.push({
        id: `burst:${bursts.length}:${capture.id}`,
        captures: [capture],
        startMs: captureMs,
        endMs: captureMs,
        label: dateRangeLabel(captureMs, captureMs),
        hasDecision: capture.kind === "decision",
      });
      continue;
    }
    previous.captures.push(capture);
    previous.endMs = Math.max(previous.endMs, captureMs);
    previous.label = dateRangeLabel(previous.startMs, previous.endMs);
    previous.hasDecision = previous.hasDecision || capture.kind === "decision";
  }
  return bursts;
}

function burstNode(burst: Burst, spineIndex: number): TrajectoryGraphNode {
  return {
    id: burst.id,
    kind: "burst",
    label: burst.label,
    eyebrow: `${burst.captures.length} ${plural("capture", burst.captures.length)}`,
    fullText: `${burst.label} · ${burst.captures.length} ${plural("capture", burst.captures.length)}`,
    spineIndex,
    labelSide: spineIndex % 2 === 0 ? "above" : "below",
    memberCount: burst.captures.length,
    hasDecision: burst.hasDecision,
    members: burst.captures,
  };
}

function latestHandoff(captures: TrajectoryCapture[]): TrajectoryCapture | undefined {
  for (let index = captures.length - 1; index >= 0; index -= 1) {
    if (captures[index].kind === "handoff") return captures[index];
  }
  return undefined;
}

function rankedFutures(
  captures: TrajectoryCapture[],
  tasks: TrajectoryTask[],
  headBurst: Burst,
  headCapture: TrajectoryCapture,
  nowMs: number,
): { visible: Array<SolidCandidate | GhostCandidate>; overflow: TrajectoryFutureItem[]; rankById: Map<string, number> } {
  const frontier = frontierHandoffs(captures, headBurst, headCapture);
  const nextActions = dedupeNewest(
    frontier
      .map((capture): SolidCandidate | null => {
        const text = cleanText(capture.nextAction);
        if (!text) return null;
        return {
          id: `future:next:${capture.id}`,
          source: "nextAction",
          kind: "future-next",
          label: text,
          text,
          sourceCapture: capture,
          sessionId: capture.sessionId,
          observedAt: capture.observedAt,
          sortMs: timestampValue(capture.observedAt),
        };
      })
      .filter(Boolean) as SolidCandidate[],
  ).slice(0, 2);

  const blockedTasks = rankedTasks(tasks, ["blocked"]).slice(0, 2);
  const activeTasks = rankedTasks(tasks, [["claimed", "in_progress"], "open"]);
  const openLoops = dedupeNewest(
    frontier.flatMap((capture) =>
      cleanList(capture.openLoops).map((loop, index): SolidCandidate => ({
        id: `future:loop:${capture.id}:${index}`,
        source: "openLoop",
        kind: "future-loop",
        label: loop,
        text: loop,
        sourceCapture: capture,
        sessionId: capture.sessionId,
        observedAt: capture.observedAt,
        sortMs: timestampValue(capture.observedAt),
      })),
    ),
  );

  const solids = dedupeRanked([
    ...nextActions,
    ...blockedTasks,
    ...activeTasks,
    ...openLoops,
  ]);
  const ghosts = ghostCandidates(captures, nowMs, solids);
  const ranked = [...solids, ...ghosts];
  const rankById = new Map(ranked.map((candidate, index) => [candidate.id, index]));
  const hasOverflow = ranked.length > FUTURE_MAX;
  const actualSlots = hasOverflow ? FUTURE_MAX - 1 : FUTURE_MAX;
  const visible: Array<SolidCandidate | GhostCandidate> = [];

  if (ghosts.length) {
    const solidSlots = Math.max(0, actualSlots - 1);
    visible.push(...solids.slice(0, solidSlots));
    visible.push(...ghosts.slice(0, actualSlots - visible.length));
  } else {
    visible.push(...solids.slice(0, actualSlots));
  }

  const visibleIds = new Set(visible.map((candidate) => candidate.id));
  const overflow = ranked.filter((candidate) => !visibleIds.has(candidate.id));
  return { visible, overflow, rankById };
}

function frontierHandoffs(captures: TrajectoryCapture[], headBurst: Burst, headCapture: TrajectoryCapture): TrajectoryCapture[] {
  const headBurstIds = new Set(headBurst.captures.map((capture) => capture.id));
  const headMs = timestampValue(headCapture.observedAt);
  const bySession = new Map<string, TrajectoryCapture>();

  for (const capture of captures) {
    if (capture.kind !== "handoff" || !capture.sessionId) continue;
    const captureMs = timestampValue(capture.observedAt);
    const inHeadBurst = headBurstIds.has(capture.id);
    const nearHead = Math.abs(captureMs - headMs) <= FRONTIER_DAYS * DAY_MS;
    if (!inHeadBurst && !nearHead) continue;
    const existing = bySession.get(capture.sessionId);
    if (!existing || captureMs >= timestampValue(existing.observedAt)) {
      bySession.set(capture.sessionId, capture);
    }
  }

  return [...bySession.values()].sort((left, right) => timestampValue(right.observedAt) - timestampValue(left.observedAt));
}

function rankedTasks(tasks: TrajectoryTask[], tiers: RankedTaskTier[]): SolidCandidate[] {
  const statusOrder = new Map<string, number>();
  tiers.forEach((tier, index) => {
    for (const status of Array.isArray(tier) ? tier : [tier]) {
      statusOrder.set(status, index);
    }
  });
  return tasks
    .filter((task) => statusOrder.has(task.status))
    .sort((left, right) => {
      const statusDelta = (statusOrder.get(left.status) ?? 99) - (statusOrder.get(right.status) ?? 99);
      if (statusDelta !== 0) return statusDelta;
      const priorityDelta = right.priority - left.priority;
      if (priorityDelta !== 0) return priorityDelta;
      return timestampValue(right.updatedAt) - timestampValue(left.updatedAt);
    })
    .map((task): SolidCandidate => ({
      id: `future:task:${task.id}`,
      source: "task",
      kind: "future-task",
      label: task.title,
      text: task.title,
      task,
      sortMs: timestampValue(task.updatedAt),
    }));
}

function ghostCandidates(captures: TrajectoryCapture[], nowMs: number, solids: SolidCandidate[]): GhostCandidate[] {
  let latestProjection: TrajectoryCapture | undefined;
  for (let index = captures.length - 1; index >= 0; index -= 1) {
    const capture = captures[index];
    if (capture.kind !== "projection") continue;
    const observedMs = timestampValue(capture.observedAt);
    if (observedMs > 0 && nowMs - observedMs > PROJECTION_TTL * DAY_MS) continue;
    latestProjection = capture;
    break;
  }
  if (!latestProjection) return [];
  const observedMs = timestampValue(latestProjection.observedAt);

  return cleanList(latestProjection.paths)
    .map((path, index): GhostCandidate => {
      const text = textValue(path.description, path.title, "Projected path");
      return {
        id: `future:ghost:${latestProjection.id}:${index}`,
        source: "ghost",
        kind: "future-ghost",
        label: textValue(path.title, path.description, "Projected path"),
        text,
        sourceCapture: latestProjection,
        sessionId: latestProjection.sessionId,
        observedAt: latestProjection.observedAt,
        path,
        confidence: clampConfidence(path.confidence),
        sortMs: observedMs,
      };
    })
    .filter((ghost) => !solids.some((solid) => jaccard(ghost.text, solid.text) >= JACCARD_THRESHOLD || jaccard(ghost.label, solid.text) >= JACCARD_THRESHOLD))
    .slice(0, GHOST_MAX);
}

function futureNode(candidate: SolidCandidate | GhostCandidate, rank: number): TrajectoryGraphNode {
  return {
    id: candidate.id,
    kind: candidate.kind,
    label: candidate.label,
    eyebrow: futureEyebrow(candidate),
    fullText: candidate.text,
    rank,
    sourceCapture: candidate.sourceCapture,
    sessionId: candidate.sessionId,
    observedAt: candidate.observedAt,
    task: candidate.task,
    path: candidate.path,
    confidence: candidate.confidence,
  };
}

function futureEyebrow(candidate: SolidCandidate | GhostCandidate): string {
  if (candidate.source === "nextAction") return "next action";
  if (candidate.source === "openLoop") return "open loop";
  if (candidate.source === "task") return candidate.task?.status ?? "task";
  return "projection";
}

function dedupeNewest<T extends { text: string; sortMs: number }>(candidates: T[]): T[] {
  const sorted = [...candidates].sort((left, right) => right.sortMs - left.sortMs);
  const kept: T[] = [];
  for (const candidate of sorted) {
    if (!kept.some((existing) => jaccard(candidate.text, existing.text) >= JACCARD_THRESHOLD)) {
      kept.push(candidate);
    }
  }
  return kept;
}

function dedupeRanked<T extends { text: string }>(candidates: T[]): T[] {
  const kept: T[] = [];
  for (const candidate of candidates) {
    if (!kept.some((existing) => jaccard(candidate.text, existing.text) >= JACCARD_THRESHOLD)) {
      kept.push(candidate);
    }
  }
  return kept;
}

function jaccard(left: string, right: string): number {
  const leftTokens = tokens(left);
  const rightTokens = tokens(right);
  if (!leftTokens.size || !rightTokens.size) return 0;
  let intersection = 0;
  for (const token of leftTokens) {
    if (rightTokens.has(token)) intersection += 1;
  }
  const union = new Set([...leftTokens, ...rightTokens]).size;
  return union ? intersection / union : 0;
}

function tokens(value: string): Set<string> {
  return new Set(value.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean));
}

function withLabelExtents(node: PositionedNode, xOffset: number): TrajectoryLayoutNode {
  const halfWidth = labelHalfWidth(node);
  const x = node.x + xOffset;
  return {
    ...node,
    x,
    labelHalfWidth: halfWidth,
    labelLeft: x - halfWidth,
    labelRight: x + halfWidth,
  };
}

function labelHalfWidth(node: TrajectoryGraphNode): number {
  const labelWidth = textPixelWidth(renderedLabel(node), node.kind === "head" ? 11 : 10);
  const eyebrowWidth = textPixelWidth(node.eyebrow ?? "", 8);
  return Math.ceil(Math.max(labelWidth, eyebrowWidth) / 2 + shapeHalfWidth(node.kind) + 4);
}

function textPixelWidth(value: string, fontSize: number): number {
  return value.length * fontSize * 0.62;
}

function shapeHalfWidth(kind: TrajectoryNodeKind): number {
  if (kind === "future-task") return 42;
  if (kind === "future-more") return 36;
  if (kind === "head") return 14;
  if (kind === "burst") return 9;
  if (kind === "deep-past") return 7;
  if (kind === "stub") return 6;
  if (kind === "future-ghost") return 11;
  return 10;
}

function renderedLabel(node: TrajectoryGraphNode): string {
  if (node.kind === "future-task") return clampText(node.label, 16);
  if (node.kind.startsWith("future-") && node.kind !== "future-more") return clampText(node.label, 24);
  if (node.kind === "head") return clampText(node.label, 28);
  return node.label;
}

function clampText(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 3)}...`;
}

function dateRangeLabel(startMs: number, endMs: number): string {
  const start = dateParts(startMs);
  const end = dateParts(endMs);
  if (start.year === end.year && start.month === end.month && start.day === end.day) {
    return `${start.monthName} ${start.day}`;
  }
  if (start.year === end.year && start.month === end.month) {
    return `${start.monthName} ${start.day}-${end.day}`;
  }
  if (start.year === end.year) {
    return `${start.monthName} ${start.day}-${end.monthName} ${end.day}`;
  }
  return `${start.monthName} ${start.day} ${start.year}-${end.monthName} ${end.day} ${end.year}`;
}

function dateParts(ms: number): { year: number; month: number; day: number; monthName: string } {
  const date = new Date(ms || 0);
  const month = date.getUTCMonth();
  return {
    year: date.getUTCFullYear(),
    month,
    day: date.getUTCDate(),
    monthName: ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][month],
  };
}

function ageLabel(value: string | null | undefined, nowMs: number): string {
  const ms = timestampValue(value);
  if (!ms) return "unknown";
  const ageMs = Math.max(0, nowMs - ms);
  if (ageMs < HOUR_MS) return `${Math.max(1, Math.floor(ageMs / (60 * 1000)))}m`;
  if (ageMs < DAY_MS) return `${Math.floor(ageMs / HOUR_MS)}h`;
  if (ageMs < 7 * DAY_MS) return `${Math.floor(ageMs / DAY_MS)}d`;
  if (ageMs < 30 * DAY_MS) return `${Math.floor(ageMs / (7 * DAY_MS))}w`;
  if (ageMs < 365 * DAY_MS) return `${Math.floor(ageMs / (30 * DAY_MS))}mo`;
  return `${Math.floor(ageMs / (365 * DAY_MS))}y`;
}

function timestampValue(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}

function textValue(...values: Array<string | null | undefined>): string {
  for (const value of values) {
    const cleaned = cleanText(value);
    if (cleaned) return cleaned;
  }
  return "";
}

function cleanText(value: string | null | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed || null;
}

function cleanList<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value.filter((item) => item !== null && item !== undefined) : [];
}

function clampConfidence(value: number | null | undefined): number | null {
  if (typeof value !== "number" || Number.isNaN(value)) return null;
  return Math.min(1, Math.max(0, value));
}

function plural(word: string, count: number): string {
  return count === 1 ? word : `${word}s`;
}
