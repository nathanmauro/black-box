import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { TaskAnnotation } from "../lib/api";
import ApprovalBox, { approvalDecision } from "./ApprovalBox";

function approval(overrides: Partial<TaskAnnotation> = {}): TaskAnnotation {
  return {
    id: "approval-1",
    taskId: "task-plan",
    kind: "approval",
    actor: "nathan",
    text: "Plan approved.",
    dataJson: { decision: "approve", stage: "plan", feedback: "" },
    observedAt: "2026-07-16T14:00:00Z",
    ...overrides,
  };
}

describe("ApprovalBox", () => {
  it("posts an approval and immediately collapses to its decision record", async () => {
    const result = approval();
    const createAnnotation = vi.fn(async () => result);
    const onDecided = vi.fn();
    render(() => (
      <ApprovalBox
        taskId="task-plan"
        actor="nathan"
        stage="plan"
        createAnnotation={createAnnotation}
        onDecided={onDecided}
      />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => expect(createAnnotation).toHaveBeenCalledWith("task-plan", {
      actor: "nathan",
      kind: "approval",
      text: "Plan approved.",
      dataJson: { decision: "approve", stage: "plan", feedback: "" },
    }));
    expect(await screen.findByText(/Approved by nathan at/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Rejection feedback" })).not.toBeInTheDocument();
    expect(onDecided).toHaveBeenCalledWith(result);
  });

  it("requires rejection feedback, posts the trimmed value, and keeps it in the record", async () => {
    const result = approval({
      id: "approval-2",
      taskId: "task-review",
      text: "Review rejected: Cover the retry path.",
      dataJson: { decision: "reject", stage: "review", feedback: "Cover the retry path." },
    });
    const createAnnotation = vi.fn(async () => result);
    render(() => (
      <ApprovalBox taskId="task-review" actor="nathan" stage="review" createAnnotation={createAnnotation} />
    ));

    const feedback = screen.getByRole("textbox", { name: "Rejection feedback" });
    const reject = screen.getByRole("button", { name: "Reject" });
    expect(feedback).toBeRequired();
    expect(reject).toBeDisabled();

    fireEvent.input(feedback, { target: { value: "  Cover the retry path.  " } });
    fireEvent.click(reject);

    await waitFor(() => expect(createAnnotation).toHaveBeenCalledWith("task-review", {
      actor: "nathan",
      kind: "approval",
      text: "Review rejected: Cover the retry path.",
      dataJson: { decision: "reject", stage: "review", feedback: "Cover the retry path." },
    }));
    expect(await screen.findByText(/Rejected by nathan at/)).toBeInTheDocument();
    expect(screen.getByText("Cover the retry path.")).toBeInTheDocument();
  });

  it("does not submit whitespace-only rejection feedback", () => {
    const createAnnotation = vi.fn(async () => approval());
    render(() => (
      <ApprovalBox taskId="task-plan" actor="nathan" stage="plan" createAnnotation={createAnnotation} />
    ));

    const feedback = screen.getByRole("textbox", { name: "Rejection feedback" });
    fireEvent.input(feedback, { target: { value: "   " } });
    fireEvent.submit(feedback.closest("form")!);

    expect(createAnnotation).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();
  });

  it("preserves feedback when a rejection request fails", async () => {
    const createAnnotation = vi.fn(async (): Promise<TaskAnnotation> => {
      throw new Error("approval endpoint unavailable");
    });
    render(() => (
      <ApprovalBox taskId="task-review" actor="nathan" stage="review" createAnnotation={createAnnotation} />
    ));

    const feedback = screen.getByRole("textbox", { name: "Rejection feedback" });
    fireEvent.input(feedback, { target: { value: "Run the failure-path test." } });
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("approval endpoint unavailable");
    expect(feedback).toHaveValue("Run the failure-path test.");
  });

  it("fails closed for rejection records without feedback", () => {
    const malformed = approval({
      dataJson: { decision: "reject", stage: "plan", feedback: "   " },
    });

    expect(approvalDecision(malformed, "plan")).toBeUndefined();
    render(() => (
      <ApprovalBox taskId="task-plan" actor="nathan" stage="plan" decision={malformed} />
    ));
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("does not collapse for a response belonging to another task", async () => {
    const createAnnotation = vi.fn(async () => approval({ taskId: "task-other" }));
    render(() => (
      <ApprovalBox taskId="task-plan" actor="nathan" stage="plan" createAnnotation={createAnnotation} />
    ));

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Approval response did not contain the submitted decision.");
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.queryByText(/Approved by nathan at/)).not.toBeInTheDocument();
  });
});
