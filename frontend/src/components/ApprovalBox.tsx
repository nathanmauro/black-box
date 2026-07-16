import { createEffect, createMemo, createSignal, on, Show } from "solid-js";
import { createTaskAnnotation, type TaskAnnotation } from "../lib/api";

export type ApprovalStage = "plan" | "review";
export type ApprovalDecision = "approve" | "reject";

export type ApprovalBoxProps = {
  taskId: string;
  actor: string;
  stage: ApprovalStage;
  decision?: TaskAnnotation;
  createAnnotation?: typeof createTaskAnnotation;
  onDecided?: (annotation: TaskAnnotation) => void;
};

export default function ApprovalBox(props: ApprovalBoxProps) {
  const [feedback, setFeedback] = createSignal("");
  const [submittedDecision, setSubmittedDecision] = createSignal<TaskAnnotation>();
  const [submitting, setSubmitting] = createSignal<ApprovalDecision>();
  const [error, setError] = createSignal<string | null>(null);
  const decision = createMemo(() => {
    const current = props.decision ?? submittedDecision();
    return approvalDecision(current, props.stage) ? current : undefined;
  });

  createEffect(on(
    () => props.taskId,
    () => {
      setFeedback("");
      setSubmittedDecision(undefined);
      setSubmitting(undefined);
      setError(null);
    },
  ));

  async function submit(nextDecision: ApprovalDecision) {
    if (submitting()) return;
    const trimmedFeedback = nextDecision === "reject" ? feedback().trim() : "";
    if (nextDecision === "reject" && !trimmedFeedback) return;

    setSubmitting(nextDecision);
    setError(null);
    try {
      const annotation = await (props.createAnnotation ?? createTaskAnnotation)(props.taskId, {
        actor: props.actor,
        kind: "approval",
        text: nextDecision === "approve"
          ? `${stageLabel(props.stage)} approved.`
          : `${stageLabel(props.stage)} rejected: ${trimmedFeedback}`,
        dataJson: {
          decision: nextDecision,
          stage: props.stage,
          feedback: trimmedFeedback,
        },
      });
      if (annotation.taskId !== props.taskId
        || approvalDecision(annotation, props.stage) !== nextDecision
        || approvalFeedback(annotation) !== trimmedFeedback) {
        throw new Error("Approval response did not contain the submitted decision.");
      }
      setSubmittedDecision(annotation);
      props.onDecided?.(annotation);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setSubmitting(undefined);
    }
  }

  return (
    <section class="approval-box" aria-label={`${stageLabel(props.stage)} approval`}>
      <Show
        when={decision()}
        fallback={(
          <form onSubmit={(event) => {
            event.preventDefault();
            void submit("reject");
          }}>
            <div class="approval-box-heading">
              <span>Human gate</span>
              <strong>Approve or reject the {props.stage}</strong>
            </div>
            <label for={`approval-feedback-${props.taskId}`}>Rejection feedback</label>
            <textarea
              id={`approval-feedback-${props.taskId}`}
              required
              value={feedback()}
              onInput={(event) => setFeedback(event.currentTarget.value)}
              placeholder={`Required when rejecting this ${props.stage}…`}
            />
            <div class="approval-box-actions">
              <button
                type="button"
                class="approval-box-approve"
                aria-busy={submitting() === "approve"}
                disabled={submitting() !== undefined}
                onClick={() => void submit("approve")}
              >
                {submitting() === "approve" ? "Approving…" : "Approve"}
              </button>
              <button
                type="submit"
                class="approval-box-reject"
                aria-busy={submitting() === "reject"}
                disabled={submitting() !== undefined || !feedback().trim()}
              >
                {submitting() === "reject" ? "Rejecting…" : "Reject"}
              </button>
            </div>
            <Show when={error()}>{(message) => <p class="approval-box-error" role="alert">{message()}</p>}</Show>
          </form>
        )}
      >
        {(annotation) => {
          const currentDecision = () => approvalDecision(annotation(), props.stage)!;
          const currentFeedback = () => approvalFeedback(annotation());
          return (
            <div class={`approval-record approval-record--${currentDecision()}`} role="status">
              <span>{currentDecision() === "approve" ? "Approved" : "Rejected"}</span>
              <p>
                {currentDecision() === "approve" ? "Approved" : "Rejected"} by {annotation().actor} at {formatInstant(annotation().observedAt)}
              </p>
              <Show when={currentDecision() === "reject" && currentFeedback()}>
                <pre>{currentFeedback()}</pre>
              </Show>
            </div>
          );
        }}
      </Show>
    </section>
  );
}

export function approvalDecision(
  annotation: Pick<TaskAnnotation, "kind" | "dataJson"> | null | undefined,
  stage: ApprovalStage,
): ApprovalDecision | undefined {
  if (annotation?.kind !== "approval" || !isRecord(annotation.dataJson)) return undefined;
  if (annotation.dataJson.stage !== stage) return undefined;
  if (typeof annotation.dataJson.feedback !== "string") return undefined;
  if (annotation.dataJson.decision === "approve") return "approve";
  if (annotation.dataJson.decision === "reject" && annotation.dataJson.feedback.trim()) return "reject";
  return undefined;
}

function approvalFeedback(annotation: Pick<TaskAnnotation, "dataJson">): string {
  return isRecord(annotation.dataJson) && typeof annotation.dataJson.feedback === "string"
    ? annotation.dataJson.feedback
    : "";
}

function stageLabel(stage: ApprovalStage): string {
  return stage.charAt(0).toUpperCase() + stage.slice(1);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
