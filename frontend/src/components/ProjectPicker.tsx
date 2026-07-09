import { createEffect, createMemo, createSignal, For, Show } from "solid-js";
import type { ProjectSummary } from "../lib/api";
import { projectShortName, rankProjects } from "../lib/projects";

type ProjectPickerProps = {
  projects: ProjectSummary[];
  selectedProjectKey?: string;
  loading?: boolean;
  error?: string | null;
  onSelect: (projectKey: string | undefined) => void;
};

export default function ProjectPicker(props: ProjectPickerProps) {
  const [open, setOpen] = createSignal(false);
  const [query, setQuery] = createSignal("");
  const [selectedProjectKey, setSelectedProjectKey] = createSignal<string | undefined>(props.selectedProjectKey);
  const selectedProject = createMemo(() => props.projects.find((project) => project.projectKey === selectedProjectKey()));
  const results = createMemo(() => rankProjects(props.projects, query()).slice(0, 12));

  createEffect(() => {
    setSelectedProjectKey(props.selectedProjectKey);
  });

  function selectProject(projectKey: string | undefined) {
    setSelectedProjectKey(projectKey);
    props.onSelect(projectKey);
    setQuery("");
    setOpen(false);
  }

  return (
    <div class="project-picker">
      <button type="button" class="project-picker-button" onClick={() => setOpen((value) => !value)}>
        <span class="project-picker-label">Project</span>
        <strong>{selectedProject() ? projectShortName(selectedProject()!) : "All projects"}</strong>
        <small>{selectedProject()?.label || "Global activity"}</small>
      </button>

      <Show when={open()}>
        <div class="project-picker-popover">
          <button type="button" class="project-picker-all" onClick={() => selectProject(undefined)}>
            All projects
          </button>
          <input
            aria-label="Search projects"
            value={query()}
            onInput={(event) => setQuery(event.currentTarget.value)}
            placeholder="Search projects..."
            autocomplete="off"
          />
          <ul class="project-picker-results" role="listbox" aria-label="Project results">
            <Show
              when={!props.loading && !props.error}
              fallback={<li class="project-picker-empty">{props.error || "Loading projects..."}</li>}
            >
              <For each={results()}>
                {(project) => (
                  <li>
                    <button
                      type="button"
                      role="option"
                      aria-selected={selectedProjectKey() === project.projectKey}
                      onClick={() => selectProject(project.projectKey)}
                    >
                      <strong>{projectShortName(project)}</strong>
                      <span>{project.label}</span>
                      <small>
                        {project.sessionCount} sessions - {project.eventCount} events
                      </small>
                    </button>
                  </li>
                )}
              </For>
              <Show when={!results().length}>
                <li class="project-picker-empty">No projects match.</li>
              </Show>
            </Show>
          </ul>
        </div>
      </Show>
    </div>
  );
}
