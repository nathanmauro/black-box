import { createRoot, createSignal } from "solid-js";
import { describe, expect, it, vi } from "vitest";
import type { AgentEvent, AgentSession, EventFeedItem, ProjectSummary } from "../api";
import type { EventAppended, LiveStatus, LiveStore, SessionUpdated } from "../sse";
import { createSeenStore } from "./seen";
import { createCompanionStore, MODE_STORAGE_KEY, type CompanionDeps } from "./store";

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

  it("marks items seen when a project is opened and when items arrive while it is open", async () => {
    await createRoot(async (dispose) => {
      const { live, setStatus, emitEvent } = fakeLive();
      const store = createCompanionStore(live, deps());
      await settled(store.loading, (loading) => !loading);
      setStatus("live");
      store.openProject("keyA");
      expect(store.mode()).toBe("expanded");
      expect(store.expanded()).toEqual({ kind: "project", projectKey: "keyA" });
      expect(store.model().unseenTotal).toBe(0);
      emitEvent({ id: "h2", sessionId: "s1", source: "claude", eventType: "Handoff", observedAt: iso(500), cwd: "/repo/a" });
      await settled(() => store.model().river.length, (length) => length === 2);
      expect(store.model().unseenTotal).toBe(0);
      dispose();
    });
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
