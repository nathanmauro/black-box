import { afterEach, describe, expect, it, vi } from "vitest";
import type { AgentTask, TaskSnapshot } from "./api";
import { createTaskLiveStore, type TaskEventSource, type TaskLifecycleFrame } from "./tasks";

class FakeEventSource implements TaskEventSource {
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  private listeners = new Map<string, Array<(event: Event) => void>>();

  addEventListener(type: string, listener: (event: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close() {}

  emit(type: string, data: string) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data }));
    }
  }
}

const spec = {
  id: "spec-1",
  projectKey: "black-box",
  title: "Agent loop",
  body: "Frozen contract",
  specRef: null,
  status: "active" as const,
  createdBy: "planner",
  createdAt: "2026-07-10T00:00:00Z",
  updatedAt: "2026-07-10T00:00:00Z",
};

function task(overrides: Partial<AgentTask> = {}): AgentTask {
  return {
    id: "task-1",
    specId: spec.id,
    projectKey: spec.projectKey,
    title: "Build the Board",
    lane: "codex",
    status: "open",
    priority: 7,
    createdBy: "planner",
    claimedBy: null,
    blockedReason: null,
    resultHandoffId: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:00:00Z",
    ...overrides,
  };
}

function snapshot(value: AgentTask): TaskSnapshot {
  return { task: value, spec };
}

function frame(
  value: AgentTask,
  transitionId = "transition-1",
  transitionType: TaskLifecycleFrame["transitionType"] = value.status === "done" ? "task.completed" : "task.claimed",
): TaskLifecycleFrame {
  return {
    task: value,
    transitionId,
    transitionType,
    observedAt: value.updatedAt,
  };
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("task live store", () => {
  it("starts from an ordered REST snapshot", async () => {
    const later = task({ id: "task-later", priority: 2, createdAt: "2026-07-10T00:01:00Z" });
    const highest = task({ id: "task-high", priority: 9 });
    const loadTasks = vi.fn(async () => [snapshot(later), snapshot(highest)]);
    const source = new FakeEventSource();

    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    expect(store.tasks().map(({ id }) => id)).toEqual(["task-high", "task-later"]);
    expect(store.snapshots()).toEqual([snapshot(highest), snapshot(later)]);
    store.close();
  });

  it("applies a lifecycle frame idempotently", async () => {
    const open = task();
    const claimed = task({ status: "in_progress", claimedBy: "worker-1", updatedAt: "2026-07-10T00:01:00Z" });
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks: async () => [snapshot(open)],
      eventSourceFactory: () => source,
    });
    await store.refresh();

    source.emit("task.claimed", JSON.stringify(frame(claimed)));
    source.emit("task.claimed", JSON.stringify(frame(claimed)));

    expect(store.tasks()).toEqual([claimed]);
    expect(store.tasks()).toHaveLength(1);
    store.close();
  });

  it("does not let an older out-of-order event overwrite newer task state", async () => {
    const claimed = task({ status: "in_progress", claimedBy: "worker-1", updatedAt: "2026-07-10T00:02:00Z" });
    const older = task({ status: "open", updatedAt: "2026-07-10T00:01:00Z" });
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks: async () => [snapshot(claimed)],
      eventSourceFactory: () => source,
    });
    await store.refresh();

    source.emit("task.created", JSON.stringify(frame(older, "transition-old")));

    expect(store.tasks()).toEqual([claimed]);
    store.close();
  });

  it("preserves backend Instant sub-millisecond ordering", async () => {
    const newer = task({ status: "in_progress", claimedBy: "worker-1", updatedAt: "2026-07-10T00:02:00.000999Z" });
    const older = task({ status: "open", updatedAt: "2026-07-10T00:02:00.000001Z" });
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks: async () => [snapshot(newer)],
      eventSourceFactory: () => source,
    });
    await store.refresh();

    source.emit("task.created", JSON.stringify(frame(older, "transition-sub-ms-old", "task.created")));

    expect(store.tasks()).toEqual([newer]);
    store.close();
  });

  it("refreshes once after reconnect and then resumes event application", async () => {
    const open = task();
    const claimed = task({ status: "in_progress", claimedBy: "worker-1", updatedAt: "2026-07-10T00:01:00Z" });
    const done = task({ status: "done", claimedBy: "worker-1", resultHandoffId: "handoff-1", updatedAt: "2026-07-10T00:02:00Z" });
    const loadTasks = vi.fn(async () => [snapshot(loadTasks.mock.calls.length > 1 ? claimed : open)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    source.onerror?.(new Event("error"));
    source.onerror?.(new Event("error"));
    source.onopen?.(new Event("open"));
    source.onopen?.(new Event("open"));
    await flush();

    expect(loadTasks).toHaveBeenCalledTimes(2);
    expect(store.status()).toBe("live");
    source.emit("task.completed", JSON.stringify(frame(done, "transition-done")));
    expect(store.tasks()).toEqual([done]);
    store.close();
  });

  it("queues a bounded refresh for each distinct reconnect cycle", async () => {
    const open = task();
    let resolveFirstRecovery: ((value: TaskSnapshot[]) => void) | undefined;
    const firstRecovery = new Promise<TaskSnapshot[]>((resolve) => {
      resolveFirstRecovery = resolve;
    });
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([snapshot(open)])
      .mockReturnValueOnce(firstRecovery)
      .mockResolvedValueOnce([snapshot(open)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    source.onerror?.(new Event("error"));
    source.onerror?.(new Event("error"));
    source.onopen?.(new Event("open"));
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(2));

    source.onerror?.(new Event("error"));
    source.onerror?.(new Event("error"));
    source.onopen?.(new Event("open"));
    expect(loadTasks).toHaveBeenCalledTimes(2);

    resolveFirstRecovery?.([snapshot(open)]);
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(3));

    expect(loadTasks).toHaveBeenCalledTimes(3);
    store.close();
  });

  it("keeps a newer sub-millisecond live event that arrives while its snapshot is in flight", async () => {
    const open = task({ updatedAt: "2026-07-10T00:02:00.000001Z" });
    const done = task({
      status: "done",
      claimedBy: "worker-1",
      updatedAt: "2026-07-10T00:02:00.000999Z",
    });
    let resolveRecovery: ((value: TaskSnapshot[]) => void) | undefined;
    const recovery = new Promise<TaskSnapshot[]>((resolve) => {
      resolveRecovery = resolve;
    });
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([snapshot(open)])
      .mockReturnValueOnce(recovery);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    const refreshing = store.refresh();
    source.emit("task.completed", JSON.stringify(frame(done, "transition-during-refresh")));
    resolveRecovery?.([snapshot(open)]);
    await refreshing;

    expect(store.tasks()).toEqual([done]);
    store.close();
  });

  it("lets a live mutation after refresh start win an exact Instant tie", async () => {
    const open = task({ updatedAt: "2026-07-10T00:02:00.000999Z" });
    const claimed = task({
      status: "in_progress",
      claimedBy: "worker-1",
      updatedAt: "2026-07-10T00:02:00.000999Z",
    });
    let resolveRecovery: ((value: TaskSnapshot[]) => void) | undefined;
    const recovery = new Promise<TaskSnapshot[]>((resolve) => {
      resolveRecovery = resolve;
    });
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([snapshot(open)])
      .mockReturnValueOnce(recovery);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    const refreshing = store.refresh();
    source.emit("task.claimed", JSON.stringify(frame(claimed, "transition-exact-tie")));
    resolveRecovery?.([snapshot(open)]);
    await refreshing;

    expect(store.tasks()).toEqual([claimed]);
    store.close();
  });

  it("treats a completed snapshot refresh as authoritative for stale rows", async () => {
    const open = task();
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([snapshot(open)])
      .mockResolvedValueOnce([]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    await store.refresh();

    expect(store.tasks()).toEqual([]);
    store.close();
  });

  it("recovers once for malformed known frames while unknown frames stay ignored", async () => {
    const open = task();
    const loadTasks = vi.fn(async () => [snapshot(open)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks,
      eventSourceFactory: () => source,
    });
    await store.refresh();

    source.emit("task.surprised", JSON.stringify(frame(task({ status: "blocked" }), "unknown")));
    await flush();
    expect(loadTasks).toHaveBeenCalledTimes(1);

    source.emit("task.claimed", "{not-json");
    source.emit("task.claimed", JSON.stringify({ transitionId: "missing-task" }));
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(2));
    await flush();

    expect(store.tasks()).toEqual([open]);
    expect(loadTasks).toHaveBeenCalledTimes(2);
    store.close();
  });

  it("queues one more malformed-frame recovery after the active snapshot point", async () => {
    const open = task();
    let resolveFirstRecovery: ((value: TaskSnapshot[]) => void) | undefined;
    const firstRecovery = new Promise<TaskSnapshot[]>((resolve) => {
      resolveFirstRecovery = resolve;
    });
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([snapshot(open)])
      .mockReturnValueOnce(firstRecovery)
      .mockResolvedValueOnce([snapshot(open)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    source.emit("task.claimed", "{first-malformed-frame");
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(2));
    source.emit("task.claimed", "{second-malformed-frame");

    resolveFirstRecovery?.([snapshot(open)]);
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(3));

    expect(loadTasks).toHaveBeenCalledTimes(3);
    store.close();
  });

  it("hydrates an unseen created task with its frozen spec", async () => {
    const created = task();
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([snapshot(created)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    source.emit("task.created", JSON.stringify(frame(created, "transition-created", "task.created")));
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(2));
    await flush();

    expect(store.tasks()).toEqual([created]);
    expect(store.snapshots()).toEqual([snapshot(created)]);
    store.close();
  });

  it("queues hydration for a second create after the active snapshot point", async () => {
    const createdA = task({ id: "task-a", title: "Task A", updatedAt: "2026-07-10T00:01:00.000001Z" });
    const createdB = task({ id: "task-b", title: "Task B", updatedAt: "2026-07-10T00:01:00.000002Z" });
    let resolveFirstHydration: ((value: TaskSnapshot[]) => void) | undefined;
    const firstHydration = new Promise<TaskSnapshot[]>((resolve) => {
      resolveFirstHydration = resolve;
    });
    const loadTasks = vi.fn()
      .mockResolvedValueOnce([])
      .mockReturnValueOnce(firstHydration)
      .mockResolvedValueOnce([snapshot(createdA), snapshot(createdB)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    source.emit("task.created", JSON.stringify(frame(createdA, "transition-created-a", "task.created")));
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(2));
    source.emit("task.created", JSON.stringify(frame(createdB, "transition-created-b", "task.created")));

    resolveFirstHydration?.([snapshot(createdA)]);
    await vi.waitFor(() => expect(loadTasks).toHaveBeenCalledTimes(3));
    await flush();

    expect(store.tasks()).toEqual([createdA, createdB]);
    expect(store.snapshots()).toEqual([snapshot(createdA), snapshot(createdB)]);
    store.close();
  });

  it("bounds records and version metadata during long-lived task streams", async () => {
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks: async () => [],
      eventSourceFactory: () => source,
    });
    await store.refresh();

    for (let index = 0; index < 1_500; index += 1) {
      store.applyFrame(frame(task({
        id: `task-${index}`,
        status: "in_progress",
        claimedBy: "worker-1",
        updatedAt: `2026-07-10T00:02:${String(index % 60).padStart(2, "0")}.${String(index).padStart(6, "0")}Z`,
      }), `transition-${index}`));
    }

    expect(store.tasks()).toHaveLength(100);
    expect(store.diagnostics()).toEqual({
      records: 100,
      versions: 1_000,
      liveMutations: 1_000,
      transitions: 1_000,
    });
    store.close();
  });

  it("applies filters without mutating the task and refreshes their snapshot", async () => {
    const open = task();
    const loadTasks = vi.fn(async () => [snapshot(open)]);
    const source = new FakeEventSource();
    const store = createTaskLiveStore({ loadTasks, eventSourceFactory: () => source });
    await store.refresh();

    await store.setFilters({ projectKey: "black-box", lane: "codex", status: "open", limit: 25 });

    expect(loadTasks).toHaveBeenLastCalledWith({ projectKey: "black-box", lane: "codex", status: "open", limit: 25 });
    expect(store.filters()).toEqual({ projectKey: "black-box", lane: "codex", status: "open", limit: 25 });
    expect(store.tasks()[0]).toEqual(open);
    store.close();
  });
});
