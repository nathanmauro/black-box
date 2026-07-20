import { useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, Match, Show, Switch } from "solid-js";
import ProjectPicker from "../components/ProjectPicker";
import { getProjects } from "../lib/api";
import { findProjectByIdentifier, readRememberedProjectKey, rememberProjectKey } from "../lib/projects";
import SessionsPage from "./SessionsPage";
import SearchPage, { type SearchMode } from "./SearchPage";
import StreamPage from "./StreamPage";

type ActivityMode = "stream" | "browse" | SearchMode;

const MODES: Array<{ id: ActivityMode; label: string; hint: string }> = [
  { id: "stream", label: "Stream", hint: "global event firehose" },
  { id: "browse", label: "Browse", hint: "session rail and conversation reader" },
  { id: "find", label: "Find", hint: "faceted event search" },
  { id: "ask", label: "Ask", hint: "synthesized answer with citations" },
];

export default function ActivityPage() {
  const [params, setParams] = useSearchParams<{ q?: string; session?: string; view?: string; project?: string; event?: string }>();
  const [mode, setModeSignal] = createSignal<ActivityMode>(modeFromParams(params));
  const [rememberedProjectKey, setRememberedProjectKey] = createSignal(readRememberedProjectKey());
  const [projects, { refetch: refetchProjects }] = createResource(getProjects, { initialValue: [] });
  const availableProjects = createMemo(() => (projects.error ? [] : projects()));
  const requestedProjectKey = createMemo(() => params.project || rememberedProjectKey());
  const selectedProject = createMemo(() => findProjectByIdentifier(availableProjects(), requestedProjectKey()));
  const projectScopePending = createMemo(() => Boolean(requestedProjectKey()) && projects.loading && !selectedProject());
  const projectScopeError = createMemo(() => {
    if (!requestedProjectKey() || projects.loading) return null;
    if (projects.error) return "The project catalog is unavailable, so Black Box will not broaden this scoped view to global activity.";
    if (!selectedProject()) return `The project “${requestedProjectKey()}” is not present in the current catalog.`;
    return null;
  });

  createEffect(() => setModeSignal(modeFromParams(params)));
  createEffect(() => {
    if (params.project !== undefined || projects.loading || projects.error) return;
    const remembered = rememberedProjectKey();
    if (!remembered) return;
    const rememberedProject = findProjectByIdentifier(availableProjects(), remembered);
    if (rememberedProject) {
      persistProjectKey(rememberedProject.projectKey);
      setParams({ project: rememberedProject.projectKey, session: undefined, event: undefined });
    } else {
      persistProjectKey(undefined);
      if (params.session || params.event) {
        setParams({ session: undefined, event: undefined });
      }
    }
  });
  createEffect(() => {
    const project = selectedProject();
    if (!params.project || !project || projects.loading || projects.error) return;
    persistProjectKey(project.projectKey);
    if (params.project !== project.projectKey) setParams({ project: project.projectKey });
  });
  createEffect(() => {
    if (!params.project || projects.loading || projects.error || selectedProject()) return;
    if (rememberedProjectKey() === params.project) persistProjectKey(undefined);
  });

  function selectMode(next: ActivityMode) {
    setModeSignal(next);
    if (next === "stream") {
      setParams({ view: undefined });
    } else if (next === "browse") {
      setParams({ view: "browse" });
    } else {
      setParams({ view: next });
    }
  }

  function selectProject(projectKey: string | undefined) {
    persistProjectKey(projectKey);
    setParams({ project: projectKey, session: undefined, event: undefined });
  }

  function persistProjectKey(projectKey: string | undefined) {
    rememberProjectKey(projectKey);
    setRememberedProjectKey(projectKey ?? null);
  }

  function selectSession(id: string) {
    setParams({ session: id, event: undefined });
  }

  function openSearchResult(sessionId: string, eventId?: string) {
    setModeSignal("browse");
    setParams({ session: sessionId, event: eventId, q: undefined, view: "browse" });
  }

  return (
    <section class="activity-page">
      <header class="activity-header">
        <div class="activity-title">
          <p class="eyebrow">workspace</p>
          <h1>Activity</h1>
          <p>Browse sessions, find events, and ask recorded memory from one surface.</p>
        </div>

        <ProjectPicker
          projects={availableProjects()}
          selectedProjectKey={requestedProjectKey() || undefined}
          loading={projects.loading}
          error={projects.error ? "Unable to load projects." : null}
          onSelect={selectProject}
        />

        <div class="activity-mode-tabs" role="tablist" aria-label="Activity mode">
          <For each={MODES}>
            {(item) => (
              <button
                type="button"
                role="tab"
                aria-label={item.label}
                aria-selected={mode() === item.id}
                classList={{ "activity-mode-tab": true, "activity-mode-tab--active": mode() === item.id }}
                onClick={() => selectMode(item.id)}
              >
                <span>{item.label}</span>
                <small>{item.hint}</small>
              </button>
            )}
          </For>
        </div>
      </header>

      <div class="activity-workspace">
        <Show
          when={!projectScopeError()}
          fallback={
            <div class="activity-scope-error" role="alert">
              <p class="eyebrow">project scope unavailable</p>
              <h2>Scoped activity is paused</h2>
              <p>{projectScopeError()}</p>
              <div>
                <button type="button" class="secondary-action" onClick={() => void refetchProjects()}>Retry catalog</button>
                <button type="button" class="secondary-action" onClick={() => selectProject(undefined)}>Clear project</button>
              </div>
            </div>
          }
        >
          <Switch>
            <Match when={mode() === "browse"}>
              <Show when={!projectScopePending()} fallback={<p class="empty-state">Resolving project scope…</p>}>
                <SessionsPage
                  selectedSessionId={params.session}
                  targetEventId={params.event}
                  project={selectedProject()}
                  defaultToFirst
                  onSelectSession={selectSession}
                />
              </Show>
            </Match>
            <Match when={mode() === "find" || mode() === "ask"}>
              <SearchPage
                mode={mode() as SearchMode}
                showModeTabs={false}
                project={selectedProject()}
                projectScopePending={projectScopePending()}
                onSelectSession={openSearchResult}
              />
            </Match>
            <Match when={mode() === "stream"}>
              <StreamPage project={selectedProject()} projectScopePending={projectScopePending()} />
            </Match>
          </Switch>
        </Show>
      </div>
    </section>
  );
}

function modeFromParams(params: { q?: string; view?: string }): ActivityMode {
  if (params.view === "ask") return "ask";
  if (params.view === "find") return "find";
  if (params.view === "browse") return "browse";
  return "stream";
}
