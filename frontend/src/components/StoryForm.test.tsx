import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { describe, expect, it, vi } from "vitest";
import type {
  CreateSpecRequest,
  EnqueueTaskRequest,
  ProjectSummary,
  Spec,
  TaskChange,
  UpdateTaskStatusRequest,
} from "../lib/api";
import type { StoryFormInput } from "../lib/storySpec";
import StoryForm from "./StoryForm";

const projects: ProjectSummary[] = [
  {
    projectKey: "sba-key",
    canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
    label: "~/Developer/proj/sba-agentic",
    sessionCount: 4,
    eventCount: 120,
    savedMeldCount: 0,
    scopes: [{
      projectKey: "sba-key",
      canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
      label: "~/Developer/proj/sba-agentic",
      primary: true,
    }],
  },
  {
    projectKey: "cockpit-key",
    canonicalKey: "/Users/nathan/Developer/proj/cockpit",
    label: "~/Developer/proj/cockpit",
    sessionCount: 2,
    eventCount: 40,
    savedMeldCount: 0,
  },
];

const spec: Spec = {
  id: "spec-1",
  projectKey: "/Users/nathan/Developer/proj/sba-agentic",
  title: "Run the board story",
  body: "# Run the board story\n",
  specRef: null,
  status: "active",
  createdBy: "board",
  createdAt: "2026-07-15T16:00:00Z",
  updatedAt: "2026-07-15T16:00:00Z",
};

const taskChange: TaskChange = {
  snapshot: {
    spec,
    task: {
      id: "task-1",
      specId: spec.id,
      projectKey: spec.projectKey,
      title: spec.title,
      lane: "gate",
      status: "open",
      priority: 23,
      createdBy: "board",
      createdAt: "2026-07-15T16:00:00Z",
      updatedAt: "2026-07-15T16:00:00Z",
    },
  },
  event: {
    id: "event-1",
    taskId: "task-1",
    type: "task.created",
    actor: "board",
    fromStatus: null,
    toStatus: "open",
    observedAt: "2026-07-15T16:00:00Z",
  },
};

const prefilledInput: StoryFormInput = {
  title: "Revise the board story",
  repo: "/Users/nathan/Developer/proj/sba-agentic",
  mode: "sdlc",
  goal: "Address the deterministic gate feedback.",
  acceptanceCriteria: "The corrected gate passes.\nThe old gate is cancelled.",
  constraints: "Preserve the frozen spec.",
  verify: "npx vitest run",
  priority: 31,
};

describe("StoryForm", () => {
  it("renders the complete story intake form with SDLC enabled", () => {
    renderStoryForm();

    expect(screen.getByRole("textbox", { name: "Title" })).toBeRequired();
    expect(screen.getByRole("button", { name: /All projects/ })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Repo path" })).toBeRequired();
    expect(screen.getByRole("textbox", { name: "Goal" })).toBeRequired();
    expect(screen.getByRole("textbox", { name: "Acceptance criteria" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Constraints" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Verify command" })).toBeInTheDocument();
    expect(screen.getByRole("spinbutton", { name: "Priority" })).toHaveValue(10);
    expect(screen.getByRole("radio", { name: "Full auto" })).toBeChecked();
    const sdlc = screen.getByRole("radio", { name: "SDLC" });
    expect(sdlc).toBeEnabled();
    fireEvent.click(sdlc);
    expect(sdlc).toBeChecked();
  });

  it("updates the acceptance-criteria gate hint as the user types", () => {
    renderStoryForm();
    const message = "Acceptance criteria is empty — the gate will block the story until at least one criterion is listed.";

    expect(screen.getByText(message)).toBeInTheDocument();
    fireEvent.input(screen.getByRole("textbox", { name: "Acceptance criteria" }), {
      target: { value: "The gate passes." },
    });

    expect(screen.queryByText(message)).not.toBeInTheDocument();
  });

  it("prefills the repo from a catalog project without locking the field", async () => {
    renderStoryForm();
    const repo = screen.getByRole("textbox", { name: "Repo path" });

    fireEvent.click(screen.getByRole("button", { name: /All projects/ }));
    fireEvent.click(screen.getByRole("option", { name: /sba-agentic/ }));

    expect(repo).toHaveValue("/Users/nathan/Developer/proj/sba-agentic");
    fireEvent.input(repo, { target: { value: "/tmp/editable-checkout" } });
    expect(repo).toHaveValue("/tmp/editable-checkout");
  });

  it("prefills every owned field and presents the blocked gate feedback", () => {
    renderStoryForm({
      initialInput: prefilledInput,
      blockedReason: "Acceptance criteria must name the cancellation behavior.",
      replacesTaskId: "task-blocked-gate",
    });

    expect(screen.getByRole("textbox", { name: "Title" })).toHaveValue(prefilledInput.title);
    expect(screen.getByRole("textbox", { name: "Repo path" })).toHaveValue(prefilledInput.repo);
    expect(screen.getByRole("textbox", { name: "Goal" })).toHaveValue(prefilledInput.goal);
    expect(screen.getByRole("textbox", { name: "Acceptance criteria" })).toHaveValue(prefilledInput.acceptanceCriteria);
    expect(screen.getByRole("textbox", { name: "Constraints" })).toHaveValue(prefilledInput.constraints);
    expect(screen.getByRole("textbox", { name: "Verify command" })).toHaveValue(prefilledInput.verify);
    expect(screen.getByRole("spinbutton", { name: "Priority" })).toHaveValue(prefilledInput.priority);
    expect(screen.getByRole("radio", { name: "SDLC" })).toBeChecked();
    expect(screen.getByRole("note", { name: "Gate feedback" })).toHaveTextContent(
      "Acceptance criteria must name the cancellation behavior.",
    );
    expect(screen.getByRole("button", { name: "Create revised story" })).toBeEnabled();
  });

  it("creates the spec, enqueues the gate task, and reports both results", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    const onCreated = vi.fn();
    renderStoryForm({ create, enqueue, onCreated });

    fireEvent.input(screen.getByRole("textbox", { name: "Title" }), { target: { value: "Run the board story" } });
    fireEvent.input(screen.getByRole("textbox", { name: "Repo path" }), {
      target: { value: "/Users/nathan/Developer/proj/sba-agentic/" },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Goal" }), { target: { value: "Ship the full-auto loop." } });
    fireEvent.input(screen.getByRole("textbox", { name: "Acceptance criteria" }), {
      target: { value: "Gate accepts the story.\nRunner claims the task." },
    });
    fireEvent.input(screen.getByRole("textbox", { name: "Constraints" }), { target: { value: "Preserve local changes." } });
    fireEvent.input(screen.getByRole("textbox", { name: "Verify command" }), { target: { value: "npm test" } });
    fireEvent.input(screen.getByRole("spinbutton", { name: "Priority" }), { target: { value: "23" } });
    fireEvent.click(screen.getByRole("button", { name: "Create story" }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledWith(expect.objectContaining({
      title: "Run the board story",
      projectKey: "/Users/nathan/Developer/proj/sba-agentic",
      actor: "board",
      specRef: null,
      body: expect.stringContaining("## Goal\n\nShip the full-auto loop."),
    }));
    expect(create.mock.calls[0]?.[0].body).toContain("## Acceptance criteria\n\n- Gate accepts the story.\n- Runner claims the task.");
    await waitFor(() => expect(enqueue).toHaveBeenCalledWith({
      specId: "spec-1",
      title: "Run the board story",
      lane: "gate",
      priority: 23,
      actor: "board",
    }));
    expect(onCreated).toHaveBeenCalledWith({ spec, taskChange });
  });

  it("records the selected SDLC mode in the frozen spec", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    renderStoryForm({ create, enqueue });
    fillRequiredFields();

    fireEvent.click(screen.getByRole("radio", { name: "SDLC" }));
    fireEvent.click(screen.getByRole("button", { name: "Create story" }));

    await waitFor(() => expect(create).toHaveBeenCalledOnce());
    expect(create.mock.calls[0]?.[0].body).toContain("mode: sdlc");
  });

  it("creates the replacement spec and gate task before cancelling the blocked gate", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    const update = vi.fn(async (_taskId: string, _request: UpdateTaskStatusRequest) => taskChange);
    const onCreated = vi.fn();
    renderStoryForm({
      initialInput: prefilledInput,
      replacesTaskId: "task-blocked-gate",
      create,
      enqueue,
      update,
      onCreated,
    });

    fireEvent.click(screen.getByRole("button", { name: "Create revised story" }));

    await waitFor(() => expect(update).toHaveBeenCalledWith("task-blocked-gate", {
      actor: "board",
      status: "cancelled",
    }));
    expect(create).toHaveBeenCalledOnce();
    expect(enqueue).toHaveBeenCalledOnce();
    expect(create.mock.invocationCallOrder[0]).toBeLessThan(enqueue.mock.invocationCallOrder[0]!);
    expect(enqueue.mock.invocationCallOrder[0]).toBeLessThan(update.mock.invocationCallOrder[0]!);
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ spec, taskChange }));
  });

  it("keeps the new story when old-task cancellation fails and retries only the cleanup", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    const update = vi.fn()
      .mockRejectedValueOnce(new Error("Cancellation unavailable"))
      .mockResolvedValueOnce(taskChange);
    const onCreated = vi.fn();
    const onCleanupFailed = vi.fn();
    renderStoryForm({
      initialInput: prefilledInput,
      replacesTaskId: "task-blocked-gate",
      create,
      enqueue,
      update,
      onCreated,
      onCleanupFailed,
    });

    fireEvent.click(screen.getByRole("button", { name: "Create revised story" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "New story created and kept, but the old gate task could not be cancelled: Cancellation unavailable",
    );
    expect(create).toHaveBeenCalledOnce();
    expect(enqueue).toHaveBeenCalledOnce();
    expect(update).toHaveBeenCalledOnce();
    expect(onCreated).not.toHaveBeenCalled();
    expect(onCleanupFailed).toHaveBeenCalledWith({ spec, taskChange }, "Cancellation unavailable");

    fireEvent.click(screen.getByRole("button", { name: "Retry cancelling old task" }));

    await waitFor(() => expect(update).toHaveBeenCalledTimes(2));
    expect(create).toHaveBeenCalledOnce();
    expect(enqueue).toHaveBeenCalledOnce();
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ spec, taskChange }));
  });

  it("shows a create-spec failure without enqueueing or reporting creation", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest): Promise<Spec> => {
      throw new Error("Spec creation failed");
    });
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    const onCreated = vi.fn();
    renderStoryForm({ create, enqueue, onCreated });
    fillRequiredFields();

    fireEvent.click(screen.getByRole("button", { name: "Create story" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Spec creation failed");
    expect(enqueue).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Create story" })).toBeEnabled();
  });

  it("retries enqueueing an already-created spec without creating a duplicate", async () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn()
      .mockRejectedValueOnce(new Error("Task enqueue failed"))
      .mockResolvedValueOnce(taskChange);
    const onCreated = vi.fn();
    renderStoryForm({ create, enqueue, onCreated });
    fillRequiredFields();

    fireEvent.click(screen.getByRole("button", { name: "Create story" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Task enqueue failed");
    expect(create).toHaveBeenCalledOnce();
    expect(enqueue).toHaveBeenCalledOnce();
    expect(onCreated).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Create story" }));

    await waitFor(() => expect(enqueue).toHaveBeenCalledTimes(2));
    expect(create).toHaveBeenCalledOnce();
    expect(onCreated).toHaveBeenCalledWith({ spec, taskChange });
  });

  it("blocks submission when priority is empty", () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    renderStoryForm({ create });
    fillRequiredFields();

    fireEvent.input(screen.getByRole("spinbutton", { name: "Priority" }), { target: { value: "" } });

    const submit = screen.getByRole("button", { name: "Create story" });
    expect(submit).toBeDisabled();
    fireEvent.click(submit);
    expect(create).not.toHaveBeenCalled();
  });

  it("cancels without creating or enqueueing", () => {
    const create = vi.fn(async (_request: CreateSpecRequest) => spec);
    const enqueue = vi.fn(async (_request: EnqueueTaskRequest) => taskChange);
    const onCancel = vi.fn();
    renderStoryForm({ create, enqueue, onCancel });

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onCancel).toHaveBeenCalledOnce();
    expect(create).not.toHaveBeenCalled();
    expect(enqueue).not.toHaveBeenCalled();
  });
});

function renderStoryForm(options: {
  create?: (request: CreateSpecRequest) => Promise<Spec>;
  enqueue?: (request: EnqueueTaskRequest) => Promise<TaskChange>;
  update?: (taskId: string, request: UpdateTaskStatusRequest) => Promise<TaskChange>;
  initialInput?: StoryFormInput;
  blockedReason?: string;
  replacesTaskId?: string;
  onCreated?: (result: { spec: Spec; taskChange: TaskChange }) => void;
  onCleanupFailed?: (result: { spec: Spec; taskChange: TaskChange }, message: string) => void;
  onCancel?: () => void;
} = {}) {
  return render(() => (
    <StoryForm
      projects={projects}
      initialInput={options.initialInput}
      blockedReason={options.blockedReason}
      replacesTaskId={options.replacesTaskId}
      createSpec={options.create}
      enqueueTask={options.enqueue}
      updateTaskStatus={options.update}
      onCreated={options.onCreated ?? vi.fn()}
      onCleanupFailed={options.onCleanupFailed}
      onCancel={options.onCancel ?? vi.fn()}
    />
  ));
}

function fillRequiredFields() {
  fireEvent.input(screen.getByRole("textbox", { name: "Title" }), { target: { value: "Run the board story" } });
  fireEvent.input(screen.getByRole("textbox", { name: "Repo path" }), {
    target: { value: "/Users/nathan/Developer/proj/sba-agentic" },
  });
  fireEvent.input(screen.getByRole("textbox", { name: "Goal" }), { target: { value: "Ship the full-auto loop." } });
}
