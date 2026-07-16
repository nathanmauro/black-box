import { createMemo, createSignal, createUniqueId, For, Show } from "solid-js";
import {
  createSpec,
  enqueueTask,
  type ProjectSummary,
  type Spec,
  type TaskChange,
} from "../lib/api";
import {
  canonicalizeProjectPath,
  findProjectByIdentifier,
  primaryProjectScope,
} from "../lib/projects";
import { assembleSpecBody, evaluateGateHints, type StoryFormInput } from "../lib/storySpec";
import ProjectPicker from "./ProjectPicker";

export type StoryFormProps = {
  projects: ProjectSummary[];
  actor?: string;
  createSpec?: typeof createSpec;
  enqueueTask?: typeof enqueueTask;
  onCreated: (result: { spec: Spec; taskChange: TaskChange }) => void;
  onCancel: () => void;
};

export default function StoryForm(props: StoryFormProps) {
  const id = createUniqueId();
  const [title, setTitle] = createSignal("");
  const [selectedProjectKey, setSelectedProjectKey] = createSignal<string>();
  const [repo, setRepo] = createSignal("");
  const [goal, setGoal] = createSignal("");
  const [acceptanceCriteria, setAcceptanceCriteria] = createSignal("");
  const [constraints, setConstraints] = createSignal("");
  const [verify, setVerify] = createSignal("");
  const [priority, setPriority] = createSignal(10);
  const [mode, setMode] = createSignal<StoryFormInput["mode"]>("full_auto");
  const [submitting, setSubmitting] = createSignal(false);
  const [submitError, setSubmitError] = createSignal<string | null>(null);
  const [createdSpec, setCreatedSpec] = createSignal<Spec>();

  const currentInput = createMemo<StoryFormInput>(() => ({
    title: title(),
    repo: repo(),
    mode: mode(),
    goal: goal(),
    acceptanceCriteria: acceptanceCriteria(),
    constraints: constraints(),
    verify: verify(),
    priority: priority(),
  }));
  const gateHints = createMemo(() => evaluateGateHints(currentInput()));
  const requiredFieldsMissing = createMemo(() => (
    !title().trim() || !repo().trim() || !goal().trim() || !Number.isFinite(priority())
  ));

  function selectProject(projectKey: string | undefined) {
    setSelectedProjectKey(projectKey);
    const project = findProjectByIdentifier(props.projects, projectKey);
    if (project) setRepo(primaryProjectScope(project).canonicalKey);
  }

  async function submitStory(event: SubmitEvent) {
    event.preventDefault();
    if (submitting() || requiredFieldsMissing()) return;

    setSubmitting(true);
    setSubmitError(null);
    const input = currentInput();
    const actor = props.actor ?? "board";
    try {
      let spec = createdSpec();
      if (!spec) {
        spec = await (props.createSpec ?? createSpec)({
          projectKey: canonicalizeProjectPath(input.repo),
          title: input.title.trim(),
          body: assembleSpecBody(input),
          specRef: null,
          actor,
        });
        setCreatedSpec(spec);
      }
      const taskChange = await (props.enqueueTask ?? enqueueTask)({
        specId: spec.id,
        title: input.title.trim(),
        lane: "gate",
        priority: input.priority,
        actor,
      });
      setCreatedSpec(undefined);
      props.onCreated({ spec, taskChange });
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section class="story-form" aria-labelledby={`${id}-title`}>
      <header class="story-form-header">
        <div>
          <p class="eyebrow">story intake</p>
          <h2 id={`${id}-title`}>New story</h2>
          <p>Freeze the work contract, then queue it for the deterministic gate.</p>
        </div>
      </header>

      <form aria-busy={submitting()} onSubmit={submitStory}>
        <label class="story-form-field story-form-field--wide" for={`${id}-story-title`}>
          <span>Title</span>
          <input
            id={`${id}-story-title`}
            type="text"
            required
            value={title()}
            onInput={(event) => setTitle(event.currentTarget.value)}
            placeholder="What should the runner deliver?"
          />
        </label>

        <div class="story-form-project">
          <ProjectPicker
            projects={props.projects}
            selectedProjectKey={selectedProjectKey()}
            allDescription="Choose a catalog project to prefill the repo path"
            onSelect={selectProject}
          />
        </div>

        <label class="story-form-field" for={`${id}-repo`}>
          <span>Repo path</span>
          <input
            id={`${id}-repo`}
            type="text"
            required
            value={repo()}
            onInput={(event) => setRepo(event.currentTarget.value)}
            placeholder="/Users/name/Developer/proj/name"
          />
        </label>

        <label class="story-form-field story-form-field--wide" for={`${id}-goal`}>
          <span>Goal</span>
          <textarea
            id={`${id}-goal`}
            required
            rows={3}
            value={goal()}
            onInput={(event) => setGoal(event.currentTarget.value)}
            placeholder="Describe the outcome this story must produce."
          />
        </label>

        <label class="story-form-field" for={`${id}-acceptance`}>
          <span>Acceptance criteria</span>
          <textarea
            id={`${id}-acceptance`}
            rows={5}
            value={acceptanceCriteria()}
            onInput={(event) => setAcceptanceCriteria(event.currentTarget.value)}
            placeholder="One acceptance criterion per line"
          />
        </label>

        <label class="story-form-field" for={`${id}-constraints`}>
          <span>Constraints</span>
          <textarea
            id={`${id}-constraints`}
            rows={5}
            value={constraints()}
            onInput={(event) => setConstraints(event.currentTarget.value)}
            placeholder="One constraint or danger per line (optional)"
          />
        </label>

        <label class="story-form-field" for={`${id}-verify`}>
          <span>Verify command</span>
          <input
            id={`${id}-verify`}
            type="text"
            value={verify()}
            onInput={(event) => setVerify(event.currentTarget.value)}
            placeholder="mvn test"
          />
        </label>

        <label class="story-form-field" for={`${id}-priority`}>
          <span>Priority</span>
          <input
            id={`${id}-priority`}
            type="number"
            min="1"
            max="100"
            value={priority()}
            onInput={(event) => setPriority(event.currentTarget.valueAsNumber)}
          />
        </label>

        <fieldset class="story-form-mode story-form-field--wide">
          <legend>Mode</legend>
          <label>
            <input
              type="radio"
              name={`${id}-mode`}
              value="full_auto"
              checked={mode() === "full_auto"}
              onChange={() => setMode("full_auto")}
            />
            Full auto
          </label>
          <label>
            <input
              type="radio"
              name={`${id}-mode`}
              value="sdlc"
              checked={mode() === "sdlc"}
              onChange={() => setMode("sdlc")}
            />
            SDLC
          </label>
        </fieldset>

        <Show when={gateHints().length > 0}>
          <ul class="story-form-hints story-form-field--wide" aria-label="Gate hints">
            <For each={gateHints()}>
              {(hint) => <li data-hint={hint.id}>{hint.message}</li>}
            </For>
          </ul>
        </Show>

        <Show when={submitError()}>
          {(message) => <p class="story-form-error story-form-field--wide" role="alert">{message()}</p>}
        </Show>

        <div class="story-form-actions story-form-field--wide">
          <button type="button" class="story-form-cancel" onClick={props.onCancel}>Cancel</button>
          <button
            type="submit"
            class="story-form-submit"
            disabled={submitting() || requiredFieldsMissing()}
          >
            {submitting() ? "Creating…" : "Create story"}
          </button>
        </div>
      </form>
    </section>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
