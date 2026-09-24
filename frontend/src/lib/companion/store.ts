import { batch, createEffect, createMemo, createSignal, on, onCleanup, type Accessor } from "solid-js";
import { getEvent, getEventFeed, getProjects, getSessions, type AgentEvent, type AgentSession, type EventFeedItem, type ProjectSummary } from "../api";
import { findProjectByIdentifier } from "../projects";
import type { EventAppended, LiveStore, SessionUpdated } from "../sse";
import { modeMessage, postToShell } from "./bridge";
import {
  ACTIVE_SESSION_WINDOW_MS,
  composeModel,
  deriveItems,
  isMeaningfulEventType,
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
  sleep: (ms: number) => Promise<void>;
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
  reportMiniWidth(width: number): void;
};

export function defaultDeps(): CompanionDeps {
  const storage = safeStorage();
  return {
    getProjects,
    getSessions,
    getEventFeed,
    getEvent,
    seen: createSeenStore(storage),
    storage,
    now: () => Date.now(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  };
}

// A completion Handoff's SSE frame can beat the transaction that made it visible (docs/architecture.md);
// a handful of short retries covers that window instead of silently dropping the item.
const EVENT_FETCH_ATTEMPTS = 3;
const EVENT_FETCH_RETRY_DELAY_MS = 600;

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
  const [miniWidth, setMiniWidth] = createSignal<number | null>(null);
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

  function visibleItemIds(): string[] {
    const view = expanded();
    const current = model();
    return view.kind === "river" ? current.river.map((item) => item.id) : current.projects.find((project) => project.key === view.projectKey)?.items.map((item) => item.id) ?? [];
  }

  // A hidden panel or a backgrounded tab must never look like quiet: "expanded" alone does not mean
  // the view is actually being looked at, so pageVisible also gates on document.visibilityState.
  function pageVisible(): boolean {
    return typeof document === "undefined" || document.visibilityState !== "hidden";
  }

  // Spec 3.4: opened equals seen. Whatever landed in the currently open view — a live event merged
  // in, or a batch loaded by refresh() into a view that was already open (e.g. a persisted expanded
  // view restored on relaunch) — is marked seen once it is visible there.
  function markVisibleSeen(ids: Iterable<string>): void {
    if (mode() !== "expanded" || !pageVisible()) return;
    const visible = new Set(visibleItemIds());
    const toMark = [...ids].filter((id) => visible.has(id));
    if (toMark.length) deps.seen.markAll(toMark);
  }

  function addEvent(item: EventFeedItem): void {
    setEvents((map) => {
      map.set(item.id, item);
      return map;
    });
    markVisibleSeen([item.id]);
  }

  let catalogFetchedAt = Number.NEGATIVE_INFINITY;

  // New repos and worktrees join the catalog server-side; refetch it, at most once a minute, when
  // live activity names a cwd the cached catalog cannot resolve. Returns the in-flight fetch so a
  // caller attributing an event to a project can wait for it instead of racing it.
  function refreshCatalogFor(cwd: string | null | undefined): Promise<void> {
    if (!cwd || findProjectByIdentifier(projects(), cwd)) return Promise.resolve();
    const current = deps.now();
    if (current - catalogFetchedAt < CATALOG_REFRESH_MS) return Promise.resolve();
    catalogFetchedAt = current;
    return deps
      .getProjects()
      .then((next) => {
        setProjects(next);
      })
      .catch(() => {
        // Keep the cached catalog; the next unknown cwd after the throttle retries.
      });
  }

  // Retries a live event's fetch a few times before giving up, so a Handoff whose SSE frame beat
  // the transaction that made it visible (docs/architecture.md) is not silently dropped.
  async function fetchEventWithRetry(id: string): Promise<AgentEvent> {
    let lastError: unknown;
    for (let attempt = 0; attempt < EVENT_FETCH_ATTEMPTS; attempt++) {
      try {
        return await deps.getEvent(id);
      } catch (cause) {
        lastError = cause;
        if (attempt < EVENT_FETCH_ATTEMPTS - 1) await deps.sleep(EVENT_FETCH_RETRY_DELAY_MS);
      }
    }
    throw lastError;
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
      batch(() => {
        setProjects(projectList);
        for (const session of sessionList) {
          upsertSession({ id: session.id, cwd: session.cwd ?? null, lastSeenAt: session.lastSeenAt });
          bumpLastEvent(session.lastSeenAt);
        }
        setEvents((map) => {
          for (const item of feed.items) map.set(item.id, item);
          return map;
        });
        markVisibleSeen(feed.items.map((item) => item.id));
        setError(null);
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  const stopEvents = live.onEventAppended((event: EventAppended) => {
    bumpLastEvent(event.observedAt);
    upsertSession({ id: event.sessionId, cwd: event.cwd ?? null, lastSeenAt: event.observedAt });
    const catalogReady = refreshCatalogFor(event.cwd);
    if (!isMeaningfulEventType(event.eventType)) return;
    // Wait for both: the full event body (retried) and any in-flight catalog refresh for its cwd,
    // so a brand-new worktree's first item is attributed to its real project, not Unassigned.
    void Promise.all([fetchEventWithRetry(event.id), catalogReady])
      .then(([full]) => addEvent({ ...full, cwd: event.cwd ?? null, sessionTitle: event.title ?? null }))
      .catch(() => {
        // Retries exhausted; the next full refresh (reconnect or reload) backfills this item.
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
  function handleVisibilityChange(): void {
    if (pageVisible()) markVisibleSeen(visibleItemIds());
  }
  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", handleVisibilityChange);
    onCleanup(() => document.removeEventListener("visibilitychange", handleVisibilityChange));
  }
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
    postToShell(modeMessage(mode(), mode() === "mini" ? (miniWidth() ?? undefined) : undefined));
  });
  createEffect(() => {
    persistMode(deps.storage, { mode: mode(), expanded: expanded() });
  });

  // A persisted expanded view (relaunch, or a project that aged out mid-session before this ran)
  // can name a project no longer in the catalog; correct it once the first load resolves instead of
  // opening on a blank "Project" with no activity strip.
  let didValidateInitialView = false;
  createEffect(() => {
    if (didValidateInitialView || loading()) return;
    didValidateInitialView = true;
    const view = expanded();
    if (view.kind !== "project") return;
    const key = resolveProjectKey(view.projectKey);
    if (key === view.projectKey) return;
    batch(() => {
      if (key) {
        setExpanded({ kind: "project", projectKey: key });
        setLastProjectKey(key);
      } else {
        setExpanded({ kind: "river" });
      }
    });
  });

  function openProject(projectKey: string): void {
    const card = model().projects.find((project) => project.key === projectKey);
    batch(() => {
      setExpanded({ kind: "project", projectKey });
      setLastProjectKey(projectKey);
      setModeSignal("expanded");
      if (card) deps.seen.markAll(card.items.map((item) => item.id));
    });
  }

  function openRiver(): void {
    setExpanded({ kind: "river" });
    setModeSignal("expanded");
    deps.seen.markAll(model().river.map((item) => item.id));
  }

  // A remembered project key can age out (no live session, no meaningful item in 24h) between
  // visits; falling back to it unvalidated opens a blank view titled "Project" with no activity
  // strip even though other projects are active. Prefer the key only while it still has a card.
  function resolveProjectKey(preferred: string | null): string | null {
    if (preferred && model().projects.some((project) => project.key === preferred)) return preferred;
    return model().projects[0]?.key ?? null;
  }

  function toggleExpandedView(): void {
    if (expanded().kind === "river") {
      const key = resolveProjectKey(lastProjectKey());
      if (key) openProject(key);
      return;
    }
    openRiver();
  }

  // The mini chip's own width (its content, not the fixed default) is what keeps "connecting" plus
  // a two-digit unseen count from being clipped by the shell panel; see modeMessage in bridge.ts.
  function reportMiniWidth(width: number): void {
    if (!Number.isFinite(width) || width <= 0) return;
    const rounded = Math.ceil(width);
    if (rounded !== miniWidth()) setMiniWidth(rounded);
  }

  function stepDown(): void {
    if (mode() === "expanded") setModeSignal("compact");
    else if (mode() === "compact") setModeSignal("mini");
  }

  void refresh();

  return { model, mode, expanded, loading, error, setMode: setModeSignal, openProject, openRiver, toggleExpandedView, stepDown, refresh, reportMiniWidth };
}
