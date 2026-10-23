import {
  createEffect,
  createMemo,
  createSignal,
  For,
  Match,
  on,
  onCleanup,
  Show,
  Switch,
} from "solid-js";
import { getEventFeed, type EventFeedItem, type ProjectSummary } from "../../lib/api";
import { timeAgo } from "../../lib/format";
import { humanOnly, withHumanText } from "../../lib/humanOnly";
import { findProjectByIdentifier, primaryProjectScope, projectShortName } from "../../lib/projects";
import { LIVE_WINDOW_MS, stableWindowOrder } from "../../lib/projectWindows";
import { appendProjectGroupScope } from "../../lib/query";
import { useLiveStore, type EventAppended } from "../../lib/sse";
import { segmentStream, type FoldRow } from "../../lib/streamGroups";
import RunHeader from "../events/RunHeader";
import StreamFold from "../events/StreamFold";
import StreamRow from "../events/StreamRow";

const WINDOW_FEED_LIMIT = 30;
const WINDOW_MAX_ROWS = 200;
const LIVE_DEBOUNCE_MS = 500;
const FLASH_MS = 1400;
const CLOCK_TICK_MS = 30_000;
// An unknown cwd usually means a brand-new project or worktree; one catalog refresh per window
// of time is enough to give it a card.
const CATALOG_REFRESH_MS = 30_000;

// One live frame per project: a sequence number to trigger the head refetch, plus the newest
// observed time so the header age and live dot move without a catalog reload.
type Pulse = { seq: number; at: string };

type ProjectWindowGridProps = {
  projects: ProjectSummary[];
  // The visible query, without any project scope: every window applies it to its own project.
  query: string;
  hasVisibleFilter: boolean;
  // until: bounds the query in the past, so live arrivals cannot match it.
  livePaused: boolean;
  onOpenProject: (projectKey: string) => void;
  onRefreshProjects?: () => void;
};

export default function ProjectWindowGrid(props: ProjectWindowGridProps) {
  const live = useLiveStore();
  const [order, setOrder] = createSignal<string[]>([]);
  const [pulses, setPulses] = createSignal<Record<string, Pulse>>({});
  const [reloadRevision, setReloadRevision] = createSignal(0);
  const [now, setNow] = createSignal(Date.now());
  let catalogRefreshedAt = Number.NEGATIVE_INFINITY;

  createEffect(() => {
    setOrder((placed) => stableWindowOrder(placed, props.projects, Date.now()));
  });

  const windows = createMemo(() => {
    const byKey = new Map(props.projects.map((project) => [project.projectKey, project]));

    return order()
      .map((key) => byKey.get(key))
      .filter((project): project is ProjectSummary => Boolean(project));
  });

  const stopEvents = live.onEventAppended((event) => routeEvent(event));
  const stopReset = live.onReset?.(() => setReloadRevision((revision) => revision + 1));

  createEffect(
    on(
      live.status,
      (status, previous) => {
        if (status === "live" && previous === "down") {
          setReloadRevision((revision) => revision + 1);
        }
      },
      { defer: true },
    ),
  );
  const clock = setInterval(() => setNow(Date.now()), CLOCK_TICK_MS);

  onCleanup(() => {
    stopEvents();
    stopReset?.();
    clearInterval(clock);
  });

  function routeEvent(event: EventAppended) {
    const project = findProjectByIdentifier(props.projects, event.cwd);

    if (!project) {
      refreshCatalogOnce();

      return;
    }

    const key = project.projectKey;

    // A project quieter than the active window earns its card the moment it speaks; it joins
    // the end so nothing already placed moves.
    setOrder((placed) => (placed.includes(key) ? placed : [...placed, key]));
    setPulses((current) => {
      const previous = current[key];
      const at = newerTimestamp(previous?.at, event.observedAt) ?? event.observedAt;

      return { ...current, [key]: { seq: (previous?.seq ?? 0) + 1, at } };
    });
    setNow(Date.now());
  }

  function refreshCatalogOnce() {
    const current = Date.now();

    if (current - catalogRefreshedAt < CATALOG_REFRESH_MS) {
      return;
    }

    catalogRefreshedAt = current;
    props.onRefreshProjects?.();
  }

  return (
    <Show
      when={windows().length}
      fallback={<p class="empty-state">No projects were active in the last 48 hours.</p>}
    >
      <div class="project-windows" role="list" aria-label="Active projects">
        <For each={windows()}>
          {(project) => (
            <ProjectWindow
              project={project}
              query={props.query}
              hasVisibleFilter={props.hasVisibleFilter}
              livePaused={props.livePaused}
              pulse={pulses()[project.projectKey]}
              reloadRevision={reloadRevision()}
              now={now()}
              onOpen={() => props.onOpenProject(project.projectKey)}
            />
          )}
        </For>
      </div>
    </Show>
  );
}

type ProjectWindowProps = {
  project: ProjectSummary;
  query: string;
  hasVisibleFilter: boolean;
  livePaused: boolean;
  pulse?: Pulse;
  reloadRevision: number;
  now: number;
  onOpen: () => void;
};

function ProjectWindow(props: ProjectWindowProps) {
  let bodyRef: HTMLDivElement | undefined;
  let liveTimer: ReturnType<typeof setTimeout> | undefined;
  let flashTimer: ReturnType<typeof setTimeout> | undefined;
  let loadToken = 0;

  const [items, setItems] = createSignal<EventFeedItem[]>([]);
  const [pendingItems, setPendingItems] = createSignal<EventFeedItem[]>([]);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<string | null>(null);
  const [unfolds, setUnfolds] = createSignal<Set<string>>(new Set());
  const [flashing, setFlashing] = createSignal(false);

  const scopedQuery = createMemo(() =>
    appendProjectGroupScope(props.query, primaryProjectScope(props.project).canonicalKey),
  );
  const lastActiveAt = () => newerTimestamp(props.project.lastSeenAt, props.pulse?.at);

  const isLive = () => {
    const at = Date.parse(lastActiveAt() ?? "");

    return !Number.isNaN(at) && props.now - at <= LIVE_WINDOW_MS;
  };

  // Cards are a glance surface: always collapsed, no day breaks, rows never expand in place.
  const runs = createMemo(() =>
    segmentStream(items(), {
      hasVisibleFilter: props.hasVisibleFilter,
      folds: { isRowExpanded: () => false, unfolded: unfolds() },
    }).flatMap((segment) => (segment.type === "run" ? [segment] : [])),
  );

  createEffect(() => {
    void props.reloadRevision;
    const q = scopedQuery();
    const human = humanOnly();
    const token = ++loadToken;

    if (liveTimer) {
      clearTimeout(liveTimer);
    }

    liveTimer = undefined;
    setLoading(true);
    setError(null);
    setPendingItems([]);
    setUnfolds(new Set<string>());
    getEventFeed({ limit: WINDOW_FEED_LIMIT, q, meaningful: true, humanOnly: human || undefined })
      .then((response) => {
        if (token === loadToken) {
          setItems(response.items);
        }
      })
      .catch((cause) => {
        if (token !== loadToken) {
          return;
        }

        setError(cause instanceof Error ? cause.message : "Unable to load this project.");
      })
      .finally(() => {
        if (token === loadToken) {
          setLoading(false);
        }
      });
  });

  createEffect(
    on(
      () => props.pulse?.seq,
      (seq) => {
        if (seq === undefined || props.livePaused) {
          return;
        }

        if (liveTimer) {
          clearTimeout(liveTimer);
        }

        liveTimer = setTimeout(() => {
          liveTimer = undefined;
          void refetchHead();
        }, LIVE_DEBOUNCE_MS);
      },
      { defer: true },
    ),
  );

  onCleanup(() => {
    if (liveTimer) {
      clearTimeout(liveTimer);
    }

    if (flashTimer) {
      clearTimeout(flashTimer);
    }
  });

  async function refetchHead() {
    const since = pendingItems()[0]?.observedAt ?? items()[0]?.observedAt;
    const token = loadToken;

    try {
      const response = await getEventFeed({
        limit: WINDOW_FEED_LIMIT,
        q: scopedQuery(),
        meaningful: true,
        humanOnly: humanOnly() || undefined,
        since,
      });

      if (token !== loadToken) {
        return;
      }

      const existing = new Set([...items(), ...pendingItems()].map((item) => item.id));
      const fresh = response.items.filter((item) => !existing.has(item.id));

      if (!fresh.length) {
        return;
      }

      flash();

      if (nearTop()) {
        setItems((current) => [...fresh, ...current].slice(0, WINDOW_MAX_ROWS));
      } else {
        setPendingItems((current) => [...fresh, ...current].slice(0, WINDOW_MAX_ROWS));
      }
    } catch {
      // Live refetch is opportunistic; the next reload or arrival catches the card up.
    }
  }

  function nearTop() {
    return !bodyRef || bodyRef.scrollTop < 40;
  }

  function showPending() {
    const pending = pendingItems();

    if (!pending.length) {
      return;
    }

    setItems((current) => [...pending, ...current].slice(0, WINDOW_MAX_ROWS));
    setPendingItems([]);
    bodyRef?.scrollTo({ top: 0 });
  }

  function flash() {
    if (flashTimer) {
      clearTimeout(flashTimer);
    }

    setFlashing(true);
    flashTimer = setTimeout(() => setFlashing(false), FLASH_MS);
  }

  function unfold(fold: FoldRow) {
    setUnfolds((current) => new Set(current).add(fold.key));
  }

  return (
    <section
      classList={{ "project-window": true, "project-window--flash": flashing() }}
      role="listitem"
      aria-label={projectShortName(props.project)}
    >
      <button
        type="button"
        class="project-window-head"
        title={`Open ${projectShortName(props.project)} in the stream`}
        onClick={() => props.onOpen()}
      >
        <span
          classList={{ "project-window-dot": true, "project-window-dot--live": isLive() }}
          aria-label={isLive() ? "live" : undefined}
        />
        <span class="project-window-name">{projectShortName(props.project)}</span>
        <span class="project-window-age">{timeAgo(lastActiveAt(), props.now)}</span>
      </button>
      <Show when={pendingItems().length}>
        <button type="button" class="project-window-pill" onClick={showPending}>
          {pendingItems().length} new
        </button>
      </Show>
      <div ref={bodyRef} class="project-window-body">
        <Switch>
          <Match when={loading() && !items().length}>
            <p class="project-window-note">Loading…</p>
          </Match>
          <Match when={error()}>
            {(message) => <p class="project-window-note">{message()}</p>}
          </Match>
          <Match when={!items().length}>
            <p class="project-window-note">Nothing matching.</p>
          </Match>
          <Match when={true}>
            <For each={runs()}>
              {(run) => (
                <div class="stream-run">
                  <RunHeader
                    run={run}
                    sticky={false}
                    sessionHref={windowSessionHref(run.sessionId, props.project)}
                  />
                  <div class="stream-run-body">
                    <For each={run.rows}>
                      {(row) =>
                        row.type === "event" ? (
                          <StreamRow
                            item={withHumanText(row.item, humanOnly())}
                            humanMode={humanOnly()}
                            expanded={false}
                            sessionHref={windowSessionHref(run.sessionId, props.project)}
                            onToggle={() => props.onOpen()}
                            cwdException={(row.item.cwd ?? null) !== (run.cwd ?? null)}
                          />
                        ) : (
                          <StreamFold fold={row} onUnfold={() => unfold(row)} />
                        )
                      }
                    </For>
                  </div>
                </div>
              )}
            </For>
          </Match>
        </Switch>
      </div>
    </section>
  );
}

function newerTimestamp(
  left: string | null | undefined,
  right: string | null | undefined,
): string | undefined {
  if (!left) {
    return right ?? undefined;
  }

  if (!right) {
    return left;
  }

  return Date.parse(right) > Date.parse(left) ? right : left;
}

function windowSessionHref(sessionId: string, project: ProjectSummary): string {
  const query = new URLSearchParams({
    view: "browse",
    session: sessionId,
    project: project.projectKey,
  });

  return `/?${query.toString()}`;
}
