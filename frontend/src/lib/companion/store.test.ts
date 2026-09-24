import { createEffect, createRoot, createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import type { AgentEvent, AgentSession, EventFeedItem, ProjectSummary } from "../api";
import type { EventAppended, LiveStatus, LiveStore, SessionUpdated } from "../sse";
import { UNASSIGNED_KEY } from "./model";
import { createSeenStore } from "./seen";
import { createCompanionStore, MODE_STORAGE_KEY, TICK_MS, type CompanionDeps } from "./store";

const NOW = Date.parse("2026-09-24T12:00:00Z");
const iso = (offsetMs: number) => new Date(NOW - offsetMs).toISOString();

const projectA: ProjectSummary = {
  projectKey: "keyA",
  canonicalKey: "/repo/a",
  label: "/repo/a",
  sessionCount: 1,
  eventCount: 1,
  savedMeldCount: 0,
  firstSeenAt: iso(86_400_000),
  lastSeenAt: iso(0),
  scopes: [{ projectKey: "keyA", canonicalKey: "/repo/a", label: "/repo/a", primary: true }],
};

const sessionA: AgentSession = { id: "s1", source: "claude", clientSessionId: "c1", title: "t", cwd: "/repo/a", startedAt: iso(600_000), lastSeenAt: iso(20_000), eventCount: 5 };

const decision: EventFeedItem = { id: "d1", sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Decision", text: "Pick A", cwd: "/repo/a", observedAt: iso(60_000) };

function fakeLive() {
  const [status, setStatus] = createSignal<LiveStatus>("connecting");
  const eventListeners = new Set<(event: EventAppended) => void>();
  const sessionListeners = new Set<(event: SessionUpdated) => void>();
  const live: LiveStore = {
    status,
    events: () => [],
    onEventAppended: (callback) => { eventListeners.add(callback); return () => eventListeners.delete(callback); },
    onSessionUpdated: (callback) => { sessionListeners.add(callback); return () => sessionListeners.delete(callback); },
  };
  return {
    live,
    setStatus,
    emitEvent: (event: EventAppended) => { for (const listener of eventListeners) listener(event); },
    emitSession: (event: SessionUpdated) => { for (const listener of sessionListeners) listener(event); },
  };
}

class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() { return this.map.size; }
  clear() { this.map.clear(); }
  getItem(key: string) { return this.map.get(key) ?? null; }
  key(index: number) { return [...this.map.keys()][index] ?? null; }
  removeItem(key: string) { this.map.delete(key); }
  setItem(key: string, value: string) { this.map.set(key, value); }
}

function deps(overrides: Partial<CompanionDeps> = {}): CompanionDeps {
  return {
    getProjects: vi.fn(async () => [projectA]),
    getSessions: vi.fn(async () => [sessionA]),
    getEventFeed: vi.fn(async () => ({ items: [decision] })),
    getEvent: vi.fn(async (id: string): Promise<AgentEvent> => ({ id, sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Handoff", text: "Handoff to next-session: wired", metadata: { contextSummary: "wired", nextAction: "verify" }, observedAt: iso(1_000) })),
    seen: createSeenStore(null),
    storage: null,
    now: () => NOW,
    sleep: async () => {},
    ...overrides,
  };
}

// Stores created after an await need their own owner, or Solid never disposes their effects and timer.
function owned<T>(create: () => T): { value: T; dispose: () => void } {
  return createRoot((dispose) => ({ value: create(), dispose }));
}

async function settled<T>(read: () => T, predicate: (value: T) => boolean): Promise<void> {
  await vi.waitFor(() => { if (!predicate(read())) throw new Error("not yet"); });
}

describe("createCompanionStore", () => {
  it("loads projects, sessions and meaningful events into one model", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      expect(store.model().pulse).toBe("live");
      expect(store.model().projects).toHaveLength(1);
      expect(store.model().projects[0]).toMatchObject({ key: "keyA", liveSessions: 1, unseen: 1 });
      expect(store.mode()).toBe("mini");
      dispose();
    });
  });

  it("merges a live meaningful event by fetching its full body", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "h1", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(d.getEvent).toHaveBeenCalledWith("h1");
      expect(store.model().river[0]).toMatchObject({ id: "h1", headline: "wired", nextAction: "verify", projectKey: "keyA" });
      expect(store.model().unseenTotal).toBe(2);
      dispose();
    });
  });

  it("retries a failed live event fetch before giving up", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const getEvent = vi
        .fn()
        .mockRejectedValueOnce(new Error("404"))
        .mockRejectedValueOnce(new Error("404"))
        .mockResolvedValueOnce({ id: "h1", sessionId: "s1", source: "claude", clientSessionId: "c1", eventType: "Handoff", text: "wired", metadata: {}, observedAt: iso(1_000) } satisfies AgentEvent);
      const store = createCompanionStore(live, deps({ getEvent, sleep: async () => {} }));
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "h1", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(getEvent).toHaveBeenCalledTimes(3);
      dispose();
    });
  });

  it("gives up on a live event fetch after exhausting its retries", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const getEvent = vi.fn().mockRejectedValue(new Error("404"));
      const store = createCompanionStore(live, deps({ getEvent, sleep: async () => {} }));
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "h1", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/a" });
      await vi.waitFor(() => expect(getEvent).toHaveBeenCalledTimes(3));
      expect(store.model().river).toHaveLength(1);
      dispose();
    });
  });

  it("waits for an in-flight catalog refresh before attributing a live item", async () => {
    await createRoot(async (dispose) => {
      const projectB: ProjectSummary = {
        ...projectA,
        projectKey: "keyB",
        canonicalKey: "/repo/b",
        label: "/repo/b",
        scopes: [{ projectKey: "keyB", canonicalKey: "/repo/b", label: "/repo/b", primary: true }],
      };
      const getProjects = vi.fn(async (): Promise<ProjectSummary[]> => [projectA]);
      let resolveProjects!: (value: ProjectSummary[]) => void;
      let clock = NOW;
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps({ getProjects, now: () => clock }));
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      store.openProject("keyB"); // no card yet; just parks the view for the assertion below
      clock = NOW + 61_000; // past the catalog refresh throttle
      getProjects.mockImplementation(() => new Promise((resolve) => { resolveProjects = resolve; }));
      emitEvent({ id: "h1", sessionId: "s2", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/b" });
      // The event's getEvent() resolves well before the catalog fetch does; attribution must still wait.
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(store.model().river.find((item) => item.id === "h1")).toBeUndefined();
      resolveProjects([projectA, projectB]);
      await settled(() => store.model().river.find((item) => item.id === "h1")?.projectKey, (key) => key === "keyB");
      dispose();
    });
  });

  it("ignores non-meaningful events except for liveness", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      emitEvent({ id: "t1", sessionId: "s9", source: "codex", eventType: "PostToolUse", observedAt: iso(0), cwd: "/repo/a" });
      expect(d.getEvent).not.toHaveBeenCalled();
      expect(store.model().projects[0].liveSessions).toBe(2);
      expect(store.model().lastEventAt).toBe(iso(0));
      dispose();
    });
  });

  it("does not mark a live item seen while the page is hidden, and catches it up on becoming visible", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      store.openProject("keyA");
      expect(store.model().unseenTotal).toBe(0);
      Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
      emitEvent({ id: "h2", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(500), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(store.model().unseenTotal).toBe(1);
      Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
      document.dispatchEvent(new Event("visibilitychange"));
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
  });

  it("marks items seen when a project is opened and when items arrive while it is open", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      store.openProject("keyA");
      expect(store.mode()).toBe("expanded");
      expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA", projectName: "a" });
      expect(store.model().unseenTotal).toBe(0);
      emitEvent({ id: "h2", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(500), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
  });

  it("marks items seen once loaded into a persisted expanded project view after relaunch", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      storage.setItem(MODE_STORAGE_KEY, JSON.stringify({ mode: "expanded", expanded: { kind: "project", projectKey: "keyA" } }));
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      expect(store.mode()).toBe("expanded");
      expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA", projectName: "a" });
      expect(store.model().river).toHaveLength(1);
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
  });

  it("marks items seen once loaded into a persisted river view after relaunch", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      storage.setItem(MODE_STORAGE_KEY, JSON.stringify({ mode: "expanded", expanded: { kind: "river" } }));
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
  });

  it("batches openProject's state writes into a single persisted write", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      const setItemSpy = vi.spyOn(storage, "setItem");
      store.openProject("keyA");
      expect(setItemSpy).toHaveBeenCalledTimes(1);
      expect(JSON.parse(storage.getItem(MODE_STORAGE_KEY) ?? "{}")).toEqual({ mode: "expanded", expanded: { kind: "project", projectKey: "keyA", projectName: "a" } });
      dispose();
    });
  });

  it("batches refresh()'s session writes into one model recompute", async () => {
    await createRoot(async (dispose) => {
      const manySessions: AgentSession[] = Array.from({ length: 50 }, (_, index) => ({
        id: `s${index}`,
        source: "claude",
        clientSessionId: `c${index}`,
        title: "t",
        cwd: "/repo/a",
        startedAt: iso(600_000),
        lastSeenAt: iso(20_000),
        eventCount: 1,
      }));
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ getSessions: vi.fn(async () => manySessions) }));
      let recomputes = 0;
      createEffect(() => {
        store.model();
        recomputes += 1;
      });
      await settled(store.loading, (loading) => !loading);
      // One recompute for the initial empty model plus at most one more for the whole refresh, not
      // one per session written.
      expect(recomputes).toBeLessThanOrEqual(2);
      dispose();
    });
  });

  it("retries a failed load on the next tick while the stream stays live", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    try {
      await createRoot(async (dispose) => {
        const getProjects = vi.fn().mockRejectedValueOnce(new Error("500")).mockResolvedValue([projectA]);
        const { live } = fakeLive();
        const store = createCompanionStore(live, deps({ getProjects }));
        await settled(store.loading, (loading) => !loading);
        expect(store.error()).toBe("500");
        await vi.advanceTimersByTimeAsync(TICK_MS);
        expect(store.error()).toBeNull();
        expect(store.model().projects).toHaveLength(1);
        dispose();
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it("refetches after reconnect", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus } = fakeLive();
      const d = deps();
      const store = createCompanionStore(live, d);
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      setStatus("down");
      expect(store.model().pulse).toBe("disconnected");
      expect(store.model().projects).toHaveLength(1);
      setStatus("live");
      await settled(() => (d.getEventFeed as ReturnType<typeof vi.fn>).mock.calls.length, (calls) => calls === 2);
      dispose();
    });
  });

  it("persists mode and expanded view, and survives a throwing storage", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      store.openRiver();
      expect(JSON.parse(storage.getItem(MODE_STORAGE_KEY) ?? "{}")).toEqual({ mode: "expanded", expanded: { kind: "river" } });
      const reloaded = owned(() => createCompanionStore(fakeLive().live, deps({ storage })));
      expect(reloaded.value.mode()).toBe("expanded");
      expect(reloaded.value.expanded()).toEqual({ kind: "river" });
      reloaded.dispose();

      const throwing = new MemoryStorage();
      throwing.setItem = () => { throw new Error("blocked"); };
      const guarded = owned(() => createCompanionStore(fakeLive().live, deps({ storage: throwing })));
      guarded.value.setMode("compact");
      expect(guarded.value.mode()).toBe("compact");
      guarded.dispose();
      dispose();
    });
  });

  it("posts pulse, unseen and mode to the shell bridge when present", async () => {
    const postMessage = vi.fn();
    const shellWindow = window as unknown as { webkit?: unknown };
    shellWindow.webkit = { messageHandlers: { companion: { postMessage } } };
    try {
      await createRoot(async (dispose) => {
        const { live, setStatus } = fakeLive();
        const store = createCompanionStore(live, deps());
        await settled(store.loading, (loading) => !loading);
        setStatus("live");
        expect(postMessage).toHaveBeenLastCalledWith({ type: "state", pulse: "live", unseen: 1 });
        store.setMode("compact");
        expect(postMessage).toHaveBeenLastCalledWith({ type: "mode", mode: "compact", width: 340, height: 420 });
        dispose();
      });
    } finally {
      delete shellWindow.webkit;
    }
  });

  it("reports a measured mini width to the shell without shrinking below the default", async () => {
    const postMessage = vi.fn();
    const shellWindow = window as unknown as { webkit?: unknown };
    shellWindow.webkit = { messageHandlers: { companion: { postMessage } } };
    try {
      await createRoot(async (dispose) => {
        const { live } = fakeLive();
        const store = createCompanionStore(live, deps());
        await settled(store.loading, (loading) => !loading);
        expect(postMessage).toHaveBeenCalledWith({ type: "mode", mode: "mini", width: 132, height: 36 });
        store.reportMiniWidth(168);
        expect(postMessage).toHaveBeenLastCalledWith({ type: "mode", mode: "mini", width: 168, height: 36 });
        store.reportMiniWidth(90);
        expect(postMessage).toHaveBeenLastCalledWith({ type: "mode", mode: "mini", width: 132, height: 36 });
        dispose();
      });
    } finally {
      delete shellWindow.webkit;
    }
  });

  it("keeps item identity and skips shell state posts across tool-call frames", async () => {
    const postMessage = vi.fn();
    const shellWindow = window as unknown as { webkit?: unknown };
    shellWindow.webkit = { messageHandlers: { companion: { postMessage } } };
    try {
      await createRoot(async (dispose) => {
        const { live, setStatus, emitEvent } = fakeLive();
        const store = createCompanionStore(live, deps());
        await settled(store.loading, (loading) => !loading);
        setStatus("live");
        const item = store.model().river[0];
        const posts = postMessage.mock.calls.length;
        emitEvent({ id: "t1", sessionId: "s1", source: "codex", eventType: "PostToolUse", observedAt: iso(0), cwd: "/repo/a" });
        expect(store.model().lastEventAt).toBe(iso(0));
        expect(store.model().river[0]).toBe(item);
        expect(postMessage).toHaveBeenCalledTimes(posts);
        emitEvent({ id: "h1", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(1_000), cwd: "/repo/a" });
        await settled(() => store.model().river.length, (length) => length === 2);
        expect(store.model().river.find((entry) => entry.id === "d1")).toBe(item);
        dispose();
      });
    } finally {
      delete shellWindow.webkit;
    }
  });

  it("refreshes the project catalog for an unknown cwd at most once a minute", async () => {
    await createRoot(async (dispose) => {
      let clock = NOW;
      const projectB: ProjectSummary = {
        ...projectA,
        projectKey: "keyB",
        canonicalKey: "/repo/b",
        label: "/repo/b",
        scopes: [{ projectKey: "keyB", canonicalKey: "/repo/b", label: "/repo/b", primary: true }],
      };
      const getProjects = vi.fn(async (): Promise<ProjectSummary[]> => [projectA]);
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps({ getProjects, now: () => clock }));
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      getProjects.mockImplementation(async () => [projectA, projectB]);
      clock = NOW + 61_000;
      emitEvent({ id: "t2", sessionId: "s7", source: "codex", eventType: "PostToolUse", observedAt: new Date(clock).toISOString(), cwd: "/repo/b" });
      await settled(() => store.model().projects.map((card) => card.key), (keys) => keys.includes("keyB"));
      expect(store.model().projects.some((card) => card.key === UNASSIGNED_KEY)).toBe(false);
      emitEvent({ id: "t3", sessionId: "s8", source: "codex", eventType: "PostToolUse", observedAt: new Date(clock).toISOString(), cwd: "/repo/c" });
      expect(getProjects).toHaveBeenCalledTimes(2);
      dispose();
    });
  });

  it("ages items out of the 24h window on the tick", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    try {
      let clock = NOW;
      await createRoot(async (dispose) => {
        const store = createCompanionStore(fakeLive().live, deps({ now: () => clock }));
        await settled(store.loading, (loading) => !loading);
        expect(store.model().river.map((item) => item.id)).toEqual(["d1"]);
        clock = NOW + 24 * 3_600_000;
        vi.advanceTimersByTime(TICK_MS);
        expect(store.model().river).toEqual([]);
        expect(store.model().projects).toEqual([]);
        dispose();
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it("falls back to an active project, then the river, when the remembered project has aged out", async () => {
    await createRoot(async (dispose) => {
      const storage = new MemoryStorage();
      storage.setItem(MODE_STORAGE_KEY, JSON.stringify({ mode: "expanded", expanded: { kind: "project", projectKey: "stale" } }));
      const { live } = fakeLive();
      const store = createCompanionStore(live, deps({ storage }));
      await settled(store.loading, (loading) => !loading);
      // The persisted view names a project no longer in the model; it must not open blank.
      expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA", projectName: "a" });

      // toggleExpandedView must also not reopen a stale lastProjectKey when no project is active.
      const empty = owned(() =>
        createCompanionStore(
          fakeLive().live,
          deps({ getProjects: vi.fn(async () => []), getSessions: vi.fn(async () => []), getEventFeed: vi.fn(async () => ({ items: [] })) }),
        ),
      );
      await settled(empty.value.loading, (loading) => !loading);
      empty.value.openProject("keyZ"); // no matching card; sets lastProjectKey to a key that doesn't exist
      empty.value.openRiver();
      empty.value.toggleExpandedView();
      expect(empty.value.expanded()).toEqual({ kind: "river" });
      empty.dispose();
      dispose();
    });
  });

  it("keeps a project's real name in the expanded view after its card ages out of the model", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    try {
      let clock = NOW;
      await createRoot(async (dispose) => {
        const store = createCompanionStore(fakeLive().live, deps({ now: () => clock }));
        await settled(store.loading, (loading) => !loading);
        store.openProject("keyA");
        expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA", projectName: "a" });
        clock = NOW + 24 * 3_600_000; // ages the item out; keyA also has no live session by then
        vi.advanceTimersByTime(TICK_MS);
        expect(store.model().projects.find((project) => project.key === "keyA")).toBeUndefined();
        // The view was never told to leave keyA and still carries its real name.
        expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA", projectName: "a" });
        dispose();
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it("steps down one level at a time", async () => {
    await createRoot(async (dispose) => {
      const store = createCompanionStore(fakeLive().live, deps());
      await settled(store.loading, (loading) => !loading);
      store.openRiver();
      store.stepDown();
      expect(store.mode()).toBe("compact");
      store.stepDown();
      expect(store.mode()).toBe("mini");
      store.stepDown();
      expect(store.mode()).toBe("mini");
      dispose();
    });
  });
});
