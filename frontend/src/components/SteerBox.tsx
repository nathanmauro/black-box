import { createSignal, Show } from "solid-js";
import { createTaskAnnotation, type TaskAnnotation } from "../lib/api";

export type SteerBoxProps = {
  taskId: string;
  actor: string;
  enabled: boolean;
  disabledHint?: string;
  createAnnotation?: typeof createTaskAnnotation;
  onSteered?: (annotation: TaskAnnotation) => void;
};

export default function SteerBox(props: SteerBoxProps) {
  const [text, setText] = createSignal("");
  const [submitting, setSubmitting] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    const trimmedText = text().trim();
    if (!trimmedText || submitting()) return;

    setSubmitting(true);
    setError(null);
    try {
      const annotation = await (props.createAnnotation ?? createTaskAnnotation)(props.taskId, {
        actor: props.actor,
        kind: "steer",
        text: trimmedText,
      });
      setText("");
      props.onSteered?.(annotation);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div class="steer-box">
      <Show
        when={props.enabled}
        fallback={(
          <p class="steer-box-disabled">
            {props.disabledHint ?? "Steering is only available while the task is in progress."}
          </p>
        )}
      >
        <form onSubmit={submit}>
          <label for={`steer-${props.taskId}`}>Steer this run</label>
          <textarea
            id={`steer-${props.taskId}`}
            value={text()}
            onInput={(event) => setText(event.currentTarget.value)}
            placeholder="Add context or redirect the active worker…"
          />
          <button type="submit" aria-busy={submitting()} disabled={submitting()}>
            {submitting() ? "Sending…" : "Send steer"}
          </button>
        </form>
        <Show when={error()}>{(message) => <p class="steer-box-error" role="alert">{message()}</p>}</Show>
      </Show>
    </div>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
