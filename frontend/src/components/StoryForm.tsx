import { createMemo, createSignal, createUniqueId, For, Show } from "solid-js";
import {
  createSpec,
  enqueueTask,
  updateTaskStatus,
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
  initialInput?: StoryFormInput;
  blockedReason?: string;
  replacesTaskId?: string;
  createSpec?: typeof createSpec;
  enqueueTask?: typeof enqueueTask;
  updateTaskStatus?: typeof updateTaskStatus;
  onCreated: (result: StoryCreationResult) => void;
  onCleanupFailed?: (result: StoryCreationResult, message: string) => void;
  onCancel: () => void;
};

export type StoryCreationResult = { spec: Spec; taskChange: TaskChange };

export default function StoryForm(props: StoryFormProps) {
  const id = createUniqueId();
  const initial = props.initialInput;
  const initialProject = findProjectByIdentifier(props.projects, initial?.repo);
  const [title, setTitle] = createSignal(initial?.title ?? "");
  const [selectedProjectKey, setSelectedProjectKey] = createSignal<string | undefined>(initialProject?.projectKey);
  const [repo, setRepo] = createSignal(initial?.repo ?? "");
  const [goal, setGoal] = createSignal(initial?.goal ?? "");
  const [acceptanceCriteria, setAcceptanceCriteria] = createSignal(initial?.acceptanceCriteria ?? "");
  const [constraints, setConstraints] = createSignal(initial?.constraints ?? "");
  const [verify, setVerify] = createSignal(initial?.verify ?? "");
  const [priority, setPriority] = createSignal(initial?.priority ?? 10);
  const [mode, setMode] = createSignal<StoryFormInput["mode"]>(initial?.mode ?? "full_auto");
  const [submitting, setSubmitting] = createSignal(false);
  const [submitError, setSubmitError] = createSignal<string | null>(null);
  const [createdSpec, setCreatedSpec] = createSignal<Spec>();
  const [createdResult, setCreatedResult] = createSignal<StoryCreationResult>();

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
  const submitLabel = createMemo(() => {
    if (submitting()) return createdResult() ? "Cancelling old task…" : "Creating…";
    if (createdResult()) return "Retry cancelling old task";
    return props.replacesTaskId ? "Create revised story" : "Create story";
  });

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
      const existingResult = createdResult();
      if (existingResult) {
        if (!props.replacesTaskId || await cancelReplacedTask(existingResult, actor)) {
          setCreatedResult(undefined);
          props.onCreated(existingResult);
        }
        return;
      }
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
      const result = { spec, taskChange };
      setCreatedResult(result);
      if (!props.replacesTaskId || await cancelReplacedTask(result, actor)) {
        setCreatedResult(undefined);
        props.onCreated(result);
      }
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function cancelReplacedTask(result: StoryCreationResult, actor: string): Promise<boolean> {
    if (!props.replacesTaskId) return true;
    try {
      await (props.updateTaskStatus ?? updateTaskStatus)(props.replacesTaskId, {
        actor,
        status: "cancelled",
      });
      return true;
    } catch (error) {
      const message = errorMessage(error);
      setSubmitError(`New story created and kept, but the old gate task could not be cancelled: ${message}`);
      props.onCleanupFailed?.(result, message);
      return false;
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

      <Show when={props.blockedReason}>
        {(reason) => (
          <section class="story-form-gate-feedback" role="note" aria-label="Gate feedback">
            <span>Gate feedback</span>
            <strong>Fix this before resubmitting</strong>
            <p>{reason()}</p>
          </section>
        )}
      </Show>

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
            {submitLabel()}
          </button>
        </div>
      </form>
    </section>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
