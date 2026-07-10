import { createSignal } from "solid-js";
import {
  listTasks,
  type AgentTask,
  type Spec,
  type TaskEventType,
  type TaskFilters,
  type TaskSnapshot,
  type TaskStatus,
} from "./api";
import { parseSseData, type LiveStatus } from "./sse";

const TASK_EVENT_TYPES = [
  "task.created",
  "task.claimed",
  "task.blocked",
  "task.completed",
  "task.reset",
  "task.cancelled",
] as const satisfies readonly TaskEventType[];

const TASK_STATUSES = new Set<TaskStatus>([
  "open",
  "claimed",
  "in_progress",
  "blocked",
  "done",
  "cancelled",
]);

const DEFAULT_TASK_LIMIT = 100;
const MAX_TASK_LIMIT = 250;
const MAX_TRACKED_TASKS = 1_000;

export type TaskLifecycleFrame = {
  task: AgentTask;
  transitionId: string;
  transitionType: TaskEventType;
  observedAt: string;
};

export type TaskEventSource = {
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener: (type: string, listener: (event: Event) => void) => void;
  close: () => void;
};

export type TaskLiveStore = {
  status: () => LiveStatus;
  tasks: () => AgentTask[];
  snapshots: () => TaskSnapshot[];
  filters: () => TaskFilters;
  setFilters: (filters: TaskFilters) => Promise<void>;
  refresh: () => Promise<void>;
  applyFrame: (frame: TaskLifecycleFrame) => void;
  diagnostics: () => TaskLiveStoreDiagnostics;
  close: () => void;
};

export type TaskLiveStoreDiagnostics = {
  records: number;
  versions: number;
  liveMutations: number;
  transitions: number;
};

export type TaskLiveStoreOptions = {
  initialFilters?: TaskFilters;
  loadTasks?: (filters: TaskFilters) => Promise<TaskSnapshot[]>;
  eventSourceFactory?: (url: string) => TaskEventSource;
};

type TaskRecord = {
  task: AgentTask;
  spec?: Spec;
};

export function createTaskLiveStore(options: TaskLiveStoreOptions = {}): TaskLiveStore {
  const loadTasks = options.loadTasks ?? listTasks;
  const [status, setStatus] = createSignal<LiveStatus>("connecting");
  const [filters, setFiltersSignal] = createSignal<TaskFilters>(normalizeFilters(options.initialFilters ?? {}));
  const [records, setRecords] = createSignal<Map<string, TaskRecord>>(new Map());
  const seenTransitions = new Set<string>();
  const latestVersions = new Map<string, string>();
  const liveMutationSequence = new Map<string, number>();
  let refreshInFlight: Promise<void> | null = null;
  let recoveryLoop: Promise<void> | null = null;
  let mutationSequence = 0;
  let filterRevision = 0;
  let recoveryGeneration = 0;
  let recoveredGeneration = 0;
  let reconnectGapOpen = false;
  const pendingRecoveryKeys = new Map<string, number>();
  let closed = false;

  const eventSourceFactory = options.eventSourceFactory ?? defaultEventSourceFactory();
  const source = eventSourceFactory?.("/api/stream") ?? null;

  if (!source) {
    setStatus("down");
  } else {
    source.onopen = () => {
      if (closed) return;
      setStatus("live");
      if (!reconnectGapOpen) return;
      reconnectGapOpen = false;
      requestAuthoritativeRecovery();
    };
    source.onerror = () => {
      if (closed) return;
      setStatus("down");
      reconnectGapOpen = true;
    };
    for (const eventType of TASK_EVENT_TYPES) {
      source.addEventListener(eventType, (message) => {
        const frame = parseTaskLifecycleFrame(message);
        if (!frame || frame.transitionType !== eventType) {
          requestAuthoritativeRecovery("malformed-task-frame");
          return;
        }
        applyFrame(frame);
      });
    }
  }

  function tasks(): AgentTask[] {
    return orderTasks([...records().values()].map(({ task }) => task));
  }

  function snapshots(): TaskSnapshot[] {
    const values: TaskSnapshot[] = [];
    for (const record of records().values()) {
      if (record.spec) values.push({ task: record.task, spec: record.spec });
    }
    return orderSnapshots(values);
  }

  function refresh(): Promise<void> {
    if (closed) return Promise.resolve();
    if (refreshInFlight) return refreshInFlight;

    const requestedFilters = filters();
    const requestedRevision = filterRevision;
    const refreshStartedAtMutation = mutationSequence;
    const operation = (async () => {
      const snapshot = await loadTasks(requestedFilters);
      if (closed || requestedRevision !== filterRevision) return;
      setRecords((current) => compactState(mergeSnapshot(
        snapshot,
        current,
        latestVersions,
        liveMutationSequence,
        refreshStartedAtMutation,
        requestedFilters,
      ), requestedFilters));
    })();
    let tracked: Promise<void>;
    tracked = operation.finally(() => {
      if (refreshInFlight === tracked) refreshInFlight = null;
    });
    refreshInFlight = tracked;
    return tracked;
  }

  async function setFilters(next: TaskFilters): Promise<void> {
    const normalized = normalizeFilters(next);
    if (sameFilters(filters(), normalized)) return;
    setFiltersSignal(normalized);
    filterRevision += 1;
    if (refreshInFlight) {
      try {
        await refreshInFlight;
      } catch {
        // The new filter still gets its own refresh even when an older request failed.
      }
    }
    await refresh();
  }

  function requestAuthoritativeRecovery(key?: string): void {
    if (closed || (key !== undefined && pendingRecoveryKeys.has(key))) return;
    recoveryGeneration += 1;
    if (key !== undefined) pendingRecoveryKeys.set(key, recoveryGeneration);
    ensureRecoveryLoop();
  }

  function ensureRecoveryLoop(): void {
    if (closed || recoveryLoop) return;
    const operation = runRecoveryLoop().catch(() => {
      if (!closed) setStatus("down");
    });
    let tracked: Promise<void>;
    tracked = operation.finally(() => {
      if (recoveryLoop !== tracked) return;
      recoveryLoop = null;
      if (!closed && recoveredGeneration < recoveryGeneration) ensureRecoveryLoop();
    });
    recoveryLoop = tracked;
  }

  async function runRecoveryLoop(): Promise<void> {
    while (!closed && recoveredGeneration < recoveryGeneration) {
      const targetGeneration = recoveryGeneration;
      const activeRefresh = refreshInFlight;
      if (activeRefresh) {
        try {
          await activeRefresh;
        } catch {
          // Recovery still gets one fresh bounded attempt after an older refresh fails.
        }
      }
      try {
        await refresh();
      } catch {
        if (!closed) setStatus("down");
      }
      recoveredGeneration = targetGeneration;
      for (const [key, generation] of pendingRecoveryKeys) {
        if (generation <= recoveredGeneration) pendingRecoveryKeys.delete(key);
      }
    }
  }

  function applyFrame(frame: TaskLifecycleFrame): void {
    if (closed || seenTransitions.has(frame.transitionId)) return;
    seenTransitions.add(frame.transitionId);
    trimSeenTransitions(seenTransitions);

    const version = instantKey(frame.task.updatedAt);
    if (!version) return;
    const latestVersion = latestVersions.get(frame.task.id);
    if (latestVersion !== undefined && compareInstantKeys(version, latestVersion) < 0) return;
    touchMap(latestVersions, frame.task.id, version);
    mutationSequence += 1;
    touchMap(liveMutationSequence, frame.task.id, mutationSequence);
    const hadFrozenSpec = records().get(frame.task.id)?.spec !== undefined;

    setRecords((current) => {
      const next = new Map(current);
      const previous = current.get(frame.task.id);
      const previousVersion = previous ? instantKey(previous.task.updatedAt) : null;
      if (previousVersion && compareInstantKeys(version, previousVersion) < 0) return current;

      if (matchesFilters(frame.task, filters())) {
        next.set(frame.task.id, { task: frame.task, spec: previous?.spec });
      } else {
        next.delete(frame.task.id);
      }
      return compactState(next, filters());
    });

    if (frame.transitionType === "task.created"
      && !hadFrozenSpec
      && matchesFilters(frame.task, filters())) {
      requestAuthoritativeRecovery("hydrate-created-task");
    }
  }

  function compactState(next: Map<string, TaskRecord>, activeFilters: TaskFilters): Map<string, TaskRecord> {
    const limit = taskLimit(activeFilters);
    const ordered = [...next.values()].sort((left, right) => compareTasks(left.task, right.task)).slice(0, limit);
    const compacted = new Map(ordered.map((record) => [record.task.id, record]));
    const protectedIds = new Set(compacted.keys());
    pruneMap(latestVersions, protectedIds, MAX_TRACKED_TASKS);
    pruneMap(liveMutationSequence, protectedIds, MAX_TRACKED_TASKS);
    return compacted;
  }

  function diagnostics(): TaskLiveStoreDiagnostics {
    return {
      records: records().size,
      versions: latestVersions.size,
      liveMutations: liveMutationSequence.size,
      transitions: seenTransitions.size,
    };
  }

  function close(): void {
    if (closed) return;
    closed = true;
    source?.close();
    setStatus("down");
  }

  const store: TaskLiveStore = {
    status,
    tasks,
    snapshots,
    filters,
    setFilters,
    refresh,
    applyFrame,
    diagnostics,
    close,
  };

  void refresh().catch(() => {
    // Consumers can retry explicitly; connection state continues to describe SSE only.
  });

  return store;
}

export function parseTaskLifecycleFrame(message: Event): TaskLifecycleFrame | null {
  const value = parseSseData<unknown>(message);
  if (!isRecord(value) || !isAgentTask(value.task)) return null;
  if (!isNonemptyString(value.transitionId) || !isTaskEventType(value.transitionType)) return null;
  if (!isValidInstant(value.observedAt)) return null;
  return {
    task: value.task,
    transitionId: value.transitionId,
    transitionType: value.transitionType,
    observedAt: value.observedAt,
  };
}

function mergeSnapshot(
  snapshot: TaskSnapshot[],
  current: Map<string, TaskRecord>,
  latestVersions: Map<string, string>,
  liveMutationSequence: Map<string, number>,
  refreshStartedAtMutation: number,
  filters: TaskFilters,
): Map<string, TaskRecord> {
  const next = new Map<string, TaskRecord>();
  for (const item of snapshot) {
    const snapshotVersion = instantKey(item.task.updatedAt);
    if (!snapshotVersion) continue;
    const knownVersion = latestVersions.get(item.task.id);
    const currentRecord = current.get(item.task.id);
    const changedDuringRefresh = (liveMutationSequence.get(item.task.id) ?? 0) > refreshStartedAtMutation;
    const knownComparison = knownVersion === undefined ? 0 : compareInstantKeys(knownVersion, snapshotVersion);
    if (knownVersion !== undefined && (knownComparison > 0 || (knownComparison === 0 && changedDuringRefresh))) {
      if (currentRecord && matchesFilters(currentRecord.task, filters)) next.set(item.task.id, currentRecord);
      continue;
    }
    touchMap(latestVersions, item.task.id, snapshotVersion);
    next.set(item.task.id, item);
  }

  for (const [id, record] of current) {
    const incoming = next.get(id);
    const changedDuringRefresh = (liveMutationSequence.get(id) ?? 0) > refreshStartedAtMutation;
    if (incoming) {
      const comparison = compareInstants(incoming.task.updatedAt, record.task.updatedAt);
      if (comparison > 0 || (comparison === 0 && !changedDuringRefresh)) continue;
    }
    if ((incoming !== undefined || changedDuringRefresh) && matchesFilters(record.task, filters)) next.set(id, record);
  }
  return next;
}

function orderTasks(tasks: AgentTask[]): AgentTask[] {
  return tasks.sort(compareTasks);
}

function compareTasks(left: AgentTask, right: AgentTask): number {
  return right.priority - left.priority
    || compareInstants(left.createdAt, right.createdAt)
    || left.id.localeCompare(right.id);
}

function orderSnapshots(snapshots: TaskSnapshot[]): TaskSnapshot[] {
  const taskOrder = new Map(orderTasks(snapshots.map(({ task }) => task)).map((task, index) => [task.id, index]));
  return snapshots.sort((left, right) => (taskOrder.get(left.task.id) ?? 0) - (taskOrder.get(right.task.id) ?? 0));
}

function normalizeFilters(filters: TaskFilters): TaskFilters {
  const normalized: TaskFilters = {};
  if (filters.projectKey !== undefined) normalized.projectKey = filters.projectKey;
  if (filters.lane !== undefined) normalized.lane = filters.lane;
  if (filters.status !== undefined) normalized.status = filters.status;
  if (filters.limit !== undefined) normalized.limit = filters.limit;
  return normalized;
}

function sameFilters(left: TaskFilters, right: TaskFilters): boolean {
  return left.projectKey === right.projectKey
    && left.lane === right.lane
    && left.status === right.status
    && left.limit === right.limit;
}

function matchesFilters(task: AgentTask, filters: TaskFilters): boolean {
  return (filters.projectKey === undefined || task.projectKey === filters.projectKey)
    && (filters.lane === undefined || task.lane === filters.lane)
    && (filters.status === undefined || task.status === filters.status);
}

function isAgentTask(value: unknown): value is AgentTask {
  if (!isRecord(value)) return false;
  return isNonemptyString(value.id)
    && isNonemptyString(value.specId)
    && isNonemptyString(value.projectKey)
    && isNonemptyString(value.title)
    && isNonemptyString(value.lane)
    && typeof value.status === "string"
    && TASK_STATUSES.has(value.status as TaskStatus)
    && typeof value.priority === "number"
    && Number.isFinite(value.priority)
    && isNonemptyString(value.createdBy)
    && isNullableString(value.claimedBy)
    && isNullableString(value.blockedReason)
    && isNullableString(value.resultHandoffId)
    && isValidInstant(value.createdAt)
    && isValidInstant(value.updatedAt);
}

function isTaskEventType(value: unknown): value is TaskEventType {
  return typeof value === "string" && (TASK_EVENT_TYPES as readonly string[]).includes(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonemptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isNullableString(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || typeof value === "string";
}

function isValidInstant(value: unknown): value is string {
  return typeof value === "string" && instantKey(value) !== null;
}

function instantKey(value: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match || !isValidUtcSecond(match.slice(1, 7).map(Number))) return null;
  const base = `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6]}`;
  return `${base}.${(match[7] ?? "").padEnd(9, "0")}Z`;
}

function isValidUtcSecond(parts: number[]): boolean {
  const [year, month, day, hour, minute, second] = parts;
  if (year === undefined || month === undefined || day === undefined
    || hour === undefined || minute === undefined || second === undefined) return false;
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
    && date.getUTCHours() === hour
    && date.getUTCMinutes() === minute
    && date.getUTCSeconds() === second;
}

function compareInstants(left: string, right: string): number {
  const leftKey = instantKey(left);
  const rightKey = instantKey(right);
  if (leftKey === rightKey) return 0;
  if (leftKey === null) return -1;
  if (rightKey === null) return 1;
  return compareInstantKeys(leftKey, rightKey);
}

function compareInstantKeys(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function taskLimit(filters: TaskFilters): number {
  return Math.max(1, Math.min(filters.limit ?? DEFAULT_TASK_LIMIT, MAX_TASK_LIMIT));
}

function touchMap<K, V>(map: Map<K, V>, key: K, value: V): void {
  map.delete(key);
  map.set(key, value);
}

function pruneMap<V>(map: Map<string, V>, protectedIds: Set<string>, maximum: number): void {
  if (map.size <= maximum) return;
  for (const key of map.keys()) {
    if (map.size <= maximum) break;
    if (!protectedIds.has(key)) map.delete(key);
  }
}

function trimSeenTransitions(seen: Set<string>): void {
  if (seen.size <= 1_000) return;
  const oldest = seen.values().next().value;
  if (oldest !== undefined) seen.delete(oldest);
}

function defaultEventSourceFactory(): ((url: string) => TaskEventSource) | null {
  if (typeof EventSource === "undefined") return null;
  return (url) => new EventSource(url) as TaskEventSource;
}
