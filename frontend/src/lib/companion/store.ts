import { createEffect, createMemo, createSignal, on, onCleanup, type Accessor } from "solid-js";
import { getEvent, getEventFeed, getProjects, getSessions, type AgentEvent, type AgentSession, type EventFeedItem, type ProjectSummary } from "../api";
import type { EventAppended, LiveStore, SessionUpdated } from "../sse";
import { modeMessage, postToShell } from "./bridge";
import { deriveModel, MEANINGFUL_EVENT_TYPES, MEANINGFUL_QUERY, type CompanionMode, type CompanionModel, type ExpandedViewState, type SessionLiveness } from "./model";
import { createSeenStore, safeStorage, type SeenStore } from "./seen";

export const MODE_STORAGE_KEY = "blackbox.companion.mode.v1";
export const TICK_MS = 30_000;

export type CompanionDeps = {
  getProjects: () => Promise<ProjectSummary[]>;
  getSessions: (limit: number, includeChildren: boolean) => Promise<AgentSession[]>;
  getEventFeed: (params: { q: string; limit: number }) => Promise<{ items: EventFeedItem[] }>;
  getEvent: (id: string) => Promise<AgentEvent>;
  seen: SeenStore;
  storage: Storage | null;
  now: () => number;
};

export type CompanionStore = {
  model: Accessor<CompanionModel>;
  mode: Accessor<CompanionMode>;
  expanded: Accessor<ExpandedViewState>;
  loading: Accessor<boolean>;
  error: Accessor<string | null>;
  setMode(mode: CompanionMode): void;
  openProject(projectKey: string): void;
  openRiver(): void;
  toggleExpandedView(): void;
  stepDown(): void;
  refresh(): Promise<void>;
};

export function defaultDeps(): CompanionDeps {
  const storage = safeStorage();
  return { getProjects, getSessions, getEventFeed, getEvent, seen: createSeenStore(storage), storage, now: () => Date.now() };
}

type PersistedMode = { mode: CompanionMode; expanded: ExpandedViewState };
const MODES: CompanionMode[] = ["mini", "compact", "expanded"];

function timestamp(iso: string | null | undefined): number {
  const value = iso ? Date.parse(iso) : Number.NaN;
  return Number.isNaN(value) ? 0 : value;
}

function loadMode(storage: Storage | null): PersistedMode {
  const fallback: PersistedMode = { mode: "mini", expanded: { kind: "river" } };
  if (!storage) return fallback;
  try {
    const parsed = JSON.parse(storage.getItem(MODE_STORAGE_KEY) ?? "null") as { mode?: unknown; expanded?: { kind?: unknown; projectKey?: unknown } } | null;
    if (!parsed || !MODES.includes(parsed.mode as CompanionMode)) return fallback;
    const expanded: ExpandedViewState =
      parsed.expanded?.kind === "project" && typeof parsed.expanded.projectKey === "string"
        ? { kind: "project", projectKey: parsed.expanded.projectKey }
        : { kind: "river" };
    return { mode: parsed.mode as CompanionMode, expanded };
  } catch {
    return fallback;
  }
}

function persistMode(storage: Storage | null, value: PersistedMode): void {
  if (!storage) return;
  try {
    storage.setItem(MODE_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // Blocked storage: mode still lives in memory for this page.
  }
}

export function createCompanionStore(live: LiveStore, deps: CompanionDeps = defaultDeps()): CompanionStore {
  const [projects, setProjects] = createSignal<ProjectSummary[]>([]);
  const [sessions, setSessions] = createSignal<Map<string, SessionLiveness>>(new Map(), { equals: false });
  const [events, setEvents] = createSignal<Map<string, EventFeedItem>>(new Map(), { equals: false });
  const [lastEventAt, setLastEventAt] = createSignal<string | null>(null);
  const [now, setNow] = createSignal(deps.now());
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<string | null>(null);
  const persisted = loadMode(deps.storage);
  const [mode, setModeSignal] = createSignal<CompanionMode>(persisted.mode);
  const [expanded, setExpanded] = createSignal<ExpandedViewState>(persisted.expanded);
  const [lastProjectKey, setLastProjectKey] = createSignal<string | null>(persisted.expanded.kind === "project" ? persisted.expanded.projectKey : null);

  const model = createMemo(() =>
    deriveModel({
      now: now(),
      connection: live.status(),
      lastEventAt: lastEventAt(),
      projects: projects(),
      sessions: [...sessions().values()],
      events: [...events().values()],
      seen: deps.seen.seen(),
    }),
  );

  function bumpLastEvent(iso: string | null | undefined): void {
    if (iso && timestamp(iso) > timestamp(lastEventAt())) setLastEventAt(iso);
  }

  function upsertSession(next: SessionLiveness): void {
    setSessions((map) => {
      const current = map.get(next.id);
      if (!current || timestamp(next.lastSeenAt) >= timestamp(current.lastSeenAt)) {
        map.set(next.id, { ...next, cwd: next.cwd ?? current?.cwd ?? null });
      }
      return map;
    });
  }

  function addEvent(item: EventFeedItem): void {
    setEvents((map) => {
      map.set(item.id, item);
      return map;
    });
    const view = expanded();
    if (mode() !== "expanded") return;
    const card = model().projects.find((project) => project.items.some((entry) => entry.id === item.id));
    if (!card) return;
    if (view.kind === "river" || view.projectKey === card.key) deps.seen.markAll([item.id]);
  }

  async function refresh(): Promise<void> {
    setLoading(true);
    try {
      const [projectList, sessionList, feed] = await Promise.all([
        deps.getProjects(),
        deps.getSessions(250, true),
        deps.getEventFeed({ q: MEANINGFUL_QUERY, limit: 200 }),
      ]);
      setProjects(projectList);
      for (const session of sessionList) {
        upsertSession({ id: session.id, cwd: session.cwd ?? null, lastSeenAt: session.lastSeenAt });
        bumpLastEvent(session.lastSeenAt);
      }
      setEvents((map) => {
        for (const item of feed.items) map.set(item.id, item);
        return map;
      });
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  const stopEvents = live.onEventAppended((event: EventAppended) => {
    bumpLastEvent(event.observedAt);
    upsertSession({ id: event.sessionId, cwd: event.cwd ?? null, lastSeenAt: event.observedAt });
    if (!(event.eventType in MEANINGFUL_EVENT_TYPES)) return;
    void deps
      .getEvent(event.id)
      .then((full) => addEvent({ ...full, cwd: event.cwd ?? null, sessionTitle: event.title ?? null }))
      .catch(() => {
        // The next refresh (reconnect or reload) backfills anything missed here.
      });
  });
  const stopSessions = live.onSessionUpdated((event: SessionUpdated) => {
    if (!event.lastSeenAt) return;
    upsertSession({ id: event.sessionId, cwd: event.cwd ?? null, lastSeenAt: event.lastSeenAt });
    bumpLastEvent(event.lastSeenAt);
  });
  const timer = setInterval(() => setNow(deps.now()), TICK_MS);
  onCleanup(() => {
    stopEvents();
    stopSessions();
    clearInterval(timer);
  });

  createEffect(
    on(
      live.status,
      (status, previous) => {
        if (status === "live" && previous === "down") void refresh();
      },
      { defer: true },
    ),
  );
  createEffect(() => {
    const current = model();
    postToShell({ type: "state", pulse: current.pulse, unseen: current.unseenTotal });
  });
  createEffect(() => {
    postToShell(modeMessage(mode()));
  });
  createEffect(() => {
    persistMode(deps.storage, { mode: mode(), expanded: expanded() });
  });

  function openProject(projectKey: string): void {
    setExpanded({ kind: "project", projectKey });
    setLastProjectKey(projectKey);
    setModeSignal("expanded");
    const card = model().projects.find((project) => project.key === projectKey);
    if (card) deps.seen.markAll(card.items.map((item) => item.id));
  }

  function openRiver(): void {
    setExpanded({ kind: "river" });
    setModeSignal("expanded");
    deps.seen.markAll(model().river.map((item) => item.id));
  }

  function toggleExpandedView(): void {
    if (expanded().kind === "river") {
      const key = lastProjectKey() ?? model().projects[0]?.key;
      if (key) openProject(key);
      return;
    }
    openRiver();
  }

  function stepDown(): void {
    if (mode() === "expanded") setModeSignal("compact");
    else if (mode() === "compact") setModeSignal("mini");
  }

  void refresh();

  return { model, mode, expanded, loading, error, setMode: setModeSignal, openProject, openRiver, toggleExpandedView, stepDown, refresh };
}
