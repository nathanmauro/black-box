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
  close: () => void;
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
  const latestVersions = new Map<string, number>();
  const liveMutationSequence = new Map<string, number>();
  let refreshInFlight: Promise<void> | null = null;
  let reconnectRefreshInFlight: Promise<void> | null = null;
  let mutationSequence = 0;
  let filterRevision = 0;
  let reconnectRefreshPending = false;
  let closed = false;

  const eventSourceFactory = options.eventSourceFactory ?? defaultEventSourceFactory();
  const source = eventSourceFactory?.("/api/stream") ?? null;

  if (!source) {
    setStatus("down");
  } else {
    source.onopen = () => {
      if (closed) return;
      setStatus("live");
      if (!reconnectRefreshPending) return;
      reconnectRefreshPending = false;
      void refreshAfterReconnect().catch(() => {
        if (!closed) setStatus("down");
      });
    };
    source.onerror = () => {
      if (closed) return;
      setStatus("down");
      reconnectRefreshPending = true;
    };
    for (const eventType of TASK_EVENT_TYPES) {
      source.addEventListener(eventType, (message) => {
        const frame = parseTaskLifecycleFrame(message);
        if (!frame || frame.transitionType !== eventType) return;
        applyFrame(frame);
      });
    }
  }

  function tasks(): AgentTask[] {
    return orderTasks([...records().values()].map(({ task }) => task)).slice(0, filters().limit ?? 100);
  }

  function snapshots(): TaskSnapshot[] {
    const values: TaskSnapshot[] = [];
    for (const record of records().values()) {
      if (record.spec) values.push({ task: record.task, spec: record.spec });
    }
    return orderSnapshots(values).slice(0, filters().limit ?? 100);
  }

  async function refresh(): Promise<void> {
    if (closed) return;
    if (refreshInFlight) return refreshInFlight;

    const requestedFilters = filters();
    const requestedRevision = filterRevision;
    const refreshStartedAtMutation = mutationSequence;
    refreshInFlight = (async () => {
      const snapshot = await loadTasks(requestedFilters);
      if (closed || requestedRevision !== filterRevision) return;
      setRecords((current) => mergeSnapshot(
        snapshot,
        current,
        latestVersions,
        liveMutationSequence,
        refreshStartedAtMutation,
        requestedFilters,
      ));
    })();

    try {
      await refreshInFlight;
    } finally {
      refreshInFlight = null;
    }
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

  async function refreshAfterReconnect(): Promise<void> {
    if (reconnectRefreshInFlight) return reconnectRefreshInFlight;
    reconnectRefreshInFlight = (async () => {
      if (refreshInFlight) {
        try {
          await refreshInFlight;
        } catch {
          // The reconnect refresh below is the bounded recovery attempt.
        }
        await Promise.resolve();
      }
      await refresh();
    })();
    try {
      await reconnectRefreshInFlight;
    } finally {
      reconnectRefreshInFlight = null;
    }
  }

  function applyFrame(frame: TaskLifecycleFrame): void {
    if (closed || seenTransitions.has(frame.transitionId)) return;
    seenTransitions.add(frame.transitionId);
    trimSeenTransitions(seenTransitions);

    const version = instantValue(frame.task.updatedAt);
    const latestVersion = latestVersions.get(frame.task.id);
    if (latestVersion !== undefined && version < latestVersion) return;
    latestVersions.set(frame.task.id, version);
    mutationSequence += 1;
    liveMutationSequence.set(frame.task.id, mutationSequence);

    setRecords((current) => {
      const next = new Map(current);
      const previous = current.get(frame.task.id);
      const previousVersion = previous ? instantValue(previous.task.updatedAt) : Number.NEGATIVE_INFINITY;
      if (version < previousVersion) return current;

      if (matchesFilters(frame.task, filters())) {
        next.set(frame.task.id, { task: frame.task, spec: previous?.spec });
      } else {
        next.delete(frame.task.id);
      }
      return next;
    });
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
  latestVersions: Map<string, number>,
  liveMutationSequence: Map<string, number>,
  refreshStartedAtMutation: number,
  filters: TaskFilters,
): Map<string, TaskRecord> {
  const next = new Map<string, TaskRecord>();
  for (const item of snapshot) {
    const snapshotVersion = instantValue(item.task.updatedAt);
    const knownVersion = latestVersions.get(item.task.id);
    const currentRecord = current.get(item.task.id);
    if (knownVersion !== undefined && knownVersion > snapshotVersion) {
      if (currentRecord && matchesFilters(currentRecord.task, filters)) next.set(item.task.id, currentRecord);
      continue;
    }
    latestVersions.set(item.task.id, snapshotVersion);
    next.set(item.task.id, item);
  }

  for (const [id, record] of current) {
    const incoming = next.get(id);
    if (incoming && instantValue(incoming.task.updatedAt) >= instantValue(record.task.updatedAt)) continue;
    const changedDuringRefresh = (liveMutationSequence.get(id) ?? 0) > refreshStartedAtMutation;
    if ((incoming || changedDuringRefresh) && matchesFilters(record.task, filters)) next.set(id, record);
  }
  return next;
}

function orderTasks(tasks: AgentTask[]): AgentTask[] {
  return tasks.sort((left, right) => (
    right.priority - left.priority
    || instantValue(left.createdAt) - instantValue(right.createdAt)
    || left.id.localeCompare(right.id)
  ));
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
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function instantValue(value: string): number {
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
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
