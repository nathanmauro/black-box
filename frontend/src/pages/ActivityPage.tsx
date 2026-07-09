import { useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, Match, Switch } from "solid-js";
import ProjectPicker from "../components/ProjectPicker";
import { getProjects } from "../lib/api";
import { readRememberedProjectKey, rememberProjectKey } from "../lib/projects";
import SessionsPage from "./SessionsPage";
import SearchPage, { type SearchMode } from "./SearchPage";
import StreamPage from "./StreamPage";

type ActivityMode = "stream" | "browse" | SearchMode;

const MODES: Array<{ id: ActivityMode; label: string; hint: string }> = [
  { id: "stream", label: "Stream", hint: "global event firehose" },
  { id: "browse", label: "Browse", hint: "session rail and prompt reader" },
  { id: "find", label: "Find", hint: "faceted event search" },
  { id: "ask", label: "Ask", hint: "synthesized answer with citations" },
];

export default function ActivityPage() {
  const [params, setParams] = useSearchParams<{ q?: string; session?: string; view?: string; project?: string; event?: string }>();
  const [mode, setModeSignal] = createSignal<ActivityMode>(modeFromParams(params));
  const [projects] = createResource(getProjects, { initialValue: [] });
  const availableProjects = createMemo(() => (projects.error ? [] : projects()));
  const selectedProject = createMemo(() => availableProjects().find((project) => project.projectKey === params.project));
  const projectScopePending = createMemo(() => Boolean(params.project) && projects.loading && !selectedProject());

  createEffect(() => setModeSignal(modeFromParams(params)));
  createEffect(() => {
    if (params.project !== undefined || projects.loading || projects.error) return;
    const remembered = readRememberedProjectKey();
    if (!remembered) return;
    if (availableProjects().some((project) => project.projectKey === remembered)) {
      setParams({ project: remembered, session: undefined, event: undefined });
    } else {
      rememberProjectKey(undefined);
    }
  });
  createEffect(() => {
    if (!params.project || projects.loading || selectedProject()) return;
    rememberProjectKey(undefined);
    setParams({ project: undefined, session: undefined, event: undefined });
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
    rememberProjectKey(projectKey);
    setParams({ project: projectKey, session: undefined, event: undefined });
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
          selectedProjectKey={params.project}
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
        <Switch>
          <Match when={mode() === "browse"}>
            <SessionsPage selectedSessionId={params.session} defaultToFirst onSelectSession={selectSession} />
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
