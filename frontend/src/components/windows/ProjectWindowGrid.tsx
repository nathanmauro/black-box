import { createEffect, createMemo, createSignal, For, Match, Show, Switch } from "solid-js";
import { getEventFeed, type EventFeedItem, type ProjectSummary } from "../../lib/api";
import { timeAgo } from "../../lib/format";
import { humanOnly, withHumanText } from "../../lib/humanOnly";
import { primaryProjectScope, projectShortName } from "../../lib/projects";
import { isActiveSince, LIVE_WINDOW_MS, stableWindowOrder } from "../../lib/projectWindows";
import { appendProjectGroupScope } from "../../lib/query";
import { segmentStream, type FoldRow } from "../../lib/streamGroups";
import RunHeader from "../events/RunHeader";
import StreamFold from "../events/StreamFold";
import StreamRow from "../events/StreamRow";

const WINDOW_FEED_LIMIT = 30;

type ProjectWindowGridProps = {
  projects: ProjectSummary[];
  // The visible query, without any project scope: every window applies it to its own project.
  query: string;
  hasVisibleFilter: boolean;
  onOpenProject: (projectKey: string) => void;
};

export default function ProjectWindowGrid(props: ProjectWindowGridProps) {
  const [order, setOrder] = createSignal<string[]>([]);

  createEffect(() => {
    setOrder((placed) => stableWindowOrder(placed, props.projects, Date.now()));
  });

  const windows = createMemo(() => {
    const byKey = new Map(props.projects.map((project) => [project.projectKey, project]));

    return order()
      .map((key) => byKey.get(key))
      .filter((project): project is ProjectSummary => Boolean(project));
  });

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
  onOpen: () => void;
};

function ProjectWindow(props: ProjectWindowProps) {
  let loadToken = 0;

  const [items, setItems] = createSignal<EventFeedItem[]>([]);
  const [loading, setLoading] = createSignal(true);
  const [error, setError] = createSignal<string | null>(null);
  const [unfolds, setUnfolds] = createSignal<Set<string>>(new Set());

  const scopedQuery = createMemo(() =>
    appendProjectGroupScope(props.query, primaryProjectScope(props.project).canonicalKey),
  );
  const isLive = () => isActiveSince(props.project, Date.now(), LIVE_WINDOW_MS);
  // Cards are a glance surface: always collapsed, no day breaks, rows never expand in place.
  const runs = createMemo(() =>
    segmentStream(items(), {
      hasVisibleFilter: props.hasVisibleFilter,
      folds: { isRowExpanded: () => false, unfolded: unfolds() },
    }).flatMap((segment) => (segment.type === "run" ? [segment] : [])),
  );

  createEffect(() => {
    const q = scopedQuery();
    const human = humanOnly();
    const token = ++loadToken;

    setLoading(true);
    setError(null);
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

  function unfold(fold: FoldRow) {
    setUnfolds((current) => new Set(current).add(fold.key));
  }

  return (
    <section class="project-window" role="listitem" aria-label={projectShortName(props.project)}>
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
        <span class="project-window-age">{timeAgo(props.project.lastSeenAt)}</span>
      </button>
      <div class="project-window-body">
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

function windowSessionHref(sessionId: string, project: ProjectSummary): string {
  const query = new URLSearchParams({
    view: "browse",
    session: sessionId,
    project: project.projectKey,
  });

  return `/?${query.toString()}`;
}
