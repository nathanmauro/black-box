import { A, useNavigate, useParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, Show, type JSX } from "solid-js";
import ProjectPicker from "../components/ProjectPicker";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import {
  deleteProjectAlias,
  getProjectMelds,
  getProjects,
  getProjectSessions,
  getProjectTimeline,
  mergeProjectAlias,
  type AgentEvent,
  type ProjectMeld,
  type ProjectMeldSessionRef,
  type ProjectScope,
  type ProjectSummary,
  type ProjectTimelineBlock,
  type ProjectTimelineResponse,
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

const TIMELINE_LIMIT = 250;
const SESSION_LIMIT = 20;

export default function ProjectsPage() {
  const params = useParams<{ projectKey?: string }>();
  const navigate = useNavigate();
  const [mergeTargetKey, setMergeTargetKey] = createSignal<string>();
  const [curationError, setCurationError] = createSignal<string | null>(null);
  const [curationBusyKey, setCurationBusyKey] = createSignal<string | null>(null);
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
  const [melds, { refetch: refetchMelds }] = createResource(
    selectedKey,
    async (key) => (key ? getProjectMelds(key) : []),
    { initialValue: [] as ProjectMeld[] },
  );
  const sessionList = createMemo(() => (sessions.error ? [] : sessions()));
  const timelineValue = createMemo(() => (timeline.error ? emptyTimeline() : timeline()));
  const meldList = createMemo(() => (melds.error ? [] : melds()));

  createEffect(() => {
    const project = routeProject();
    if (!params.projectKey || !project || params.projectKey === project.projectKey) return;
    navigate(projectHref(project), { replace: true });
  });

  createEffect(() => {
    selectedKey();
    setMergeTargetKey(undefined);
    setCurationError(null);
  });

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
    await Promise.all([refetchSessions(), refetchTimeline(), refetchMelds()]);
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
                      <div class="pane-head">
                        <span id="project-storyline-title" class="eyebrow">Hybrid storyline</span>
                        <span>{timelineValue().count.toLocaleString()} blocks</span>
                      </div>
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
                    </section>

                    <aside class="project-context-rail">
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
  return (
    <div class="project-timeline-row">
      <div class="timeline-block-label">
        <span>{props.block.blockType || props.block.sourceType || "event"}</span>
        <span>{props.block.sessionTitle || props.block.clientSessionId || props.block.headline}</span>
        <time>{timeAgo(props.block.observedAt)}</time>
      </div>
      <Show
        when={props.block.sourceType === "saved_meld"}
        fallback={<EventRenderer event={timelineBlockToEvent(props.block)} />}
      >
        <SavedMeldTimelineCard block={props.block} project={props.project} />
      </Show>
    </div>
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
  return value || "Timeline";
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
