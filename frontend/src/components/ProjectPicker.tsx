import { createEffect, createMemo, createSignal, createUniqueId, For, Show } from "solid-js";
import type { ProjectSummary } from "../lib/api";
import { projectShortName, rankProjects } from "../lib/projects";

type ProjectPickerProps = {
  projects: ProjectSummary[];
  selectedProjectKey?: string;
  loading?: boolean;
  error?: string | null;
  allDescription?: string;
  onSelect: (projectKey: string | undefined) => void;
};

export default function ProjectPicker(props: ProjectPickerProps) {
  const [open, setOpen] = createSignal(false);
  const [query, setQuery] = createSignal("");
  const [activeIndex, setActiveIndex] = createSignal(0);
  const [selectedProjectKey, setSelectedProjectKey] = createSignal<string | undefined>(props.selectedProjectKey);
  const selectedProject = createMemo(() => props.projects.find((project) => project.projectKey === selectedProjectKey()));
  const results = createMemo(() => rankProjects(props.projects, query()).slice(0, 12));
  const listboxId = `project-picker-results-${createUniqueId()}`;
  let triggerButton: HTMLButtonElement | undefined;
  let searchInput: HTMLInputElement | undefined;

  createEffect(() => {
    setSelectedProjectKey(props.selectedProjectKey);
  });
  createEffect(() => {
    query();
    setActiveIndex(0);
  });

  function selectProject(projectKey: string | undefined) {
    setSelectedProjectKey(projectKey);
    props.onSelect(projectKey);
    setQuery("");
    setOpen(false);
    queueMicrotask(() => triggerButton?.focus());
  }

  function toggleOpen() {
    const next = !open();
    setOpen(next);
    if (next) queueMicrotask(() => searchInput?.focus());
  }

  function closePicker() {
    setOpen(false);
    setQuery("");
    queueMicrotask(() => triggerButton?.focus());
  }

  function handleSearchKeyDown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      closePicker();
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const direction = event.key === "ArrowDown" ? 1 : -1;
      const lastIndex = Math.max(0, results().length - 1);
      setActiveIndex((current) => Math.min(lastIndex, Math.max(0, current + direction)));
      return;
    }
    if (event.key === "Enter") {
      const project = results()[activeIndex()];
      if (!project) return;
      event.preventDefault();
      selectProject(project.projectKey);
    }
  }

  return (
    <div class="project-picker">
      <button
        ref={triggerButton}
        type="button"
        class="project-picker-button"
        aria-haspopup="listbox"
        aria-expanded={open()}
        aria-controls={listboxId}
        onClick={toggleOpen}
      >
        <span class="project-picker-label">Project</span>
        <strong>{selectedProject() ? projectShortName(selectedProject()!) : "All projects"}</strong>
        <small>{selectedProject()?.label || props.allDescription || "Global activity"}</small>
      </button>

      <Show when={open()}>
        <div class="project-picker-popover">
          <button type="button" class="project-picker-all" onClick={() => selectProject(undefined)}>
            All projects
          </button>
          <input
            ref={searchInput}
            aria-label="Search projects"
            role="combobox"
            aria-autocomplete="list"
            aria-expanded="true"
            aria-controls={listboxId}
            aria-activedescendant={results()[activeIndex()] ? `${listboxId}-${activeIndex()}` : undefined}
            value={query()}
            onInput={(event) => setQuery(event.currentTarget.value)}
            onKeyDown={handleSearchKeyDown}
            placeholder="Search projects..."
            autocomplete="off"
          />
          <ul id={listboxId} class="project-picker-results" role="listbox" aria-label="Project results">
            <Show
              when={!props.loading}
              fallback={<li class="project-picker-empty">Loading projects...</li>}
            >
              <Show when={props.error}>
                {(message) => <li class="project-picker-empty" role="alert">{message()}</li>}
              </Show>
              <For each={results()}>
                {(project, index) => (
                  <li>
                    <button
                      id={`${listboxId}-${index()}`}
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
              <Show when={!results().length && !props.error}>
                <li class="project-picker-empty">No projects match.</li>
              </Show>
            </Show>
          </ul>
        </div>
      </Show>
    </div>
  );
}
