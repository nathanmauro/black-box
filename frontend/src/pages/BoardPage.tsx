import { A, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, on, onCleanup, Show, untrack, type JSX } from "solid-js";
import ApprovalBox, { approvalDecision, type ApprovalStage } from "../components/ApprovalBox";
import DagView from "../components/DagView";
import ProjectPicker from "../components/ProjectPicker";
import SteerBox from "../components/SteerBox";
import StoryForm from "../components/StoryForm";
import {
  createSpec,
  createTaskAnnotation,
  enqueueTask,
  getProjects,
  getRecall,
  getSpec,
  getTaskDag,
  getTaskEvents,
  updateTaskStatus,
  type AgentTask,
  type AnnotationKind,
  type ProjectSummary,
  type TaskAnnotation,
  type TaskChange,
  type TaskEvent,
  type TaskFilters,
  type TaskSnapshot,
} from "../lib/api";
import {
  canonicalizeProjectPath,
  findProjectByIdentifier,
  primaryProjectScope,
  projectShortName,
} from "../lib/projects";
import { parseSpecBody, type StoryFormInput } from "../lib/storySpec";
import { createTaskLiveStore, type TaskLiveStoreWithNotes } from "../lib/tasks";

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

type ApprovalState = "inactive" | "checking" | "unavailable" | "awaiting" | "decided";

type StoryFormState = {
  initialInput?: StoryFormInput;
  blockedReason?: string;
  replacesTaskId?: string;
};

const COLUMNS: BoardColumn[] = [
  { id: "open", label: "Open", description: "Ready to claim", statuses: ["open"] },
  { id: "active", label: "In Progress", description: "Owned and moving", statuses: ["claimed", "in_progress"] },
  { id: "blocked", label: "Blocked", description: "Needs intervention", statuses: ["blocked"] },
  { id: "done", label: "Done", description: "Handoff recorded", statuses: ["done"] },
];

export type BoardPageProps = {
  store?: TaskLiveStoreWithNotes;
  loadProjects?: typeof getProjects;
  loadSpec?: typeof getSpec;
  createSpec?: typeof createSpec;
  enqueueTask?: typeof enqueueTask;
  updateStatus?: typeof updateTaskStatus;
  recallHandoff?: typeof getRecall;
  getTaskEvents?: typeof getTaskEvents;
  getTaskDag?: typeof getTaskDag;
  createAnnotation?: typeof createTaskAnnotation;
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
  const [storyForm, setStoryForm] = createSignal<StoryFormState>();
  const [revisingTaskId, setRevisingTaskId] = createSignal<string>();
  const [revisionErrors, setRevisionErrors] = createSignal<Record<string, string | undefined>>({});
  const [resettingTasks, setResettingTasks] = createSignal<Set<string>>(new Set());
  const [resetErrors, setResetErrors] = createSignal<Record<string, string | undefined>>({});
  const [knownProjects, setKnownProjects] = createSignal<string[]>(params.project ? [params.project] : []);
  const [knownLanes, setKnownLanes] = createSignal<string[]>(params.lane ? [params.lane] : []);
  const [localApprovals, setLocalApprovals] = createSignal<Record<string, TaskAnnotation>>({});
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
  const selectedProject = createMemo(() => selectedProjectForScope(params.project, projectOptions()));
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

  async function reviseTask(snapshot: TaskSnapshot) {
    const { task } = snapshot;
    if (task.status !== "blocked" || task.lane !== "gate" || revisingTaskId() === task.id) return;
    setRevisingTaskId(task.id);
    setRevisionErrors((current) => ({ ...current, [task.id]: undefined }));
    try {
      const frozenSpec = await (props.loadSpec ?? getSpec)(task.specId);
      setStoryForm({
        initialInput: parseSpecBody(frozenSpec.body),
        blockedReason: task.blockedReason ?? "The gate blocked this story without feedback.",
        replacesTaskId: task.id,
      });
    } catch (error) {
      setRevisionErrors((current) => ({ ...current, [task.id]: errorMessage(error) }));
    } finally {
      setRevisingTaskId(undefined);
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
          <button
            type="button"
            class="story-form-trigger"
            aria-expanded={storyForm() !== undefined}
            onClick={() => setStoryForm({})}
          >
            New story
          </button>
        </div>
      </section>

      <Show when={storyForm()} keyed>
        {(formState) => (
          <StoryForm
            projects={projectOptions()}
            initialInput={formState.initialInput}
            blockedReason={formState.blockedReason}
            replacesTaskId={formState.replacesTaskId}
            createSpec={props.createSpec}
            enqueueTask={props.enqueueTask}
            updateTaskStatus={mutateStatus}
            onCreated={() => {
              setStoryForm(undefined);
              void store.refresh();
            }}
            onCleanupFailed={() => void store.refresh()}
            onCancel={() => setStoryForm(undefined)}
          />
        )}
      </Show>

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
          when={!params.lane ? primaryCatalogFilter(params.project, selectedProject()) : undefined}
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
              <Show when={projectHref(project())}>
                {(href) => <A class="secondary-action" href={href()}>Open project</A>}
              </Show>
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
                            store={store}
                            getTaskEvents={props.getTaskEvents ?? getTaskEvents}
                            localApproval={localApprovals()[snapshot.task.id]}
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
                          store={store}
                          getTaskEvents={props.getTaskEvents ?? getTaskEvents}
                          localApproval={localApprovals()[snapshot.task.id]}
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
                store={store}
                resetting={resettingTasks().has(snapshot().task.id)}
                resetError={resetErrors()[snapshot().task.id] ?? null}
                revising={revisingTaskId() === snapshot().task.id}
                revisionError={revisionErrors()[snapshot().task.id] ?? null}
                recallHandoff={props.recallHandoff ?? getRecall}
                getTaskEvents={props.getTaskEvents ?? getTaskEvents}
                getTaskDag={props.getTaskDag ?? getTaskDag}
                createAnnotation={props.createAnnotation}
                localApproval={localApprovals()[snapshot().task.id]}
                onApproval={(annotation) => setLocalApprovals((current) => ({
                  ...current,
                  [annotation.taskId]: annotation,
                }))}
                onClose={closeTaskDetail}
                onReset={() => void resetTask(snapshot())}
                onRevise={() => void reviseTask(snapshot())}
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
  store: TaskLiveStoreWithNotes;
  getTaskEvents: typeof getTaskEvents;
  localApproval?: TaskAnnotation;
  selected: boolean;
  buttonRef: (button: HTMLButtonElement) => void;
  onSelect: () => void;
}) {
  const task = () => props.snapshot.task;
  const stage = createMemo(() => sdlcStage(task().lane));
  const [stageEvents] = createResource(
    () => (task().status === "done" && stage()
      ? task().id
      : undefined),
    (taskId) => props.getTaskEvents(taskId),
  );
  const stageTimeline = createMemo(() => {
    props.store.noteEpoch();
    return mergeTaskTimeline(
      stageEvents.error ? [] : stageEvents() ?? [],
      annotationsWithLocal(
        props.store.taskAnnotations(task().id),
        props.localApproval,
      ),
    );
  });
  const decision = createMemo(() => approvalForStage(stageTimeline(), stage()));
  const approvalState = createMemo<ApprovalState>(() => {
    if (task().status !== "done" || stage() === undefined) return "inactive";
    if (decision() !== undefined) return "decided";
    if (stageEvents.loading) return "checking";
    if (stageEvents.error) return "unavailable";
    if (stageEvents() === undefined) return "checking";
    return "awaiting";
  });
  return (
    <article class={`board-task board-task--${task().status}`}>
      <button
        type="button"
        ref={props.buttonRef}
        classList={{ "board-task-button": true, "board-task-button--selected": props.selected }}
        aria-label={`${task().title}, ${statusLabel(task().status)}`}
        aria-describedby={stage() ? `task-stage-${task().id}` : undefined}
        aria-pressed={props.selected}
        onClick={props.onSelect}
      >
        <span class="board-task-topline">
          <StatusBadge status={task().status} />
          <span class="board-priority">P{task().priority}</span>
        </span>
        <strong>{task().title}</strong>
        <Show when={stage()}>
          {(currentStage) => (
            <span id={`task-stage-${task().id}`} class="board-task-stage-row">
              <span class={`board-stage-chip board-stage-chip--${currentStage()}`}>{currentStage()}</span>
              <Show when={approvalState() === "awaiting"}>
                <span class="board-awaiting-chip">Awaiting approval</span>
              </Show>
              <Show when={approvalState() === "checking"}>
                <span class="board-approval-state-chip">Checking approval</span>
              </Show>
              <Show when={approvalState() === "unavailable"}>
                <span class="board-approval-state-chip board-approval-state-chip--error">Approval status unavailable</span>
              </Show>
            </span>
          )}
        </Show>
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
  store: TaskLiveStoreWithNotes;
  resetting: boolean;
  resetError: string | null;
  revising: boolean;
  revisionError: string | null;
  recallHandoff: typeof getRecall;
  getTaskEvents: typeof getTaskEvents;
  getTaskDag: typeof getTaskDag;
  createAnnotation?: typeof createTaskAnnotation;
  localApproval?: TaskAnnotation;
  onApproval: (annotation: TaskAnnotation) => void;
  onClose: () => void;
  onReset: () => void;
  onRevise: () => void;
}) {
  let closeButton: HTMLButtonElement | undefined;
  const task = () => props.snapshot.task;
  const spec = () => props.snapshot.spec;
  const canReset = () => task().status === "blocked" || task().status === "in_progress";
  const canRevise = () => task().status === "blocked" && task().lane === "gate";
  const stage = createMemo(() => sdlcStage(task().lane));
  const [dagExpanded, setDagExpanded] = createSignal(false);
  const [events, { refetch: refetchEvents }] = createResource(
    () => [task().id, props.store.noteEpoch()] as const,
    ([taskId]) => props.getTaskEvents(taskId),
  );
  const [dag] = createResource(
    () => (dagExpanded() ? task().id : undefined),
    (taskId) => props.getTaskDag(taskId),
  );
  const timeline = createMemo(() => {
    props.store.noteEpoch();
    return mergeTaskTimeline(
      events.error ? [] : events() ?? [],
      annotationsWithLocal(
        props.store.taskAnnotations(task().id),
        props.localApproval,
      ),
    );
  });
  const decision = createMemo(() => approvalForStage(timeline(), stage()));
  const approvalState = createMemo<ApprovalState>(() => {
    if (task().status !== "done" || stage() === undefined) return "inactive";
    if (decision() !== undefined) return "decided";
    if (events.loading) return "checking";
    if (events.error) return "unavailable";
    if (events() === undefined) return "checking";
    return "awaiting";
  });
  const approvalReady = createMemo(() => (
    task().status === "done"
    && stage() !== undefined
    && (decision() !== undefined || (!events.loading && !events.error && events() !== undefined))
  ));
  const chips = createMemo(() => timelineChips(timeline()));
  const tendril = createMemo(() => timeline().find((entry) => (
    entry.kind === "worker_session" && typeof entry.dataJson?.sessionId === "string"
  )));
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
          <Show when={approvalState() === "awaiting"}>
            <span class="board-awaiting-chip">Awaiting approval</span>
          </Show>
          <Show when={approvalState() === "checking"}>
            <span class="board-approval-state-chip">Checking approval</span>
          </Show>
          <Show when={approvalState() === "unavailable"}>
            <span class="board-approval-state-chip board-approval-state-chip--error">Approval status unavailable</span>
          </Show>
        </section>

        <Show when={task().blockedReason}>
          {(reason) => (
            <section class="board-blocked-callout">
              <span>Blocked reason</span>
              <p>{reason()}</p>
            </section>
          )}
        </Show>

        <Show when={canRevise()}>
          <section class="board-revise-panel">
            <div>
              <strong>Submit a corrected story</strong>
              <p>The frozen spec stays unchanged. A successful resubmission creates a new spec and gate task.</p>
            </div>
            <button
              type="button"
              class="board-revise-button"
              aria-busy={props.revising}
              disabled={props.revising}
              onClick={props.onRevise}
            >
              {props.revising ? "Loading spec…" : "Revise & resubmit"}
            </button>
            <Show when={props.revisionError}>
              {(message) => <p class="board-revise-error" role="alert">Unable to load frozen spec: {message()}</p>}
            </Show>
          </section>
        </Show>

        <section class="board-detail-section">
          <h3>Lifecycle</h3>
          <dl class="board-detail-grid">
            <dt>Project</dt>
            <dd class="board-project-identity">
              <strong>{props.project ? projectShortName(props.project) : task().projectKey}</strong>
              <span>{props.project?.canonicalKey || task().projectKey}</span>
              <Show when={props.project && props.project.canonicalKey !== task().projectKey}>
                <span>Exact queue scope · {task().projectKey}</span>
              </Show>
              <Show when={projectHref(props.project)}>
                {(href) => <A href={href()}>Open project</A>}
              </Show>
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

        <section class="board-detail-section board-annotations">
          <h3>Agent activity</h3>
          <Show when={chips().engine || chips().branch || chips().prUrl}>
            <div class="board-chip-row" aria-label="Agent run context">
              <Show when={chips().engine}>{(engine) => <span class="board-chip board-chip--engine">Engine · {engine()}</span>}</Show>
              <Show when={chips().branch}>{(branch) => <code class="board-chip board-chip--branch">{branch()}</code>}</Show>
              <Show when={chips().prUrl}>
                {(prUrl) => <a class="board-chip board-chip--pr" href={prUrl()} target="_blank" rel="noreferrer">Pull request ↗</a>}
              </Show>
            </div>
          </Show>

          <Show when={tendril()}>
            {(entry) => (
              <A
                href={`/sessions/${encodeURIComponent(entry().dataJson!.sessionId as string)}?task=${encodeURIComponent(task().id)}`}
                class="board-tendril-button"
              >
                Open worker session →
              </A>
            )}
          </Show>

          <Show when={events.loading}>
            <p class="board-annotation-state">Loading task activity…</p>
          </Show>
          <Show when={events.error}>
            <p class="board-annotation-state board-annotation-state--error">Task activity could not be loaded.</p>
          </Show>
          <Show when={!events.loading && !events.error && timeline().length === 0}>
            <p class="board-annotation-state">No task activity yet.</p>
          </Show>
          <Show when={!events.loading && !events.error && timeline().length > 0}>
            <ul class="board-annotation-list">
              <For each={timeline()}>
                {(entry) => (
                  <li
                    classList={{
                      "board-annotation-row": true,
                      "board-annotation-row--document": entry.kind === "plan" || entry.kind === "review",
                      [`board-annotation-row--${entry.kind}`]: entry.kind === "plan" || entry.kind === "review",
                    }}
                    data-annotation-id={entry.id}
                  >
                    <div class="board-annotation-meta">
                      <span class={`board-annotation-kind board-annotation-kind--${entry.kind}`}>
                        {entry.kind === "lifecycle" ? entry.text : annotationKindLabel(entry.kind)}
                      </span>
                      <span>{entry.actor}</span>
                      <time dateTime={entry.observedAt}>{formatInstant(entry.observedAt)}</time>
                    </div>
                    <Show
                      when={entry.kind === "plan" || entry.kind === "review"}
                      fallback={<p>{entry.text}</p>}
                    >
                      <pre class="board-annotation-document">{entry.text}</pre>
                    </Show>
                  </li>
                )}
              </For>
            </ul>
          </Show>

          <Show when={approvalReady() && stage()}>
            {(currentStage) => (
              <ApprovalBox
                taskId={task().id}
                actor="nathan"
                stage={currentStage()}
                decision={decision()}
                createAnnotation={props.createAnnotation}
                onDecided={(annotation) => {
                  props.onApproval(annotation);
                  void refetchEvents();
                }}
              />
            )}
          </Show>

          <SteerBox
            taskId={task().id}
            actor="board"
            enabled={task().status === "in_progress"}
            createAnnotation={props.createAnnotation}
            onSteered={() => void refetchEvents()}
          />
        </section>

        <section class="board-detail-section board-dag-panel">
          <button
            type="button"
            class="board-dag-toggle"
            aria-expanded={dagExpanded()}
            aria-controls={`task-dag-${task().id}`}
            onClick={() => setDagExpanded((expanded) => !expanded)}
          >
            <span aria-hidden="true">{dagExpanded() ? "−" : "+"}</span>
            {dagExpanded() ? "Hide agent DAG" : "View agent DAG"}
          </button>
          <Show when={dagExpanded()}>
            <div id={`task-dag-${task().id}`} class="board-dag-body">
              <Show when={dag.loading}>
                <p class="board-annotation-state">Loading agent DAG…</p>
              </Show>
              <Show when={dag.error}>
                <p class="board-annotation-state board-annotation-state--error">Agent DAG could not be loaded.</p>
              </Show>
              <Show when={!dag.loading && !dag.error}>
                <DagView dag={dag() ?? { nodes: [], edges: [] }} currentTaskId={task().id} />
              </Show>
            </div>
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

type TaskTimelineEntry = {
  id: string;
  taskId: string;
  kind: AnnotationKind | "lifecycle";
  actor: string;
  text: string;
  dataJson?: Record<string, unknown>;
  observedAt: string;
};

const ANNOTATION_KINDS = new Set<AnnotationKind>([
  "note",
  "steer",
  "progress",
  "worker_session",
  "engine",
  "plan",
  "review",
  "approval",
]);

const ANNOTATION_KIND_LABELS: Record<AnnotationKind, string> = {
  note: "Note",
  steer: "Steer",
  progress: "Progress",
  worker_session: "Worker Session",
  engine: "Engine",
  plan: "Plan",
  review: "Review",
  approval: "Approval",
};

function mergeTaskTimeline(history: TaskEvent[], liveAnnotations: TaskAnnotation[]): TaskTimelineEntry[] {
  const fetchedIds = new Set(history.map((event) => event.id));
  const entries = history.flatMap((event): TaskTimelineEntry[] => {
    if (event.type !== "task.note") {
      const label = `${event.fromStatus ?? "—"} → ${event.toStatus ?? event.type}`;
      return [{
        id: event.id,
        taskId: event.taskId,
        kind: "lifecycle",
        actor: event.actor,
        text: label,
        observedAt: event.observedAt,
      }];
    }

    const detail = event.detail;
    if (!isRecord(detail) || !isAnnotationKind(detail.kind)) return [];
    return [{
      id: event.id,
      taskId: event.taskId,
      kind: detail.kind,
      actor: event.actor,
      text: typeof detail.text === "string" ? detail.text : "",
      dataJson: isRecord(detail.dataJson) ? detail.dataJson : undefined,
      observedAt: event.observedAt,
    }];
  });

  for (const annotation of liveAnnotations) {
    if (fetchedIds.has(annotation.id)) continue;
    entries.push({
      ...annotation,
      dataJson: annotation.dataJson ?? undefined,
    });
  }

  return entries.sort((left, right) => (
    right.observedAt.localeCompare(left.observedAt) || right.id.localeCompare(left.id)
  ));
}

function timelineChips(entries: TaskTimelineEntry[]): { engine?: string; branch?: string; prUrl?: string } {
  const result: { engine?: string; branch?: string; prUrl?: string } = {};
  for (const entry of [...entries].reverse()) {
    if (!entry.dataJson || !["engine", "progress", "worker_session"].includes(entry.kind)) continue;
    if (typeof entry.dataJson.engine === "string") result.engine = entry.dataJson.engine;
    if (typeof entry.dataJson.branch === "string") result.branch = entry.dataJson.branch;
    if (typeof entry.dataJson.prUrl === "string") result.prUrl = entry.dataJson.prUrl;
    else if (typeof entry.dataJson.pr === "string") result.prUrl = entry.dataJson.pr;
  }
  return result;
}

function annotationKindLabel(kind: AnnotationKind): string {
  return ANNOTATION_KIND_LABELS[kind];
}

function sdlcStage(lane: string): ApprovalStage | undefined {
  if (lane === "sdlc:plan") return "plan";
  if (lane === "sdlc:review") return "review";
  return undefined;
}

function approvalForStage(
  entries: TaskTimelineEntry[],
  stage: ApprovalStage | undefined,
): TaskAnnotation | undefined {
  if (!stage) return undefined;
  const entry = entries.find((candidate) => (
    candidate.kind === "approval"
    && approvalDecision({ kind: "approval", dataJson: candidate.dataJson }, stage) !== undefined
  ));
  if (!entry) return undefined;
  return {
    id: entry.id,
    taskId: entry.taskId,
    kind: "approval",
    actor: entry.actor,
    text: entry.text,
    dataJson: entry.dataJson,
    observedAt: entry.observedAt,
  };
}

function annotationsWithLocal(
  annotations: TaskAnnotation[],
  localApproval: TaskAnnotation | undefined,
): TaskAnnotation[] {
  if (!localApproval || annotations.some((annotation) => annotation.id === localApproval.id)) return annotations;
  return [...annotations, localApproval];
}

function isAnnotationKind(value: unknown): value is AnnotationKind {
  return typeof value === "string" && ANNOTATION_KINDS.has(value as AnnotationKind);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
  const project = selectedProjectForScope(filters.projectKey, projects);
  return `${projectLabel(project, filters.projectKey) || "every project"} / ${filters.lane || "every lane"}`;
}

function mergeProjectOptions(catalog: ProjectSummary[], queueScopes: string[]): ProjectSummary[] {
  const projects = [...catalog];
  const addedScopes = new Set<string>();
  for (const scope of queueScopes) {
    const canonicalScope = canonicalizeProjectPath(scope);
    if (addedScopes.has(canonicalScope)) continue;
    addedScopes.add(canonicalScope);

    const catalogProject = projectForScope(scope, catalog);
    if (catalogProject) {
      const primaryScope = primaryProjectScope(catalogProject);
      if (canonicalizeProjectPath(primaryScope.canonicalKey) === canonicalScope) continue;
      projects.push({
        projectKey: `queue-scope:${scope}`,
        canonicalKey: scope,
        label: `Exact queue scope · ${scope}`,
        sessionCount: 0,
        eventCount: 0,
        savedMeldCount: 0,
        firstSeenAt: null,
        lastSeenAt: null,
      });
      continue;
    }
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
  return findProjectByIdentifier(projects, scope);
}

function selectedProjectForScope(scope: string | null | undefined, projects: ProjectSummary[]): ProjectSummary | undefined {
  if (!scope) return undefined;
  const canonicalScope = canonicalizeProjectPath(scope);
  return projects.find((project) => (
    isExactQueueChoice(project)
    && canonicalizeProjectPath(project.canonicalKey) === canonicalScope
  )) ?? projectForScope(scope, projects);
}

function primaryCatalogFilter(
  scope: string | null | undefined,
  project: ProjectSummary | undefined,
): ProjectSummary | undefined {
  if (!scope || !project || isExactQueueChoice(project)) return undefined;
  return canonicalizeProjectPath(primaryProjectScope(project).canonicalKey) === canonicalizeProjectPath(scope)
    ? project
    : undefined;
}

function isExactQueueChoice(project: ProjectSummary): boolean {
  return project.projectKey.startsWith("queue-scope:") || project.projectKey.startsWith("uncatalogued:");
}

function projectHref(project: ProjectSummary | undefined): string | undefined {
  if (!project || isExactQueueChoice(project)) return undefined;
  return `/projects/${encodeURIComponent(project.projectKey)}`;
}

function projectLabel(project: ProjectSummary | undefined, fallback?: string): string | undefined {
  if (project && isExactQueueChoice(project)) return project.label;
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
