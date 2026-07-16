import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type { TaskAnnotation } from "../lib/api";
import SteerBox from "./SteerBox";

const annotation: TaskAnnotation = {
  id: "note-1",
  taskId: "task-1",
  kind: "steer",
  actor: "board",
  text: "Focus the verification path.",
  observedAt: "2026-07-15T12:00:00Z",
};

describe("SteerBox", () => {
  it("renders an interactive textarea and submit button when enabled", () => {
    render(() => <SteerBox taskId="task-1" actor="board" enabled />);

    expect(screen.getByRole("textbox", { name: "Steer this run" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send steer" })).toBeInTheDocument();
  });

  it("renders the disabled hint without a textarea when steering is unavailable", () => {
    render(() => (
      <SteerBox taskId="task-1" actor="board" enabled={false} disabledHint="Wait for an active worker." />
    ));

    expect(screen.getByText("Wait for an active worker.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Steer this run" })).not.toBeInTheDocument();
  });

  it("trims and submits a steer, then clears the textarea and reports success", async () => {
    const createAnnotation = vi.fn(async () => annotation);
    const onSteered = vi.fn();
    render(() => (
      <SteerBox
        taskId="task-1"
        actor="board"
        enabled
        createAnnotation={createAnnotation}
        onSteered={onSteered}
      />
    ));

    const textarea = screen.getByRole("textbox", { name: "Steer this run" });
    fireEvent.input(textarea, { target: { value: "  Focus the verification path.  " } });
    fireEvent.click(screen.getByRole("button", { name: "Send steer" }));

    await waitFor(() => expect(createAnnotation).toHaveBeenCalledWith("task-1", {
      actor: "board",
      kind: "steer",
      text: "Focus the verification path.",
    }));
    await waitFor(() => expect(textarea).toHaveValue(""));
    expect(onSteered).toHaveBeenCalledWith(annotation);
  });

  it("does not submit empty or whitespace-only text", () => {
    const createAnnotation = vi.fn(async () => annotation);
    render(() => <SteerBox taskId="task-1" actor="board" enabled createAnnotation={createAnnotation} />);

    const textarea = screen.getByRole("textbox", { name: "Steer this run" });
    fireEvent.input(textarea, { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "Send steer" }));

    expect(createAnnotation).not.toHaveBeenCalled();
  });

  it("shows a request error and preserves the typed text for retry", async () => {
    const createAnnotation = vi.fn(async () => {
      throw new Error("worker is unavailable");
    });
    render(() => <SteerBox taskId="task-1" actor="board" enabled createAnnotation={createAnnotation} />);

    const textarea = screen.getByRole("textbox", { name: "Steer this run" });
    fireEvent.input(textarea, { target: { value: "Try the smaller fixture." } });
    fireEvent.click(screen.getByRole("button", { name: "Send steer" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("worker is unavailable");
    expect(textarea).toHaveValue("Try the smaller fixture.");
  });
});
