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

function frame(value: AgentTask, transitionId = "transition-1"): TaskLifecycleFrame {
  return {
    task: value,
    transitionId,
    transitionType: value.status === "done" ? "task.completed" : "task.claimed",
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

  it("keeps a live event that arrives while its recovery snapshot is in flight", async () => {
    const open = task();
    const done = task({ status: "done", claimedBy: "worker-1", updatedAt: "2026-07-10T00:02:00Z" });
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

  it("ignores malformed and unknown lifecycle frames", async () => {
    const open = task();
    const source = new FakeEventSource();
    const store = createTaskLiveStore({
      loadTasks: async () => [snapshot(open)],
      eventSourceFactory: () => source,
    });
    await store.refresh();

    source.emit("task.claimed", "{not-json");
    source.emit("task.claimed", JSON.stringify({ transitionId: "missing-task" }));
    source.emit("task.surprised", JSON.stringify(frame(task({ status: "blocked" }), "unknown")));

    expect(store.tasks()).toEqual([open]);
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
