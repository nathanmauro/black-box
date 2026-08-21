import { A, useNavigate, useParams, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, onMount, Show, type JSX } from "solid-js";
import KindBadge from "../components/KindBadge";
import ProjectPicker from "../components/ProjectPicker";
import SourceDot from "../components/SourceDot";
import TrajectoryView from "../components/TrajectoryView";
import { EventRenderer } from "../components/events/EventRow";
import {
  deleteProjectAlias,
  getProjectMelds,
  getProjects,
  getProjectSessions,
  getProjectTimeline,
  getProjectTrajectory,
  mergeProjectAlias,
  type AgentEvent,
  type ProjectMeld,
  type ProjectMeldSessionRef,
  type ProjectScope,
  type ProjectSummary,
  type ProjectTimelineBlock,
  type ProjectTimelineResponse,
  type ProjectTrajectoryResponse,
} from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import {
  findProjectByIdentifier,
  NO_PROJECT_SCOPE,
  primaryProjectScope,
  projectScopeDisplayName,
  projectScopes,
  projectShortName,
  rankProjects,
} from "../lib/projects";
import { buildTrajectory, findTrajectoryNodeForCapture, type TrajectoryGraphNode } from "../lib/trajectory";

const TIMELINE_LIMIT = 250;
const SESSION_LIMIT = 20;
const STORY_VIEW_KEY = "bb.projectStoryView";

type ProjectStoryView = "trajectory" | "timeline";

export default function ProjectsPage() {
  const params = useParams<{ projectKey?: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams<{ focus?: string }>();
  const [mergeTargetKey, setMergeTargetKey] = createSignal<string>();
  const [curationError, setCurationError] = createSignal<string | null>(null);
  const [curationBusyKey, setCurationBusyKey] = createSignal<string | null>(null);
  const [storyView, setStoryViewSignal] = createSignal<ProjectStoryView>(loadProjectStoryView());
  const [projects, { refetch: refetchProjects }] = createResource(getProjects, {
    initialValue: [] as ProjectSummary[],
  });
  const projectList = createMemo(() => (projects.error ? [] : projects()));
  const routeProject = createMemo(() => findProjectByIdentifier(projectList(), params.projectKey));
  const selectedProject = createMemo(() => routeProject() || (!params.projectKey ? projectList()[0] : undefined));
  const selectedKey = createMemo(() => selectedProject()?.projectKey || null);
  const projectCandidates = createMemo(() =>
    projectList().filter(
      (project) => project.projectKey !== selectedProject()?.projectKey && !isProtectedProject(project),
    ),
  );
  const selectedMergeTarget = createMemo(() => findProjectByIdentifier(projectCandidates(), mergeTargetKey()));
  const [sessions, { refetch: refetchSessions }] = createResource(
    selectedKey,
    async (key) => (key ? getProjectSessions(key, SESSION_LIMIT) : []),
    { initialValue: [] },
  );
  const [timeline, { refetch: refetchTimeline }] = createResource(
    selectedKey,
    async (key) => (key ? getLatestProjectTimeline(key) : emptyTimeline()),
    { initialValue: emptyTimeline() },
  );
  const [trajectory, { refetch: refetchTrajectory }] = createResource(
    selectedKey,
    async (key) => (key ? getProjectTrajectory(key) : emptyTrajectory()),
    { initialValue: emptyTrajectory() },
  );
  const [melds, { refetch: refetchMelds }] = createResource(
    selectedKey,
    async (key) => (key ? getProjectMelds(key) : []),
    { initialValue: [] as ProjectMeld[] },
  );
  const sessionList = createMemo(() => (sessions.error ? [] : sessions()));
  const timelineValue = createMemo(() => (timeline.error ? emptyTimeline() : timeline()));
  const trajectoryValue = createMemo(() => (trajectory.error ? emptyTrajectory() : trajectory()));
  const trajectoryGraph = createMemo(() => buildTrajectory(trajectoryValue(), Date.now()));
  // Trajectory selection is URL state (spec §9, D14: `?focus=` = selection): `focus=<nodeId>`
  // selects a node directly; `focus=capture:<eventId>` resolves to the node containing that
  // capture. Selection survives reload and the back button walks selection history.
  const selectedTrajectoryNodeId = createMemo<string | null>(() => {
    const focus = searchParams.focus;
    if (!focus) return null;
    if (focus.startsWith("capture:")) {
      return findTrajectoryNodeForCapture(trajectoryGraph(), focus.slice("capture:".length))?.id ?? null;
    }
    return focus;
  });
  const selectedTrajectoryNode = createMemo(() =>
    trajectoryGraph().nodes.find((node) => node.id === selectedTrajectoryNodeId()) ?? null,
  );
  const meldList = createMemo(() => (melds.error ? [] : melds()));
  const storylineCount = createMemo(() =>
    storyView() === "trajectory"
      ? `${trajectoryGraph().nodes.length.toLocaleString()} ${trajectoryGraph().nodes.length === 1 ? "node" : "nodes"}`
      : `${timelineValue().count.toLocaleString()} ${timelineValue().count === 1 ? "block" : "blocks"}`,
  );

  createEffect(() => {
    const project = routeProject();
    if (!params.projectKey || !project || params.projectKey === project.projectKey) return;
    navigate(projectHref(project), { replace: true });
  });

  createEffect((previousKey: string | null | undefined) => {
    const key = selectedKey();
    setMergeTargetKey(undefined);
    setCurationError(null);
    // Clear the focus selection only on a real project switch — never on the initial catalog
    // resolve (null → key), which would wipe a deep-linked ?focus= before it ever rendered.
    if (previousKey != null && key != null && previousKey !== key && searchParams.focus) {
      setSearchParams({ focus: undefined }, { replace: true });
    }
    return key;
  }, undefined as string | null | undefined);

  const clearTrajectorySelection = (event: KeyboardEvent) => {
    if (event.key !== "Escape" || event.defaultPrevented) return;
    if (storyView() !== "trajectory" || !searchParams.focus) return;
    setSearchParams({ focus: undefined });
  };
  onMount(() => window.addEventListener("keydown", clearTrajectorySelection));
  onCleanup(() => window.removeEventListener("keydown", clearTrajectorySelection));

  function setStoryView(view: ProjectStoryView) {
    setStoryViewSignal(view);
    saveProjectStoryView(view);
  }

  function selectProject(projectKey: string | undefined) {
    if (projectKey) navigate(`/projects/${encodeURIComponent(projectKey)}`);
    else navigate("/projects");
  }

  async function mergeCandidate() {
    const project = selectedProject();
    const candidate = selectedMergeTarget();
    if (!project || !candidate || curationBusyKey()) return;
    const initiatingProjectKey = project.projectKey;
    const aliasScope = primaryProjectScope(candidate);
    const primaryScope = primaryProjectScope(project);
    setCurationBusyKey(aliasScope.canonicalKey);
    setCurationError(null);
    try {
      await mergeProjectAlias(aliasScope.canonicalKey, primaryScope.canonicalKey);
      if (selectedKey() === initiatingProjectKey) setMergeTargetKey(undefined);
      await refreshWorkspace();
    } catch (error) {
      if (selectedKey() === initiatingProjectKey) {
        setCurationError(errorMessage(error, "Unable to merge project scopes."));
      }
    } finally {
      setCurationBusyKey(null);
    }
  }

  async function undoScope(scope: ProjectScope) {
    const initiatingProjectKey = selectedKey();
    if (!initiatingProjectKey || curationBusyKey()) return;
    setCurationBusyKey(scope.canonicalKey);
    setCurationError(null);
    try {
      await deleteProjectAlias(scope.canonicalKey);
      await refreshWorkspace();
    } catch (error) {
      if (selectedKey() === initiatingProjectKey) {
        setCurationError(errorMessage(error, "Unable to undo project alias."));
      }
    } finally {
      setCurationBusyKey(null);
    }
  }

  async function refreshWorkspace() {
    await refetchProjects();
    await Promise.all([refetchSessions(), refetchTimeline(), refetchTrajectory(), refetchMelds()]);
  }

  return (
    <section class="projects-page" aria-labelledby="projects-title">
      <aside class="project-catalog-pane">
        <header class="project-catalog-head">
          <div>
            <p class="eyebrow">flight recorder</p>
            <h1 id="projects-title">Projects</h1>
          </div>
          <span>{projectList().length.toLocaleString()}</span>
        </header>
        <p class="project-catalog-intro">
          Group recorded working directories into durable project identities without rewriting history.
        </p>
        <ProjectPicker
          projects={projectList()}
          selectedProjectKey={selectedProject()?.projectKey}
          loading={projects.loading}
          error={projects.error ? "Unable to load the project catalog." : null}
          allDescription="Project catalog"
          allowAll={false}
          onSelect={selectProject}
        />

        <div class="project-catalog-label">
          <span class="eyebrow">Project catalog</span>
          <span>{projectList().length.toLocaleString()} observed</span>
        </div>
        <Show when={!projects.loading} fallback={<WorkspaceState title="Loading project catalog" detail="Reading observed working directories…" />}>
          <Show
            when={!projects.error}
            fallback={
              <WorkspaceState
                tone="error"
                title="Project catalog unavailable"
                detail={errorMessage(projects.error, "Black Box could not load project identities.")}
                action={<button type="button" onClick={() => void refetchProjects()}>Retry</button>}
              />
            }
          >
            <Show
              when={projectList().length}
              fallback={<WorkspaceState title="No observed projects" detail="Projects appear after Black Box records a working directory." />}
            >
              <nav class="project-catalog-list" aria-label="Project catalog">
                <For each={rankProjects(projectList(), "")}>
                  {(project) => (
                    <button
                      type="button"
                      classList={{
                        "project-catalog-row": true,
                        "project-catalog-row--active": project.projectKey === selectedProject()?.projectKey,
                      }}
                      onClick={() => selectProject(project.projectKey)}
                    >
                      <span class="project-catalog-row-main">
                        <strong>{projectShortName(project)}</strong>
                        <small>{truncatePath(primaryProjectScope(project).canonicalKey)}</small>
                      </span>
                      <span class="project-catalog-row-meta">
                        <span>{project.sessionCount.toLocaleString()} sessions</span>
                        <span>{projectScopes(project).length.toLocaleString()} scopes</span>
                      </span>
                    </button>
                  )}
                </For>
              </nav>
            </Show>
          </Show>
        </Show>
      </aside>

      <main class="project-workspace">
        <Show when={!projects.loading && !projects.error}>
          <Show
            when={!params.projectKey || routeProject()}
            fallback={
              <WorkspaceState
                tone="error"
                title="Unknown project identity"
                detail={`The project “${params.projectKey}” is not present in the current catalog.`}
                action={<A href="/projects">Open project catalog</A>}
              />
            }
          >
            <Show
              when={selectedProject()}
              fallback={<WorkspaceState title="Select a project" detail="Choose a catalog entry to inspect its recorded storyline." />}
            >
              {(project) => (
                <>
                  <ProjectHeader project={project()} />
                  <div class="project-workspace-grid">
                    <section class="project-storyline" aria-labelledby="project-storyline-title">
                      <div class="pane-head project-storyline-head">
                        <div class="project-storyline-title">
                          <span id="project-storyline-title" class="eyebrow">
                            {storyView() === "trajectory" ? "Trajectory" : "Hybrid storyline"}
                          </span>
                          <div class="project-storyline-toggle" role="group" aria-label="Project storyline view">
                            <button
                              type="button"
                              aria-pressed={storyView() === "trajectory"}
                              onClick={() => setStoryView("trajectory")}
                            >
                              Trajectory
                            </button>
                            <button
                              type="button"
                              aria-pressed={storyView() === "timeline"}
                              onClick={() => setStoryView("timeline")}
                            >
                              Timeline
                            </button>
                          </div>
                        </div>
                        <span>{storylineCount()}</span>
                      </div>
                      <Show
                        when={storyView() === "trajectory"}
                        fallback={(
                          <div class="project-timeline">
                            <Show
                              when={!timeline.loading}
                              fallback={<WorkspaceState title="Loading storyline" detail="Combining raw events and saved melds…" />}
                            >
                              <Show
                                when={!timeline.error}
                                fallback={<WorkspaceState tone="error" title="Storyline unavailable" detail={errorMessage(timeline.error)} />}
                              >
                                <Show
                                  when={timelineValue().items.length}
                                  fallback={<WorkspaceState title={`No recorded storyline for ${projectShortName(project())}`} detail="This project identity has no timeline blocks yet." />}
                                >
                                  <For each={timelineValue().items}>
                                    {(block) => <TimelineBlock block={block} project={project()} />}
                                  </For>
                                </Show>
                              </Show>
                            </Show>
                          </div>
                        )}
                      >
                        <div class="project-trajectory">
                          <Show
                            when={!trajectory.loading}
                            fallback={<WorkspaceState title="Loading trajectory" detail="Assembling bursts, head, and futures…" />}
                          >
                            <Show
                              when={!trajectory.error}
                              fallback={<WorkspaceState tone="error" title="Trajectory unavailable" detail={errorMessage(trajectory.error)} />}
                            >
                              <Show
                                when={trajectoryGraph().nodes.length}
                                fallback={<WorkspaceState title="No trajectory captures yet" detail="Timeline tab still reachable." />}
                              >
                                <TrajectoryView
                                  graph={trajectoryGraph()}
                                  selectedNodeId={selectedTrajectoryNodeId()}
                                  onSelect={(node) => setSearchParams({ focus: node.id })}
                                />
                              </Show>
                            </Show>
                          </Show>
                        </div>
                      </Show>
                    </section>

                    <aside class="project-context-rail">
                      <Show when={selectedTrajectoryNode()}>
                        {(node) => <TrajectoryDetailCard node={node()} project={project()} />}
                      </Show>
                      <ProjectIdentityPanel
                        project={project()}
                        candidates={projectCandidates()}
                        selectedMergeTargetKey={mergeTargetKey()}
                        busyKey={curationBusyKey()}
                        error={curationError()}
                        onSelectMergeTarget={setMergeTargetKey}
                        onMerge={() => void mergeCandidate()}
                        onUndo={(scope) => void undoScope(scope)}
                      />
                      <RecentSessionsPanel project={project()} sessions={sessionList()} loading={sessions.loading} error={sessions.error} />
                      <SavedMeldsPanel project={project()} melds={meldList()} loading={melds.loading} error={melds.error} />
                    </aside>
                  </div>
                </>
              )}
            </Show>
          </Show>
        </Show>
      </main>
    </section>
  );
}

function ProjectHeader(props: { project: ProjectSummary }) {
  const primary = () => primaryProjectScope(props.project);
  return (
    <header class="project-detail-header">
      <div class="project-detail-identity">
        <p class="eyebrow">grouped project identity</p>
        <h2>{projectShortName(props.project)}</h2>
        <p title={primary().canonicalKey}>{truncatePath(primary().canonicalKey)}</p>
      </div>
      <div class="project-header-actions" aria-label="Project actions">
        <A href={activityHref(props.project, "browse")}>Activity Browse</A>
        <A href={boardHref(props.project)}>Board</A>
        <A href={recallHref(props.project)}>Recall</A>
      </div>
      <div class="project-stat-strip">
        <Metric label="session" value={props.project.sessionCount} />
        <Metric label="event" value={props.project.eventCount} />
        <Metric label="meld" value={props.project.savedMeldCount} />
        <span>{projectScopes(props.project).length.toLocaleString()} scopes</span>
        <span>seen {timeAgo(props.project.lastSeenAt)}</span>
      </div>
    </header>
  );
}

function TrajectoryDetailCard(props: { node: TrajectoryGraphNode; project: ProjectSummary }) {
  const sourceCapture = () => props.node.sourceCapture;
  const sourceSessionId = () => props.node.sessionId || sourceCapture()?.sessionId;
  return (
    <section class="project-rail-panel trajectory-detail-card" aria-labelledby="trajectory-detail-title">
      <div class="pane-head">
        <span id="trajectory-detail-title" class="eyebrow">Trajectory detail</span>
        <span>{kindLabel(props.node.kind)}</span>
      </div>
      <div class="project-rail-body trajectory-detail-body">
        <strong>{props.node.label}</strong>
        <Show when={props.node.eyebrow}>
          {(eyebrow) => <small>{eyebrow()}</small>}
        </Show>
        <p>{props.node.fullText || props.node.label}</p>

        <Show when={sourceCapture()}>
          {(capture) => (
            <div class="trajectory-detail-source">
              <span>{kindLabel(capture().kind)}</span>
              <code>{capture().id}</code>
              <Show when={capture().observedAt}>
                {(observedAt) => <time>{timeAgo(observedAt())}</time>}
              </Show>
            </div>
          )}
        </Show>

        <Show when={viewInStreamHref(props.node, props.project)}>
          {(href) => (
            <A class="trajectory-detail-link" href={href()}>
              View in Stream <span aria-hidden="true">→</span>
            </A>
          )}
        </Show>

        <Show when={sourceSessionId()}>
          {(sessionId) => (
            <A class="trajectory-detail-link" href={sessionHref(props.project, sessionId())}>
              Session {sourceCapture()?.sessionTitle || sourceCapture()?.clientSessionId || sessionId()}
            </A>
          )}
        </Show>

        <Show when={props.node.task}>
          {(task) => (
            <A class="trajectory-detail-link" href={`/board?task=${encodeURIComponent(task().id)}`}>
              Task {task().status}: {task().title}
            </A>
          )}
        </Show>

        <Show when={typeof props.node.confidence === "number"}>
          <div class="trajectory-detail-source">
            <span>Ghost confidence</span>
            <strong>{Math.round((props.node.confidence ?? 0) * 100)}%</strong>
          </div>
        </Show>

        <Show when={props.node.members?.length}>
          <div class="trajectory-detail-list">
            <span>Burst members</span>
            <ul>
              <For each={props.node.members}>
                {(capture) => (
                  <li>
                    <strong>{kindLabel(capture.kind)}</strong>
                    <span>{capture.headline || capture.text || capture.id}</span>
                    <Show when={capture.observedAt}>
                      {(observedAt) => <time>{timeAgo(observedAt())}</time>}
                    </Show>
                  </li>
                )}
              </For>
            </ul>
          </div>
        </Show>

        <Show when={props.node.alternatives?.length}>
          <div class="trajectory-detail-list">
            <span>Alternatives</span>
            <ul>
              <For each={props.node.alternatives}>
                {(alternative) => <li>{alternative}</li>}
              </For>
            </ul>
          </div>
        </Show>

        <Show when={props.node.items?.length}>
          <div class="trajectory-detail-list">
            <span>Remaining ranked futures</span>
            <ul>
              <For each={props.node.items}>
                {(item) => (
                  <li>
                    <strong>{futureSourceLabel(item.source)}</strong>
                    <span>{item.text}</span>
                  </li>
                )}
              </For>
            </ul>
          </div>
        </Show>
      </div>
    </section>
  );
}

function ProjectIdentityPanel(props: {
  project: ProjectSummary;
  candidates: ProjectSummary[];
  selectedMergeTargetKey?: string;
  busyKey: string | null;
  error: string | null;
  onSelectMergeTarget: (projectKey: string | undefined) => void;
  onMerge: () => void;
  onUndo: (scope: ProjectScope) => void;
}) {
  const scopes = createMemo(() => projectScopes(props.project));
  const variants = createMemo(() => scopes().filter((scope) => !scope.primary));
  return (
    <section class="project-rail-panel project-identity-panel" aria-labelledby="project-identity-title">
      <div class="pane-head">
        <span id="project-identity-title" class="eyebrow">Identity &amp; scopes</span>
        <span>{scopes().length.toLocaleString()}</span>
      </div>
      <div class="project-rail-body">
        <div class="project-primary-scope">
          <span>Primary</span>
          <strong>{projectScopeDisplayName(primaryProjectScope(props.project))}</strong>
          <code>{primaryProjectScope(props.project).canonicalKey}</code>
        </div>
        <Show
          when={variants().length}
          fallback={<p class="project-rail-empty">No variant scopes are grouped into this project.</p>}
        >
          <ul class="project-scope-list">
            <For each={variants()}>
              {(scope) => (
                <li>
                  <span>
                    <strong>{projectScopeDisplayName(scope)}</strong>
                    <code>{scope.canonicalKey}</code>
                    <small classList={{ "project-scope-origin": true, "project-scope-origin--manual": scope.source === "manual" }}>
                      {projectScopeOrigin(scope)}
                    </small>
                  </span>
                  <Show when={scope.source === "manual"}>
                    <button
                      type="button"
                      aria-label={`Undo merge for ${scope.canonicalKey}`}
                      disabled={Boolean(props.busyKey)}
                      onClick={() => props.onUndo(scope)}
                    >
                      {props.busyKey === scope.canonicalKey ? "Undoing…" : "Undo"}
                    </button>
                  </Show>
                </li>
              )}
            </For>
          </ul>
        </Show>

        <Show
          when={!isProtectedProject(props.project)}
          fallback={<p class="project-rail-empty project-curation-note">Protected system scopes cannot be merged.</p>}
        >
          <details class="project-curation">
            <summary>Merge another catalog entry</summary>
            <p>
              Treat another observed scope as this project. This changes grouping only; raw sessions and event history stay untouched.
            </p>
            <ProjectPicker
              projects={props.candidates}
              selectedProjectKey={props.selectedMergeTargetKey}
              allDescription="Choose a project to merge"
              onSelect={props.onSelectMergeTarget}
            />
            <button
              type="button"
              class="primary-action project-merge-action"
              disabled={!props.selectedMergeTargetKey || Boolean(props.busyKey)}
              onClick={props.onMerge}
            >
              {props.busyKey ? "Updating identity…" : "Merge into this project"}
            </button>
          </details>
        </Show>
        <Show when={props.error}>{(message) => <p class="inline-error" role="alert">{message()}</p>}</Show>
      </div>
    </section>
  );
}

function RecentSessionsPanel(props: {
  project: ProjectSummary;
  sessions: Array<{ id: string; source: string; clientSessionId: string; title: string; eventCount: number; lastSeenAt: string }>;
  loading: boolean;
  error: unknown;
}) {
  return (
    <section class="project-rail-panel" aria-labelledby="recent-project-sessions-title">
      <div class="pane-head">
        <span id="recent-project-sessions-title" class="eyebrow">Recent sessions</span>
        <span>{props.sessions.length.toLocaleString()}</span>
      </div>
      <div class="project-rail-body">
        <Show when={!props.loading} fallback={<p class="project-rail-empty">Loading project sessions…</p>}>
          <Show when={!props.error} fallback={<p class="inline-error" role="alert">{errorMessage(props.error, "Sessions unavailable.")}</p>}>
            <Show
              when={props.sessions.length}
              fallback={<p class="project-rail-empty">No sessions have been recorded for this project.</p>}
            >
              <ul class="project-session-list">
                <For each={props.sessions}>
                  {(session) => (
                    <li>
                      <A href={sessionHref(props.project, session.id)}>
                        <SourceDot source={session.source} />
                        <span>
                          <strong>{session.title || session.clientSessionId}</strong>
                          <small>{session.eventCount.toLocaleString()} {session.eventCount === 1 ? "event" : "events"} · {timeAgo(session.lastSeenAt)}</small>
                        </span>
                      </A>
                    </li>
                  )}
                </For>
              </ul>
            </Show>
          </Show>
        </Show>
      </div>
    </section>
  );
}

function SavedMeldsPanel(props: { project: ProjectSummary; melds: ProjectMeld[]; loading: boolean; error: unknown }) {
  return (
    <section class="project-rail-panel" aria-labelledby="saved-project-melds-title">
      <div class="pane-head">
        <span id="saved-project-melds-title" class="eyebrow">Saved melds</span>
        <span>{props.melds.length.toLocaleString()}</span>
      </div>
      <div class="project-rail-body">
        <Show when={!props.loading} fallback={<p class="project-rail-empty">Loading saved melds…</p>}>
          <Show when={!props.error} fallback={<p class="inline-error" role="alert">{errorMessage(props.error, "Saved melds unavailable.")}</p>}>
            <Show
              when={props.melds.length}
              fallback={<p class="project-rail-empty">No saved melds for this project.</p>}
            >
              <div class="saved-meld-list">
                <For each={props.melds}>
                  {(meld) => (
                    <article class="saved-meld-row">
                      <strong>{meld.title}</strong>
                      <small>{meld.provider} · {meld.model} · {timeAgo(meld.createdAt)}</small>
                      <p>{meld.body}</p>
                      <SourceSessionLinks project={props.project} sessions={meld.sessions || []} />
                    </article>
                  )}
                </For>
              </div>
            </Show>
          </Show>
        </Show>
      </div>
    </section>
  );
}

function TimelineBlock(props: { block: ProjectTimelineBlock; project: ProjectSummary }) {
  const blockLabel = createMemo(() => timelineBlockLabel(props.block));
  return (
    <div class="project-timeline-row">
      <div class="timeline-block-label">
        <span>{blockLabel()}</span>
        <span>{props.block.sessionTitle || props.block.clientSessionId || props.block.headline}</span>
        <time>{timeAgo(props.block.observedAt)}</time>
      </div>
      <TimelineBlockCard block={props.block} project={props.project} />
    </div>
  );
}

function TimelineBlockCard(props: { block: ProjectTimelineBlock; project: ProjectSummary }) {
  if (props.block.sourceType === "saved_meld") {
    return <SavedMeldTimelineCard block={props.block} project={props.project} />;
  }
  if (normalizeEventType(props.block.eventType || props.block.blockType || props.block.sourceType) === "Projection") {
    return <ProjectionTimelineCard block={props.block} />;
  }
  return <EventRenderer event={timelineBlockToEvent(props.block)} />;
}

function ProjectionTimelineCard(props: { block: ProjectTimelineBlock }) {
  const metadata = createMemo(() => metadataRecord(props.block.metadata));
  const paths = createMemo(() => projectionPaths(metadata().paths));
  const basis = createMemo(() => metadataValue(metadata(), "basis", ""));
  const headline = createMemo(() => props.block.headline || paths()[0]?.title || "Projected futures");
  return (
    <article class="event-card">
      <div class="event-card-head">
        <SourceDot source={props.block.source} />
        <KindBadge kind="Projection" />
        <strong>{truncatePath(headline())}</strong>
        <span class="event-card-time">{timeAgo(props.block.observedAt)}</span>
      </div>
      <Show when={basis()}>
        {(text) => <p class="event-rationale">{truncatePath(text())}</p>}
      </Show>
      <Show when={paths().length} fallback={props.block.text ? <p class="reader-text">{props.block.text}</p> : null}>
        <div class="metadata-list">
          <span>paths</span>
          <ul>
            <For each={paths()}>
              {(path) => (
                <li>
                  <strong>{truncatePath(path.title)}</strong>
                  {path.description ? ` - ${truncatePath(path.description)}` : ""}
                  {path.confidence === undefined ? "" : ` (${Math.round(path.confidence * 100)}%)`}
                </li>
              )}
            </For>
          </ul>
        </div>
      </Show>
    </article>
  );
}

function SavedMeldTimelineCard(props: { block: ProjectTimelineBlock; project: ProjectSummary }) {
  const metadata = createMemo(() => metadataRecord(props.block.metadata));
  return (
    <article class="saved-meld-card">
      <div class="saved-meld-card-head">
        <strong>{props.block.headline || "Saved meld"}</strong>
        <time>{timeAgo(props.block.observedAt)}</time>
      </div>
      <div class="saved-meld-provenance">
        <span>{metadataValue(metadata(), "provider", "local")}</span>
        <span>{metadataValue(metadata(), "model", "context-bundle")}</span>
        <span>{metadataValue(metadata(), "executionMode", "export_bundle")}</span>
      </div>
      <p>{props.block.text}</p>
      <SourceSessionLinks project={props.project} sessions={props.block.sourceSessions || []} />
    </article>
  );
}

function SourceSessionLinks(props: { project: ProjectSummary; sessions: ProjectMeldSessionRef[] }) {
  return (
    <Show when={props.sessions.length}>
      <div class="meld-source-links">
        <span>source sessions</span>
        <For each={props.sessions}>
          {(session) => <A href={sessionHref(props.project, session.id)}>{session.title || session.clientSessionId}</A>}
        </For>
      </div>
    </Show>
  );
}

function WorkspaceState(props: { title: string; detail: string; tone?: "error"; action?: JSX.Element }) {
  return (
    <div classList={{ "project-workspace-state": true, "project-workspace-state--error": props.tone === "error" }} role={props.tone === "error" ? "alert" : undefined}>
      <strong>{props.title}</strong>
      <p>{props.detail}</p>
      {props.action}
    </div>
  );
}

function Metric(props: { label: string; value: number }) {
  return <span><strong>{props.value.toLocaleString()}</strong> {props.value === 1 ? props.label : `${props.label}s`}</span>;
}

function projectHref(project: ProjectSummary): string {
  return `/projects/${encodeURIComponent(project.projectKey)}`;
}

const STREAM_LINK_KINDS = new Set<string>(["deep-past", "burst", "head"]);

/**
 * "View in Stream" (spec §9, D14): the node's burst time range as absolute-ISO since:/until:
 * tokens in the VISIBLE q, with the project scope carried by the `?project=` convention so the
 * hidden project_group injection applies at the API boundary — never in the visible q. Null for
 * non-burst nodes or when the range is unknown (e.g. a capped deep-past with no members).
 */
function viewInStreamHref(node: TrajectoryGraphNode, project: ProjectSummary): string | null {
  if (!STREAM_LINK_KINDS.has(node.kind)) return null;
  if (!node.startMs || !node.endMs) return null;
  const q = `since:${new Date(node.startMs).toISOString()} until:${new Date(node.endMs).toISOString()}`;
  const query = new URLSearchParams({ q, project: project.projectKey });
  return `/stream?${query.toString()}`;
}

function activityHref(project: ProjectSummary, view: "browse"): string {
  const query = new URLSearchParams({ view, project: project.projectKey });
  return `/?${query.toString()}`;
}

function sessionHref(project: ProjectSummary, sessionId: string): string {
  const query = new URLSearchParams({ view: "browse", project: project.projectKey, session: sessionId });
  return `/?${query.toString()}`;
}

function boardHref(project: ProjectSummary): string {
  const query = new URLSearchParams({ project: primaryProjectScope(project).canonicalKey });
  return `/board?${query.toString()}`;
}

function recallHref(project: ProjectSummary): string {
  const query = new URLSearchParams({ scope: primaryProjectScope(project).canonicalKey });
  return `/recall?${query.toString()}`;
}

function newestFirst(response: ProjectTimelineResponse): ProjectTimelineResponse {
  return {
    ...response,
    items: [...response.items].sort((left, right) => timestampValue(right.observedAt) - timestampValue(left.observedAt)),
  };
}

async function getLatestProjectTimeline(projectKey: string): Promise<ProjectTimelineResponse> {
  const probe = await getProjectTimeline(projectKey, 1, 0);
  if (probe.count <= 1) return newestFirst(probe);
  let expectedCount = probe.count;
  let page = probe;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const offset = Math.max(0, expectedCount - TIMELINE_LIMIT);
    page = await getProjectTimeline(projectKey, TIMELINE_LIMIT, offset);
    if (page.count === expectedCount) return newestFirst(page);
    expectedCount = page.count;
  }
  return newestFirst(page);
}

function emptyTimeline(): ProjectTimelineResponse {
  return { projectKey: "", canonicalKey: "", label: "", limit: TIMELINE_LIMIT, offset: 0, count: 0, items: [] };
}

function emptyTrajectory(): ProjectTrajectoryResponse {
  return { projectKey: "", canonicalKey: "", label: "", generatedAt: "", totalCaptures: 0, captures: [], tasks: [] };
}

function loadProjectStoryView(): ProjectStoryView {
  try {
    return localStorage.getItem(STORY_VIEW_KEY) === "timeline" ? "timeline" : "trajectory";
  } catch {
    return "trajectory";
  }
}

function saveProjectStoryView(view: ProjectStoryView): void {
  try {
    localStorage.setItem(STORY_VIEW_KEY, view);
  } catch {
    // Storage failures degrade to the in-memory tab state for this session.
  }
}

function timestampValue(value: string | null | undefined): number {
  return value ? Date.parse(value) || 0 : 0;
}

function errorMessage(error: unknown, fallback = "Unable to load this project data."): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

function metadataRecord(metadata: unknown): Record<string, unknown> {
  return metadata && typeof metadata === "object" && !Array.isArray(metadata) ? (metadata as Record<string, unknown>) : {};
}

function metadataValue(metadata: Record<string, unknown>, key: string, fallback: string): string {
  const value = metadata[key];
  return typeof value === "string" && value.trim() ? value : fallback;
}

function kindLabel(value: string): string {
  return value.replaceAll("-", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function futureSourceLabel(value: string): string {
  if (value === "nextAction") return "Next action";
  if (value === "openLoop") return "Open loop";
  if (value === "task") return "Task";
  return "Projection";
}

function timelineBlockLabel(block: ProjectTimelineBlock): string {
  return normalizeEventType(block.eventType || block.blockType || block.sourceType);
}

function timelineBlockToEvent(block: ProjectTimelineBlock): AgentEvent {
  return {
    id: block.id,
    sessionId: block.sessionId || block.id,
    source: block.source || "unknown",
    clientSessionId: block.clientSessionId || "",
    eventType: normalizeEventType(block.eventType || block.blockType || block.sourceType),
    role: block.role || undefined,
    text: block.text || block.headline || "",
    toolName: block.toolName || undefined,
    toolInputJson: block.toolInputJson || undefined,
    toolOutputJson: block.toolOutputJson || undefined,
    metadata: timelineMetadata(block),
    observedAt: block.observedAt || new Date(0).toISOString(),
  };
}

function timelineMetadata(block: ProjectTimelineBlock): unknown {
  const metadata =
    block.metadata && typeof block.metadata === "object" && !Array.isArray(block.metadata)
      ? { ...(block.metadata as Record<string, unknown>) }
      : {};
  const eventType = normalizeEventType(block.eventType || block.blockType || block.sourceType);
  if (eventType === "Decision") {
    if (!metadata.decision && block.headline) metadata.decision = block.headline;
    if (!metadata.rationale && block.text) metadata.rationale = block.text;
  }
  if (eventType === "Handoff" && !metadata.contextSummary && block.text) metadata.contextSummary = block.text;
  if (eventType === "Observation" && !metadata.observation && block.text) metadata.observation = block.text;
  return Object.keys(metadata).length ? metadata : block.metadata;
}

function normalizeEventType(value: string | null | undefined): string {
  const normalized = String(value || "Timeline").toLowerCase();
  if (normalized === "decision") return "Decision";
  if (normalized === "handoff") return "Handoff";
  if (normalized === "observation") return "Observation";
  if (normalized === "projection") return "Projection";
  return value || "Timeline";
}

function projectionPaths(value: unknown): Array<{ title: string; description?: string; confidence?: number }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((path) => {
    if (!path || typeof path !== "object" || Array.isArray(path)) return [];
    const record = path as Record<string, unknown>;
    const title = typeof record.title === "string" ? record.title.trim() : "";
    if (!title) return [];
    const description = typeof record.description === "string" && record.description.trim()
      ? record.description.trim()
      : undefined;
    const confidence = typeof record.confidence === "number" && Number.isFinite(record.confidence)
      ? Math.max(0, Math.min(1, record.confidence))
      : undefined;
    return [{ title, description, confidence }];
  });
}

function projectScopeOrigin(scope: ProjectScope): string {
  if (scope.source === "manual") return "Manual alias";
  if (scope.source === "nested-worktree") return "Automatic · nested worktree";
  if (scope.source === "git-commondir") return "Automatic · Git common directory";
  return "Automatic catalog scope";
}

function isProtectedProject(project: ProjectSummary): boolean {
  const canonicalKey = primaryProjectScope(project).canonicalKey;
  return canonicalKey === "/" || canonicalKey === NO_PROJECT_SCOPE;
}
