import { batch, createEffect, createMemo, createSignal, on, onCleanup, type Accessor } from "solid-js";
import { getEvent, getEventFeed, getProjects, getSessions, type AgentEvent, type AgentSession, type EventFeedItem, type ProjectSummary } from "../api";
import { findProjectByIdentifier } from "../projects";
import type { EventAppended, LiveStore, SessionUpdated } from "../sse";
import { modeMessage, postToShell } from "./bridge";
import {
  ACTIVE_SESSION_WINDOW_MS,
  composeModel,
  deriveItems,
  MEANINGFUL_EVENT_TYPES,
  MEANINGFUL_QUERY,
  MEANINGFUL_WINDOW_MS,
  type CompanionMode,
  type CompanionModel,
  type ExpandedViewState,
  type MeaningfulItem,
  type SessionLiveness,
} from "./model";
import { createSeenStore, safeStorage, type SeenStore } from "./seen";

export const MODE_STORAGE_KEY = "blackbox.companion.mode.v1";
export const TICK_MS = 30_000;
export const CATALOG_REFRESH_MS = 60_000;

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

function sameElements<T>(a: readonly T[], b: readonly T[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
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

  // Items only change with events, the catalog, seen-state, or the window; activity frames reuse them,
  // so rows keep their identity (and their DOM) while tool calls stream in.
  const items = createMemo<MeaningfulItem[]>(
    (previous) => deriveItems({ now: now(), projects: projects(), events: [...events().values()], seen: deps.seen.seen() }, previous),
    [],
    { equals: sameElements },
  );
  const model = createMemo(() =>
    composeModel({
      now: now(),
      connection: live.status(),
      lastEventAt: lastEventAt(),
      projects: projects(),
      sessions: [...sessions().values()],
      items: items(),
    }),
  );
  const shellState = createMemo(
    () => ({ pulse: model().pulse, unseen: model().unseenTotal }),
    undefined,
    { equals: (previous, next) => previous.pulse === next.pulse && previous.unseen === next.unseen },
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

  let catalogFetchedAt = Number.NEGATIVE_INFINITY;

  // New repos and worktrees join the catalog server-side; refetch it, at most once a minute, when
  // live activity names a cwd the cached catalog cannot resolve.
  function refreshCatalogFor(cwd: string | null | undefined): void {
    if (!cwd || findProjectByIdentifier(projects(), cwd)) return;
    const current = deps.now();
    if (current - catalogFetchedAt < CATALOG_REFRESH_MS) return;
    catalogFetchedAt = current;
    void deps
      .getProjects()
      .then(setProjects)
      .catch(() => {
        // Keep the cached catalog; the next unknown cwd after the throttle retries.
      });
  }

  async function refresh(): Promise<void> {
    setLoading(true);
    catalogFetchedAt = deps.now();
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
    refreshCatalogFor(event.cwd);
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
    refreshCatalogFor(event.cwd);
    bumpLastEvent(event.lastSeenAt);
  });
  // The tick ages the model; pruning keeps both maps bounded by their windows in a long-running shell.
  function prune(current: number): void {
    setEvents((map) => {
      for (const [id, item] of map) if (current - timestamp(item.observedAt) > MEANINGFUL_WINDOW_MS) map.delete(id);
      return map;
    });
    setSessions((map) => {
      for (const [id, session] of map) if (current - timestamp(session.lastSeenAt) > ACTIVE_SESSION_WINDOW_MS) map.delete(id);
      return map;
    });
  }
  const timer = setInterval(() => {
    const current = deps.now();
    batch(() => {
      prune(current);
      setNow(current);
    });
  }, TICK_MS);
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
    const state = shellState();
    postToShell({ type: "state", pulse: state.pulse, unseen: state.unseen });
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
