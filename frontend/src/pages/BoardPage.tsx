import { useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, on, onCleanup, Show, untrack, type JSX } from "solid-js";
import ProjectPicker from "../components/ProjectPicker";
import {
  getProjects,
  getRecall,
  updateTaskStatus,
  type AgentTask,
  type ProjectSummary,
  type TaskChange,
  type TaskFilters,
  type TaskSnapshot,
} from "../lib/api";
import { projectShortName } from "../lib/projects";
import { createTaskLiveStore, type TaskLiveStore } from "../lib/tasks";

type BoardSearchParams = {
  project?: string;
  lane?: string;
  task?: string;
};

type BoardColumn = {
  id: "open" | "active" | "blocked" | "done";
  label: string;
  description: string;
  statuses: AgentTask["status"][];
};

const COLUMNS: BoardColumn[] = [
  { id: "open", label: "Open", description: "Ready to claim", statuses: ["open"] },
  { id: "active", label: "In Progress", description: "Owned and moving", statuses: ["claimed", "in_progress"] },
  { id: "blocked", label: "Blocked", description: "Needs intervention", statuses: ["blocked"] },
  { id: "done", label: "Done", description: "Handoff recorded", statuses: ["done"] },
];

export type BoardPageProps = {
  store?: TaskLiveStore;
  loadProjects?: typeof getProjects;
  updateStatus?: typeof updateTaskStatus;
  recallHandoff?: typeof getRecall;
};

export default function BoardPage(props: BoardPageProps) {
  const [params, setParams] = useSearchParams<BoardSearchParams>();
  const ownsStore = props.store === undefined;
  const store = props.store ?? createTaskLiveStore({ initialFilters: filtersFromParams(params) });
  const mutateStatus = props.updateStatus ?? updateTaskStatus;
  const [catalog] = createResource(props.loadProjects ?? getProjects, { initialValue: [] });
  const [phase, setPhase] = createSignal<"loading" | "ready" | "error">("loading");
  const [loadError, setLoadError] = createSignal<string | null>(null);
  const [visibleFilters, setVisibleFilters] = createSignal<TaskFilters | null>(null);
  const [showCancelled, setShowCancelled] = createSignal(false);
  const [resettingTasks, setResettingTasks] = createSignal<Set<string>>(new Set());
  const [resetErrors, setResetErrors] = createSignal<Record<string, string | undefined>>({});
  const [knownProjects, setKnownProjects] = createSignal<string[]>(params.project ? [params.project] : []);
  const [knownLanes, setKnownLanes] = createSignal<string[]>(params.lane ? [params.lane] : []);
  const taskButtons = new Map<string, HTMLButtonElement>();
  let lastFocusedTaskId: string | undefined;
  let loadSequence = 0;

  const snapshots = createMemo(() => store.snapshots());
  const activeSnapshots = createMemo(() => snapshots().filter(({ task }) => task.status !== "cancelled"));
  const cancelledSnapshots = createMemo(() => snapshots().filter(({ task }) => task.status === "cancelled"));
  const selected = createMemo(() => snapshots().find(({ task }) => task.id === params.task));
  const totalActive = createMemo(() => activeSnapshots().length);
  const catalogProjects = createMemo(() => catalog.error ? [] : catalog());
  const projectOptions = createMemo(() => mergeProjectOptions(catalogProjects(), knownProjects()));
  const selectedProject = createMemo(() => projectForScope(params.project, projectOptions()));
  const projectCatalogError = createMemo(() => catalog.error ? "Unable to load projects." : null);

  createEffect(() => {
    const projects = new Set(knownProjects());
    const lanes = new Set(knownLanes());
    for (const { task } of snapshots()) {
      projects.add(task.projectKey);
      lanes.add(task.lane);
    }
    if (params.project) projects.add(params.project);
    if (params.lane) lanes.add(params.lane);
    const nextProjects = [...projects].sort();
    const nextLanes = [...lanes].sort();
    if (nextProjects.join("\0") !== knownProjects().join("\0")) setKnownProjects(nextProjects);
    if (nextLanes.join("\0") !== knownLanes().join("\0")) setKnownLanes(nextLanes);
  });

  createEffect(() => {
    const filters = filtersFromParams(params);
    void load(filters);
  });

  onCleanup(() => {
    if (ownsStore) store.close();
  });

  async function load(filters = filtersFromParams(params)) {
    const sequence = ++loadSequence;
    setPhase("loading");
    setLoadError(null);
    try {
      const operation = untrack(() => (
        sameTaskFilters(store.filters(), filters)
          ? store.refresh()
          : store.setFilters(filters)
      ));
      await operation;
      if (sequence === loadSequence) {
        setVisibleFilters({ ...filters });
        setPhase("ready");
      }
    } catch (error) {
      if (sequence !== loadSequence) return;
      setLoadError(errorMessage(error));
      setPhase("error");
    }
  }

  async function resetTask(snapshot: TaskSnapshot) {
    if (snapshot.task.status !== "blocked" && snapshot.task.status !== "in_progress") return;
    const taskId = snapshot.task.id;
    if (resettingTasks().has(taskId)) return;
    setResettingTasks((current) => new Set(current).add(taskId));
    setResetErrors((current) => ({ ...current, [taskId]: undefined }));
    try {
      await mutateStatus(taskId, { actor: "board", status: "open" });
      await store.refresh();
    } catch (error) {
      setResetErrors((current) => ({ ...current, [taskId]: errorMessage(error) }));
    } finally {
      setResettingTasks((current) => {
        const next = new Set(current);
        next.delete(taskId);
        return next;
      });
    }
  }

  function columnTasks(column: BoardColumn): TaskSnapshot[] {
    return activeSnapshots().filter(({ task }) => column.statuses.includes(task.status));
  }

  function selectTask(taskId?: string) {
    if (taskId) lastFocusedTaskId = taskId;
    setParams({ task: taskId });
  }

  function selectProject(projectKey?: string) {
    const project = projectKey
      ? projectOptions().find((candidate) => candidate.projectKey === projectKey)
      : undefined;
    setParams({ project: project?.canonicalKey, task: undefined });
  }

  function closeTaskDetail() {
    const returnTo = lastFocusedTaskId;
    selectTask();
    queueMicrotask(() => {
      const button = returnTo ? taskButtons.get(returnTo) : undefined;
      if (button?.isConnected) button.focus();
    });
  }

  return (
    <section class="board-page" aria-labelledby="board-title">
      <header class="board-header">
        <div class="board-heading">
          <p class="eyebrow">coordination ledger</p>
          <h1 id="board-title">Coordination board</h1>
          <p>Observe the agent queue, inspect frozen intent, and clear stalled ownership without running an agent.</p>
        </div>
        <div class="board-vitals" aria-label="Board status">
          <span class={`board-live board-live--${store.status()}`}>
            <i aria-hidden="true" /> {store.status()}
          </span>
          <strong>{totalActive()}</strong>
          <small>active records</small>
        </div>
      </header>

      <section class="board-filter-bar" aria-label="Board filters">
        <div class="board-project-filter">
          <ProjectPicker
            projects={projectOptions()}
            selectedProjectKey={selectedProject()?.projectKey}
            loading={catalog.loading && projectOptions().length === 0}
            error={projectCatalogError()}
            allDescription="Entire coordination queue"
            onSelect={selectProject}
          />
        </div>
        <label>
          <span>Lane</span>
          <select
            aria-label="Lane"
            value={params.lane ?? ""}
            onChange={(event) => setParams({ lane: optionalValue(event.currentTarget.value), task: undefined })}
          >
            <option value="">All lanes</option>
            <For each={knownLanes()}>{(lane) => <option value={lane}>{lane}</option>}</For>
          </select>
        </label>
        <div class="board-filter-summary" aria-live="polite">
          <span>{projectLabel(selectedProject(), params.project) || "every project"}</span>
          <span>{params.lane || "every lane"}</span>
          <Show when={projectCatalogError()}>
            <span class="board-catalog-warning">catalog unavailable</span>
          </Show>
          <Show when={phase() === "loading" && snapshots().length > 0}>
            <span class="board-refreshing">refreshing</span>
          </Show>
        </div>
      </section>

      <Show when={phase() === "loading" && snapshots().length === 0}>
        <BoardState class="board-state--loading" label="Loading coordination board" title="Reading the queue">
          <div class="board-loading-lines" aria-hidden="true"><i /><i /><i /></div>
        </BoardState>
      </Show>

      <Show when={phase() === "error" && snapshots().length === 0}>
        <div class="board-state board-state--error" role="alert">
          <span class="board-state-index">ERR</span>
          <h2>Queue snapshot unavailable</h2>
          <p>{loadError()}</p>
          <button type="button" class="secondary-action" aria-label="Retry loading board" onClick={() => void load()}>
            Retry snapshot
          </button>
        </div>
      </Show>

      <Show when={phase() === "ready" && snapshots().length === 0}>
        <Show
          when={selectedProject() && !params.lane ? selectedProject() : undefined}
          fallback={(
            <BoardState class="board-state--empty" label="Empty coordination board" title="No tasks match this view">
              <p>Change a filter or enqueue work through the REST or MCP task contract.</p>
            </BoardState>
          )}
        >
          {(project) => (
            <BoardState
              class="board-state--empty"
              label={`Empty coordination board for ${projectShortName(project())}`}
              title={`No work is queued for ${projectShortName(project())}`}
            >
              <p>Agents enqueue work through REST or MCP. Selecting a project does not infer tasks from recorded activity.</p>
              <button type="button" class="secondary-action" onClick={() => selectProject(undefined)}>Clear project</button>
            </BoardState>
          )}
        </Show>
      </Show>

      <Show when={phase() === "error" && snapshots().length > 0}>
        <div class="board-stale-alert" role="alert">
          <div>
            <strong>Snapshot refresh failed</strong>
            <span>{loadError()}. Showing the last successful view: {filterLabel(visibleFilters(), projectOptions())}.</span>
          </div>
          <button type="button" aria-label="Retry refreshing board" onClick={() => void load()}>Retry</button>
        </div>
      </Show>

      <Show when={snapshots().length > 0}>
        <div classList={{ "board-workspace": true, "board-workspace--detail": selected() !== undefined }}>
          <div class="board-canvas">
            <div class="board-columns" aria-label="Task status columns">
              <For each={COLUMNS}>
                {(column, columnIndex) => (
                  <section class={`board-column board-column--${column.id}`} aria-label={`${column.label} tasks`}>
                    <header>
                      <span class="board-column-index">0{columnIndex() + 1}</span>
                      <div><h2>{column.label}</h2><p>{column.description}</p></div>
                      <strong>{columnTasks(column).length}</strong>
                    </header>
                    <div class="board-task-stack">
                      <For each={columnTasks(column)} fallback={<p class="board-column-empty">No records</p>}>
                        {(snapshot) => (
                          <TaskCard
                            snapshot={snapshot}
                            project={projectForScope(snapshot.task.projectKey, projectOptions())}
                            selected={params.task === snapshot.task.id}
                            buttonRef={(button) => taskButtons.set(snapshot.task.id, button)}
                            onSelect={() => selectTask(snapshot.task.id)}
                          />
                        )}
                      </For>
                    </div>
                  </section>
                )}
              </For>
            </div>

            <Show when={cancelledSnapshots().length > 0}>
              <section class="board-cancelled-drawer">
                <button
                  type="button"
                  aria-expanded={showCancelled()}
                  onClick={() => setShowCancelled((shown) => !shown)}
                >
                  <span aria-hidden="true">{showCancelled() ? "−" : "+"}</span>
                  {showCancelled() ? "Hide" : "Show"} {cancelledSnapshots().length} cancelled {pluralize(cancelledSnapshots().length, "task")}
                </button>
                <Show when={showCancelled()}>
                  <div class="board-cancelled-list" role="region" aria-label="Cancelled tasks">
                    <For each={cancelledSnapshots()}>
                      {(snapshot) => (
                        <TaskCard
                          snapshot={snapshot}
                          project={projectForScope(snapshot.task.projectKey, projectOptions())}
                          selected={params.task === snapshot.task.id}
                          buttonRef={(button) => taskButtons.set(snapshot.task.id, button)}
                          onSelect={() => selectTask(snapshot.task.id)}
                        />
                      )}
                    </For>
                  </div>
                </Show>
              </section>
            </Show>
          </div>

          <Show when={selected()}>
            {(snapshot) => (
              <TaskDetail
                snapshot={snapshot()}
                project={projectForScope(snapshot().task.projectKey, projectOptions())}
                resetting={resettingTasks().has(snapshot().task.id)}
                resetError={resetErrors()[snapshot().task.id] ?? null}
                recallHandoff={props.recallHandoff ?? getRecall}
                onClose={closeTaskDetail}
                onReset={() => void resetTask(snapshot())}
              />
            )}
          </Show>
        </div>
      </Show>
    </section>
  );
}

function BoardState(props: { class: string; label: string; title: string; children: JSX.Element }) {
  return (
    <div class={`board-state ${props.class}`} role="status" aria-label={props.label}>
      <span class="board-state-index">Q</span>
      <h2>{props.title}</h2>
      {props.children}
    </div>
  );
}

function TaskCard(props: {
  snapshot: TaskSnapshot;
  project?: ProjectSummary;
  selected: boolean;
  buttonRef: (button: HTMLButtonElement) => void;
  onSelect: () => void;
}) {
  const task = () => props.snapshot.task;
  return (
    <article class={`board-task board-task--${task().status}`}>
      <button
        type="button"
        ref={props.buttonRef}
        classList={{ "board-task-button": true, "board-task-button--selected": props.selected }}
        aria-label={`${task().title}, ${statusLabel(task().status)}`}
        aria-pressed={props.selected}
        onClick={props.onSelect}
      >
        <span class="board-task-topline">
          <StatusBadge status={task().status} />
          <span class="board-priority">P{task().priority}</span>
        </span>
        <strong>{task().title}</strong>
        <span class="board-task-meta">
          <i>{task().lane}</i>
          <Show
            when={props.project}
            fallback={<i>{task().projectKey}</i>}
          >
            {(project) => (
              <>
                <i>{projectShortName(project())}</i>
                <Show when={project().canonicalKey !== projectShortName(project())}>
                  <i title={project().canonicalKey}>{project().canonicalKey}</i>
                </Show>
              </>
            )}
          </Show>
        </span>
        <Show when={task().claimedBy}>
          {(claimant) => <span class="board-task-owner">↳ {claimant()}</span>}
        </Show>
        <Show when={task().blockedReason}>
          {(reason) => <span class="board-task-blocker">{reason()}</span>}
        </Show>
      </button>
    </article>
  );
}

function TaskDetail(props: {
  snapshot: TaskSnapshot;
  project?: ProjectSummary;
  resetting: boolean;
  resetError: string | null;
  recallHandoff: typeof getRecall;
  onClose: () => void;
  onReset: () => void;
}) {
  let closeButton: HTMLButtonElement | undefined;
  const task = () => props.snapshot.task;
  const spec = () => props.snapshot.spec;
  const canReset = () => task().status === "blocked" || task().status === "in_progress";
  const [handoff] = createResource(
    () => task().resultHandoffId || undefined,
    async (handoffId) => {
      const result = await props.recallHandoff(handoffId, 24 * 365, ["handoff"]);
      return result.items.find((item) => item.eventId === handoffId) ?? null;
    },
  );
  createEffect(on(
    () => task().id,
    () => queueMicrotask(() => closeButton?.focus()),
  ));
  return (
    <aside class="board-detail" aria-label="Task detail">
      <header class="board-detail-head">
        <div>
          <p class="eyebrow">task record</p>
          <h2>{task().title}</h2>
        </div>
        <button ref={closeButton} type="button" class="board-detail-close" aria-label="Close task detail" onClick={props.onClose}>×</button>
      </header>

      <div class="board-detail-scroll">
        <section class="board-detail-status">
          <StatusBadge status={task().status} />
          <span>{task().lane}</span>
          <span>P{task().priority}</span>
        </section>

        <Show when={task().blockedReason}>
          {(reason) => (
            <section class="board-blocked-callout">
              <span>Blocked reason</span>
              <p>{reason()}</p>
            </section>
          )}
        </Show>

        <section class="board-detail-section">
          <h3>Lifecycle</h3>
          <dl class="board-detail-grid">
            <dt>Project</dt>
            <dd class="board-project-identity">
              <strong>{props.project ? projectShortName(props.project) : task().projectKey}</strong>
              <span>{props.project?.canonicalKey || task().projectKey}</span>
            </dd>
            <dt>Current claimant</dt><dd>{task().claimedBy || "Unclaimed"}</dd>
            <dt>Created by</dt><dd>{task().createdBy}</dd>
            <dt>Created</dt><dd>{formatInstant(task().createdAt)}</dd>
            <dt>Last transition</dt><dd>{formatInstant(task().updatedAt)}</dd>
            <dt>Task ID</dt><dd><code>{task().id}</code></dd>
          </dl>
        </section>

        <section class="board-detail-section board-spec">
          <div class="board-section-heading">
            <div><span>Frozen specification</span><h3>{spec().title}</h3></div>
            <span class={`board-spec-status board-spec-status--${spec().status}`}>{spec().status}</span>
          </div>
          <pre class="board-spec-body">{spec().body}</pre>
          <dl class="board-detail-grid">
            <dt>Spec ID</dt><dd><code>{spec().id}</code></dd>
            <dt>Frozen by</dt><dd>{spec().createdBy}</dd>
            <dt>Frozen at</dt><dd>{formatInstant(spec().createdAt)}</dd>
          </dl>
          <Show when={spec().specRef}>
            {(provenance) => (
              <div class="board-provenance">
                <span>Provenance</span>
                <pre>{JSON.stringify(provenance(), null, 2)}</pre>
              </div>
            )}
          </Show>
        </section>

        <Show when={task().resultHandoffId}>
          {(handoffId) => (
            <section class="board-handoff" id={`handoff-${handoffId()}`}>
              <div class="board-handoff-head">
                <div><span>Completion artifact</span><code>{handoffId()}</code></div>
                <span class="board-handoff-label">Linked Handoff</span>
              </div>
              <Show when={handoff.loading}>
                <p class="board-handoff-state">Resolving linked Handoff…</p>
              </Show>
              <Show when={handoff.error}>
                <p class="board-handoff-state board-handoff-state--error">Linked Handoff could not be recalled.</p>
              </Show>
              <Show when={!handoff.loading && !handoff.error && handoff() === null}>
                <p class="board-handoff-state">No matching Handoff is available in the recall window.</p>
              </Show>
              <Show when={handoff()}>
                {(item) => (
                  <div class="board-handoff-body">
                    <strong>{item().headline || "Completion Handoff"}</strong>
                    <span>{item().source}{item().clientSessionId ? ` · ${item().clientSessionId}` : ""}</span>
                    <Show when={item().nextAction}>{(next) => <p><b>Next</b>{next()}</p>}</Show>
                    <Show when={item().openLoops?.length}>
                      <ul><For each={item().openLoops || []}>{(loop) => <li>{loop}</li>}</For></ul>
                    </Show>
                  </div>
                )}
              </Show>
            </section>
          )}
        </Show>

        <Show when={canReset()}>
          <section class="board-reset-panel">
            <div><strong>Release ownership</strong><p>Return this task to Open so another agent can claim it.</p></div>
            <button
              type="button"
              class="board-reset-button"
              aria-label="Reset task to open"
              aria-busy={props.resetting}
              disabled={props.resetting}
              onClick={props.onReset}
            >
              {props.resetting ? "Resetting…" : "Reset to open"}
            </button>
            <Show when={props.resetError}>{(message) => <p class="board-reset-error" role="alert">Reset failed: {message()}</p>}</Show>
          </section>
        </Show>
      </div>
    </aside>
  );
}

function StatusBadge(props: { status: AgentTask["status"] }) {
  return <span class={`board-status board-status--${props.status}`}>{statusLabel(props.status)}</span>;
}

function filtersFromParams(params: BoardSearchParams): TaskFilters {
  const filters: TaskFilters = { limit: 250 };
  if (params.project?.trim()) filters.projectKey = params.project.trim();
  if (params.lane?.trim()) filters.lane = params.lane.trim();
  return filters;
}

function statusLabel(status: AgentTask["status"]): string {
  if (status === "in_progress" || status === "claimed") return "In progress";
  return status.charAt(0).toUpperCase() + status.slice(1);
}

function optionalValue(value: string): string | undefined {
  return value || undefined;
}

function pluralize(count: number, singular: string): string {
  return count === 1 ? singular : `${singular}s`;
}

function filterLabel(filters: TaskFilters | null, projects: ProjectSummary[]): string {
  if (!filters) return "previous snapshot";
  const project = projectForScope(filters.projectKey, projects);
  return `${projectLabel(project, filters.projectKey) || "every project"} / ${filters.lane || "every lane"}`;
}

function mergeProjectOptions(catalog: ProjectSummary[], queueScopes: string[]): ProjectSummary[] {
  const projects = [...catalog];
  for (const scope of queueScopes) {
    if (projectForScope(scope, projects)) continue;
    projects.push({
      projectKey: `uncatalogued:${scope}`,
      canonicalKey: scope,
      label: `Uncatalogued queue scope · ${scope}`,
      sessionCount: 0,
      eventCount: 0,
      savedMeldCount: 0,
      firstSeenAt: null,
      lastSeenAt: null,
    });
  }
  return projects;
}

function projectForScope(scope: string | null | undefined, projects: ProjectSummary[]): ProjectSummary | undefined {
  if (!scope) return undefined;
  return projects.find((project) => project.canonicalKey === scope);
}

function projectLabel(project: ProjectSummary | undefined, fallback?: string): string | undefined {
  if (project?.projectKey.startsWith("uncatalogued:")) return project.canonicalKey;
  return project?.label || fallback;
}

function sameTaskFilters(left: TaskFilters, right: TaskFilters): boolean {
  return left.projectKey === right.projectKey
    && left.lane === right.lane
    && left.status === right.status
    && left.limit === right.limit;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function formatInstant(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}
