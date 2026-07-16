import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal, type JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  AgentTask,
  DagResponse,
  ProjectSummary,
  TaskAnnotation,
  TaskChange,
  TaskEvent,
  TaskFilters,
  TaskSnapshot,
} from "../../lib/api";
import type { TaskLifecycleFrame, TaskLiveStoreWithNotes } from "../../lib/tasks";
import BoardPage from "../BoardPage";

// Vitest exposes Node for this shipped-CSS contract while the frontend intentionally does not.
// @ts-expect-error Node built-ins are available in Vitest.
const { readFileSync } = await import("node:fs");
const themeCss = readFileSync("src/theme.css", "utf8") as string;

type BoardSearchParams = { project?: string; lane?: string; task?: string };

let params: BoardSearchParams;
let setParams: SetStoreFunction<BoardSearchParams>;
const searchParamWrites = vi.fn();
const emptyCatalog = async (): Promise<ProjectSummary[]> => [];

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element; class?: string }) => (
    <a href={props.href} class={props.class}>{props.children}</a>
  ),
  useNavigate: () => vi.fn(),
  useSearchParams: () => [params, (next: BoardSearchParams) => {
    searchParamWrites(next);
    setParams(next);
  }],
}));

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getTaskEvents: vi.fn(async () => []),
  };
});

beforeEach(() => {
  [params, setParams] = createStore<BoardSearchParams>({});
  searchParamWrites.mockReset();
});

describe("BoardPage", () => {
  it("places every active task in one operational column and exposes complete frozen context", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-blocked" });
    const store = fakeTaskStore(fixtures());
    const recallHandoff = vi.fn(async (scope: string) => ({
      scope,
      withinHours: 8760,
      kinds: ["handoff"],
      count: 1,
      items: [{
        eventId: "handoff-44",
        kind: "handoff",
        source: "codex",
        clientSessionId: "session-44",
        repo: "/repos/black-box",
        observedAt: "2026-07-10T00:30:00Z",
        headline: "REST and MCP adapters shipped with parity.",
        openLoops: ["Run the Board verification"],
        nextAction: "Inspect the coordination surface.",
      }],
    }));

    render(() => <BoardPage store={store} updateStatus={vi.fn()} recallHandoff={recallHandoff} loadProjects={emptyCatalog} />);

    await screen.findByRole("heading", { name: "Coordination board" });
    const open = screen.getByRole("region", { name: "Open tasks" });
    const active = screen.getByRole("region", { name: "In Progress tasks" });
    const blocked = screen.getByRole("region", { name: "Blocked tasks" });
    const done = screen.getByRole("region", { name: "Done tasks" });

    expect(within(open).getByText("Shape the contract")).toBeInTheDocument();
    expect(within(active).getByText("Build the client")).toBeInTheDocument();
    expect(within(blocked).getByText("Verify the queue")).toBeInTheDocument();
    expect(within(done).getByText("Ship the adapter")).toBeInTheDocument();
    expect(screen.queryByText("Retired experiment")).not.toBeInTheDocument();

    const detail = screen.getByRole("complementary", { name: "Task detail" });
    expect(within(detail).getByText("Frozen acceptance contract for blocked work.")).toBeInTheDocument();
    expect(within(detail).getByText("waiting for deterministic fixture data")).toBeInTheDocument();
    expect(within(detail).getByText("worker-2")).toBeInTheDocument();
    expect(within(detail).getByText(/specs\/queue\.md/)).toBeInTheDocument();
    expect(within(detail).getByRole("button", { name: "Reset task to open" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /execute|launch|run agent/i })).not.toBeInTheDocument();

    fireEvent.click(within(done).getByRole("button", { name: /Ship the adapter/ }));
    expect(within(detail).getByText("Linked Handoff")).toBeInTheDocument();
    expect(await within(detail).findByText("REST and MCP adapters shipped with parity.")).toBeInTheDocument();
    expect(within(detail).getByText("Inspect the coordination surface.")).toBeInTheDocument();
    expect(recallHandoff).toHaveBeenCalledWith("handoff-44", 8760, ["handoff"]);

    fireEvent.click(screen.getByRole("button", { name: "Show 1 cancelled task" }));
    expect(screen.getByRole("region", { name: "Cancelled tasks" })).toHaveTextContent("Retired experiment");
  });

  it("moves a task between columns when the live store applies a lifecycle frame", async () => {
    const store = fakeTaskStore([fixtures()[0]!]);
    render(() => <BoardPage store={store} updateStatus={vi.fn()} loadProjects={emptyCatalog} />);
    await screen.findByText("Shape the contract");

    store.applyFrame({
      task: { ...fixtures()[0]!.task, status: "blocked", claimedBy: "worker-9", blockedReason: "review", updatedAt: "2026-07-10T01:00:00Z" },
      transitionId: "transition-9",
      transitionType: "task.blocked",
      observedAt: "2026-07-10T01:00:00Z",
    });

    await waitFor(() => expect(within(screen.getByRole("region", { name: "Blocked tasks" })).getByText("Shape the contract")).toBeInTheDocument());
    expect(within(screen.getByRole("region", { name: "Open tasks" })).queryByText("Shape the contract")).not.toBeInTheDocument();
  });

  it("moves keyboard focus into task detail and restores it to the originating card", async () => {
    const store = fakeTaskStore(fixtures());
    render(() => <BoardPage store={store} updateStatus={vi.fn()} loadProjects={emptyCatalog} />);
    const taskCard = await screen.findByRole("button", { name: "Shape the contract, Open" });
    taskCard.focus();

    fireEvent.click(taskCard);

    const close = await screen.findByRole("button", { name: "Close task detail" });
    await waitFor(() => expect(close).toHaveFocus());
    fireEvent.click(close);
    await waitFor(() => expect(taskCard).toHaveFocus());
  });

  it("writes shareable project, lane, and task filters without mutating task state", async () => {
    const updateStatus = vi.fn();
    const store = fakeTaskStore(fixtures());
    render(() => <BoardPage store={store} updateStatus={updateStatus} loadProjects={emptyCatalog} />);
    await screen.findByText("Shape the contract");

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "other-project" } });
    fireEvent.click(await screen.findByRole("option", { name: /other-project/ }));
    fireEvent.change(screen.getByLabelText("Lane"), { target: { value: "review" } });
    fireEvent.click(screen.getByRole("button", { name: /Shape the contract/ }));

    expect(searchParamWrites).toHaveBeenCalledWith({ project: "other-project" });
    expect(searchParamWrites).toHaveBeenCalledWith({ lane: "review" });
    expect(searchParamWrites).toHaveBeenCalledWith({ task: "task-open" });
    expect(updateStatus).not.toHaveBeenCalled();
    expect(store.setFilters).toHaveBeenCalled();
  });

  it("restores project, lane, and selected task from a shared Board URL", async () => {
    [params, setParams] = createStore<BoardSearchParams>({
      project: "black-box",
      lane: "review",
      task: "task-blocked",
    });
    const store = fakeTaskStore(fixtures());
    const updateStatus = vi.fn();

    render(() => <BoardPage store={store} updateStatus={updateStatus} loadProjects={emptyCatalog} />);

    expect(await screen.findByRole("button", { name: /black-box/ })).toBeInTheDocument();
    expect(screen.getByLabelText("Lane")).toHaveValue("review");
    expect(screen.getByRole("complementary", { name: "Task detail" })).toHaveTextContent("Verify the queue");
    expect(store.setFilters).toHaveBeenCalledWith({ projectKey: "black-box", lane: "review", limit: 250 });
    expect(updateStatus).not.toHaveBeenCalled();
  });

  it("searches catalog projects by name and path, then filters tasks by canonical scope", async () => {
    const canonicalScope = "/workspace/black-box";
    const store = fakeTaskStore([
      snapshot({ id: "task-catalog", projectKey: canonicalScope, title: "Catalog-backed task", priority: 9 }),
    ]);
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));
    await screen.findByText("Catalog-backed task");

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    const search = screen.getByLabelText("Search projects");
    fireEvent.input(search, { target: { value: "black-box" } });
    let option = await screen.findByRole("option", { name: /black-box/ });
    expect(within(option).getByText("black-box")).toBeInTheDocument();
    expect(within(option).getAllByText(canonicalScope).length).toBeGreaterThan(0);

    fireEvent.input(search, { target: { value: canonicalScope } });
    option = await screen.findByRole("option", { name: /black-box/ });
    fireEvent.click(option);

    await waitFor(() => expect(searchParamWrites).toHaveBeenCalledWith({ project: canonicalScope, task: undefined }));
    expect(store.setFilters).toHaveBeenCalledWith({ projectKey: canonicalScope, limit: 250 });
    expect(screen.getByRole("button", { name: /black-box/ })).toHaveTextContent(canonicalScope);

    const card = screen.getByRole("button", { name: "Catalog-backed task, Open" });
    expect(within(card).getByText("black-box")).toBeInTheDocument();
    expect(within(card).getByText(canonicalScope)).toBeInTheDocument();
    fireEvent.click(card);

    const detail = await screen.findByRole("complementary", { name: "Task detail" });
    expect(within(detail).getByText("black-box")).toBeInTheDocument();
    expect(within(detail).getByText(canonicalScope)).toBeInTheDocument();
    expect(within(detail).getByRole("link", { name: "Open project" })).toHaveAttribute("href", "/projects/catalog-black-box");
  });

  it("maps grouped variant scopes for display without broadening the exact task filter", async () => {
    const variantScope = "/workspace/black-box-worktrees/feature";
    [params, setParams] = createStore<BoardSearchParams>({ project: variantScope, task: "task-variant" });
    const store = fakeTaskStore([
      snapshot({ id: "task-variant", projectKey: variantScope, title: "Variant-scoped task" }),
    ]);

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));

    const card = await screen.findByRole("button", { name: "Variant-scoped task, Open" });
    expect(within(card).getByText("black-box")).toBeInTheDocument();
    expect(within(card).getByText("/workspace/black-box")).toBeInTheDocument();
    expect(store.setFilters).toHaveBeenCalledWith({ projectKey: variantScope, limit: 250 });

    const detail = screen.getByRole("complementary", { name: "Task detail" });
    expect(within(detail).getByText(`Exact queue scope · ${variantScope}`)).toBeInTheDocument();
    expect(within(detail).getByRole("link", { name: "Open project" })).toHaveAttribute("href", "/projects/catalog-black-box");
    expect(within(screen.getByRole("region", { name: "Board filters" })).getByText(`Exact queue scope · ${variantScope}`)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /black-box/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: variantScope } });
    const option = await screen.findByRole("option", { name: /Exact queue scope/ });
    expect(within(option).getByText(`Exact queue scope · ${variantScope}`)).toBeInTheDocument();
    expect(within(option).queryByText(/^Uncatalogued queue scope/)).not.toBeInTheDocument();
    fireEvent.click(option);
    expect(searchParamWrites).toHaveBeenCalledWith({ project: variantScope, task: undefined });
  });

  it("shows a named empty project state and clearing it restores the complete queue", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ project: "/workspace/cockpit" });
    const allTasks = [
      snapshot({ id: "task-catalog", projectKey: "/workspace/black-box", title: "Catalog-backed task" }),
    ];
    const store = fakeTaskStore(allTasks, async () => undefined, true);
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));

    expect(await screen.findByRole("heading", { name: "No work is queued for cockpit" })).toBeInTheDocument();
    expect(screen.getByText(/Agents enqueue work through REST or MCP/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open project" })).toHaveAttribute("href", "/projects/catalog-cockpit");
    fireEvent.click(screen.getByRole("button", { name: /Clear project/i }));

    expect(await screen.findByText("Catalog-backed task")).toBeInTheDocument();
    expect(searchParamWrites).toHaveBeenCalledWith({ project: undefined, task: undefined });
    expect(store.setFilters).toHaveBeenLastCalledWith({ limit: 250 });
  });

  it("keeps an empty grouped variant labeled as an exact queue scope", async () => {
    const variantScope = "/workspace/black-box-worktrees/feature";
    [params, setParams] = createStore<BoardSearchParams>({ project: variantScope });
    const store = fakeTaskStore([
      snapshot({ id: "task-primary", projectKey: "/workspace/black-box", title: "Primary-scoped task" }),
    ], async () => undefined, true);

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));

    expect(await screen.findByRole("heading", { name: "No tasks match this view" })).toBeInTheDocument();
    expect(screen.getByText(`Exact queue scope · ${variantScope}`)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "No work is queued for black-box" })).not.toBeInTheDocument();
    expect(store.setFilters).toHaveBeenCalledWith({ projectKey: variantScope, limit: 250 });
  });

  it("uses the generic empty state when another filter excludes a project's tasks", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ project: "/workspace/black-box", lane: "review" });
    const store = fakeTaskStore([
      snapshot({ id: "task-catalog", projectKey: "/workspace/black-box", title: "Catalog-backed task", lane: "codex" }),
    ], async () => undefined, true);
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));

    expect(await screen.findByRole("heading", { name: "No tasks match this view" })).toBeInTheDocument();
    expect(screen.queryByText(/No work is queued for/)).not.toBeInTheDocument();
  });

  it("keeps unmatched historical task scopes accessible as uncatalogued choices", async () => {
    const store = fakeTaskStore(fixtures());
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => catalogProjects()}
      />
    ));
    await screen.findByText("Shape the contract");

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "other-project" } });
    const option = await screen.findByRole("option", { name: /other-project/ });
    expect(within(option).getByText(/^Uncatalogued queue scope/)).toBeInTheDocument();
    fireEvent.click(option);

    await waitFor(() => expect(searchParamWrites).toHaveBeenCalledWith({ project: "other-project", task: undefined }));
    expect(store.setFilters).toHaveBeenCalledWith({ projectKey: "other-project", limit: 250 });
    fireEvent.click(screen.getByRole("button", { name: "Review the release, Open" }));
    expect(within(screen.getByRole("complementary", { name: "Task detail" })).queryByRole("link", { name: "Open project" })).not.toBeInTheDocument();
  });

  it("keeps queued work and raw-scope filtering usable when the project catalog fails", async () => {
    const store = fakeTaskStore(fixtures());
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={async () => {
          throw new Error("catalog offline");
        }}
      />
    ));

    expect(await screen.findByText("Shape the contract")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    expect(await screen.findByText("Unable to load projects.")).toBeInTheDocument();

    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "other-project" } });
    fireEvent.click(await screen.findByRole("option", { name: /other-project/ }));

    await waitFor(() => expect(store.setFilters).toHaveBeenCalledWith({ projectKey: "other-project", limit: 250 }));
    expect(screen.getByText("Shape the contract")).toBeInTheDocument();
  });

  it("labels a retained snapshot as stale when a filter refresh fails", async () => {
    const store = fakeTaskStore(fixtures());
    store.refresh
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error("filtered queue offline"));
    render(() => <BoardPage store={store} updateStatus={vi.fn()} loadProjects={emptyCatalog} />);
    await waitFor(() => expect(store.refresh).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByText("refreshing")).not.toBeInTheDocument());

    fireEvent.click(await screen.findByRole("button", { name: /All projects/ }));
    fireEvent.input(screen.getByLabelText("Search projects"), { target: { value: "other-project" } });
    fireEvent.click(await screen.findByRole("option", { name: /other-project/ }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Snapshot refresh failed");
    expect(alert).toHaveTextContent("filtered queue offline");
    expect(alert).toHaveTextContent("every project / every lane");
    expect(screen.getByText("Shape the contract")).toBeInTheDocument();

    store.refresh.mockResolvedValueOnce(undefined);
    fireEvent.click(within(alert).getByRole("button", { name: "Retry refreshing board" }));
    await waitFor(() => expect(screen.queryByText("Snapshot refresh failed")).not.toBeInTheDocument());
  });

  it("shows a deliberate pending reset and relies on a refresh for canonical state", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-blocked" });
    const store = fakeTaskStore(fixtures());
    let resolveReset!: () => void;
    const updateStatus = vi.fn(() => new Promise<TaskChange>((resolve) => {
      resolveReset = () => resolve({ snapshot: fixtures()[2]!, event: {
        id: "event-reset",
        taskId: "task-blocked",
        type: "task.reset",
        actor: "board",
        fromStatus: "blocked",
        toStatus: "open",
        detail: null,
        observedAt: "2026-07-10T01:00:00Z",
      } });
    }));
    render(() => <BoardPage store={store} updateStatus={updateStatus} loadProjects={emptyCatalog} />);
    const reset = await screen.findByRole("button", { name: "Reset task to open" });

    fireEvent.click(reset);

    expect(reset).toBeDisabled();
    expect(reset).toHaveTextContent("Resetting");
    expect(updateStatus).toHaveBeenCalledWith("task-blocked", { actor: "board", status: "open" });
    resolveReset();
    await waitFor(() => expect(store.refresh).toHaveBeenCalledTimes(2));
  });

  it("keeps a failed reset visible without inventing a local status change", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-blocked" });
    const store = fakeTaskStore(fixtures());
    const updateStatus = vi.fn(async () => {
      throw new Error("task changed concurrently");
    });
    render(() => <BoardPage store={store} updateStatus={updateStatus} loadProjects={emptyCatalog} />);

    fireEvent.click(await screen.findByRole("button", { name: "Reset task to open" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Reset failed: task changed concurrently");
    expect(within(screen.getByRole("region", { name: "Blocked tasks" })).getByText("Verify the queue")).toBeInTheDocument();
    expect(store.refresh).toHaveBeenCalledTimes(1);
  });

  it("keeps concurrent reset state scoped to the task that initiated it", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-blocked" });
    const store = fakeTaskStore(fixtures());
    let rejectReset!: (error: Error) => void;
    const updateStatus = vi.fn(() => new Promise<TaskChange>((_resolve, reject) => {
      rejectReset = reject;
    }));
    render(() => <BoardPage store={store} updateStatus={updateStatus} loadProjects={emptyCatalog} />);
    fireEvent.click(await screen.findByRole("button", { name: "Reset task to open" }));

    fireEvent.click(screen.getByRole("button", { name: "Build the client, In progress" }));
    const otherReset = await screen.findByRole("button", { name: "Reset task to open" });
    expect(otherReset).not.toBeDisabled();
    rejectReset(new Error("blocked task changed"));
    await waitFor(() => expect(screen.queryByText(/blocked task changed/)).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Verify the queue, Blocked" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("blocked task changed");
  });

  it("renders distinct loading, failure, and empty workspace states", async () => {
    let rejectLoad!: (error: Error) => void;
    const store = fakeTaskStore([], () => new Promise<void>((_resolve, reject) => {
      rejectLoad = reject;
    }));
    render(() => <BoardPage store={store} updateStatus={vi.fn()} loadProjects={emptyCatalog} />);

    expect(screen.getByRole("status", { name: "Loading coordination board" })).toBeInTheDocument();
    await waitFor(() => expect(store.refresh).toHaveBeenCalled());
    rejectLoad(new Error("queue offline"));
    expect(await screen.findByRole("alert")).toHaveTextContent("queue offline");

    store.refresh.mockResolvedValue(undefined);
    fireEvent.click(screen.getByRole("button", { name: "Retry loading board" }));
    expect(await screen.findByText("No tasks match this view")).toBeInTheDocument();
  });

  it("merges fetched and live activity without duplicates and orders it newest first", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-active" });
    const duplicate = annotation({
      id: "note-fetched",
      text: "Fetched progress",
      observedAt: "2026-07-15T12:01:00Z",
    });
    const live = annotation({
      id: "note-live",
      kind: "progress",
      actor: "worker-1",
      text: "Live verification complete",
      observedAt: "2026-07-15T12:02:00Z",
    });
    const store = fakeTaskStore(fixtures(), undefined, false, [duplicate, live]);
    const history: TaskEvent[] = [
      {
        id: "event-created",
        taskId: "task-active",
        type: "task.created",
        actor: "planner",
        fromStatus: null,
        toStatus: "open",
        detail: null,
        observedAt: "2026-07-15T12:00:00Z",
      },
      {
        id: duplicate.id,
        taskId: "task-active",
        type: "task.note",
        actor: duplicate.actor,
        detail: { kind: duplicate.kind, text: duplicate.text, dataJson: duplicate.dataJson },
        observedAt: duplicate.observedAt,
      },
    ];

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={emptyCatalog}
        getTaskEvents={vi.fn(async () => history)}
      />
    ));

    await screen.findByText("Live verification complete");
    const rows = [...document.querySelectorAll<HTMLElement>(".board-annotation-row")];
    expect(rows.map((row) => row.dataset.annotationId)).toEqual(["note-live", "note-fetched", "event-created"]);
    expect(rows.filter((row) => row.dataset.annotationId === "note-fetched")).toHaveLength(1);
    expect(rows[2]).toHaveTextContent("— → open");
  });

  it("links the newest worker session back to the selected task", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-active" });
    const store = fakeTaskStore(fixtures(), undefined, false, [annotation({
      id: "worker-session",
      kind: "worker_session",
      text: "Worker session attached",
      dataJson: { sessionId: "session-9" },
    })]);

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={emptyCatalog}
        getTaskEvents={vi.fn(async () => [])}
      />
    ));

    expect(await screen.findByRole("link", { name: "Open worker session →" })).toHaveAttribute(
      "href",
      "/sessions/session-9?task=task-active",
    );
  });

  it("derives engine, branch, and pull request chips with later values winning", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-active" });
    const store = fakeTaskStore(fixtures(), undefined, false, [
      annotation({
        id: "engine-old",
        kind: "engine",
        text: "Runner selected",
        dataJson: { engine: "claude", branch: "feature/old", pr: "https://example.com/pr/1" },
        observedAt: "2026-07-15T12:00:00Z",
      }),
      annotation({
        id: "progress-new",
        kind: "progress",
        text: "Implementation moved",
        dataJson: { engine: "codex", branch: "feature/new", prUrl: "https://example.com/pr/2" },
        observedAt: "2026-07-15T12:05:00Z",
      }),
    ]);

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={emptyCatalog}
        getTaskEvents={vi.fn(async () => [])}
      />
    ));

    const chips = await screen.findByLabelText("Agent run context");
    expect(chips).toHaveTextContent("Engine · codex");
    expect(chips).toHaveTextContent("feature/new");
    expect(within(chips).getByRole("link", { name: "Pull request ↗" })).toHaveAttribute("href", "https://example.com/pr/2");
  });

  it("enables steering only while the selected task is in progress", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-active" });
    const store = fakeTaskStore(fixtures());
    const createAnnotation = vi.fn(async () => annotation({ kind: "steer", text: "Stay on verification." }));
    const getTaskEvents = vi.fn(async () => [] as TaskEvent[]);
    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={emptyCatalog}
        getTaskEvents={getTaskEvents}
        createAnnotation={createAnnotation}
      />
    ));

    const textarea = await screen.findByRole("textbox", { name: "Steer this run" });
    fireEvent.input(textarea, { target: { value: "Stay on verification." } });
    fireEvent.click(screen.getByRole("button", { name: "Send steer" }));
    await waitFor(() => expect(createAnnotation).toHaveBeenCalledWith("task-active", {
      actor: "board",
      kind: "steer",
      text: "Stay on verification.",
    }));

    fireEvent.click(screen.getByRole("button", { name: "Verify the queue, Blocked" }));
    expect(await screen.findByText("Steering is only available while the task is in progress.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Steer this run" })).not.toBeInTheDocument();
  });

  it("lazily fetches and highlights the selected task in the agent DAG", async () => {
    [params, setParams] = createStore<BoardSearchParams>({ task: "task-active" });
    const store = fakeTaskStore(fixtures());
    const dag: DagResponse = {
      nodes: [
        { id: "spec-1", type: "spec", label: "Coordination story", ref: "/specs/spec-1" },
        { id: "task-active", type: "task", label: "Build the client", status: "in_progress", ref: "/tasks/task-active" },
      ],
      edges: [{ from: "spec-1", to: "task-active", type: "has_task" }],
    };
    const getTaskDag = vi.fn(async () => dag);

    render(() => (
      <BoardPage
        store={store}
        updateStatus={vi.fn()}
        loadProjects={emptyCatalog}
        getTaskEvents={vi.fn(async () => [])}
        getTaskDag={getTaskDag}
      />
    ));

    expect(getTaskDag).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "View agent DAG" }));
    await waitFor(() => expect(getTaskDag).toHaveBeenCalledWith("task-active"));
    expect(await screen.findByText("Build the client", { selector: ".dag-label" })).toBeInTheDocument();
    expect(document.querySelector('[data-node-id="task-active"]')).toHaveClass("dag-node--current");
  });

  it("ships explicit narrow stacking and reduced-motion contracts", () => {
    expect(themeCss).toContain("@media (max-width: 660px)");
    expect(themeCss).toContain(".board-columns,");
    expect(themeCss).toContain("grid-template-columns: 1fr;");
    expect(themeCss).toContain("@media (prefers-reduced-motion: reduce)");
    expect(themeCss).toContain(".board-loading-lines i { animation: none; }");
    expect(themeCss).toContain(".board-task-button:hover { transform: none; }");
  });
});

function fakeTaskStore(
  initial: TaskSnapshot[],
  refreshImpl: () => Promise<void> = async () => undefined,
  filterSnapshots = false,
  initialAnnotations: TaskAnnotation[] = [],
) {
  const [snapshots, setSnapshots] = createSignal(initial);
  const [filters, setFiltersSignal] = createSignal<TaskFilters>({});
  const [noteEpoch] = createSignal(0);
  const annotationsByTask = new Map<string, TaskAnnotation[]>();
  for (const item of initialAnnotations) {
    annotationsByTask.set(item.taskId, [...(annotationsByTask.get(item.taskId) ?? []), item]);
  }
  const refresh = vi.fn(refreshImpl);
  const store: TaskLiveStoreWithNotes & { setFilters: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> } = {
    status: () => "live",
    tasks: () => snapshots().map(({ task }) => task),
    snapshots,
    taskAnnotations: (taskId) => [...(annotationsByTask.get(taskId) ?? [])],
    noteEpoch,
    filters,
    setFilters: vi.fn(async (next: TaskFilters) => {
      const current = filters();
      if (current.projectKey === next.projectKey
        && current.lane === next.lane
        && current.status === next.status
        && current.limit === next.limit) return;
      setFiltersSignal(next);
      if (filterSnapshots) {
        setSnapshots(initial.filter(({ task }) => (
          (!next.projectKey || task.projectKey === next.projectKey)
          && (!next.lane || task.lane === next.lane)
          && (!next.status || task.status === next.status)
        )));
      }
      await refresh();
    }),
    refresh,
    applyFrame: (frame: TaskLifecycleFrame) => setSnapshots((current) => current.map((item) => (
      item.task.id === frame.task.id ? { ...item, task: frame.task } : item
    ))),
    diagnostics: () => ({ records: snapshots().length, versions: snapshots().length, liveMutations: 0, transitions: 0 }),
    close: vi.fn(),
  };
  return store;
}

function annotation(overrides: Partial<TaskAnnotation> = {}): TaskAnnotation {
  return {
    id: "note-1",
    taskId: "task-active",
    kind: "note",
    actor: "worker-1",
    text: "Task note",
    dataJson: null,
    observedAt: "2026-07-15T12:00:00Z",
    ...overrides,
  };
}

function catalogProjects(): ProjectSummary[] {
  return [
    {
      projectKey: "catalog-black-box",
      canonicalKey: "/workspace/black-box",
      label: "/workspace/black-box",
      sessionCount: 6,
      eventCount: 42,
      savedMeldCount: 0,
      lastSeenAt: "2026-07-10T02:00:00Z",
      scopes: [
        {
          projectKey: "catalog-black-box",
          canonicalKey: "/workspace/black-box",
          label: "/workspace/black-box",
          primary: true,
        },
        {
          projectKey: "catalog-black-box-feature",
          canonicalKey: "/workspace/black-box-worktrees/feature",
          label: "black-box feature worktree",
          primary: false,
        },
      ],
    },
    {
      projectKey: "catalog-cockpit",
      canonicalKey: "/workspace/cockpit",
      label: "/workspace/cockpit",
      sessionCount: 3,
      eventCount: 18,
      savedMeldCount: 0,
      lastSeenAt: "2026-07-10T01:00:00Z",
    },
  ];
}

function fixtures(): TaskSnapshot[] {
  return [
    snapshot({ id: "task-open", title: "Shape the contract", status: "open", lane: "planning", priority: 9 }),
    snapshot({ id: "task-active", title: "Build the client", status: "in_progress", lane: "frontend", priority: 7, claimedBy: "worker-1" }),
    snapshot({
      id: "task-blocked",
      title: "Verify the queue",
      status: "blocked",
      lane: "review",
      priority: 8,
      claimedBy: "worker-2",
      blockedReason: "waiting for deterministic fixture data",
    }, "Frozen acceptance contract for blocked work."),
    snapshot({ id: "task-done", title: "Ship the adapter", status: "done", lane: "backend", priority: 5, claimedBy: "worker-3", resultHandoffId: "handoff-44" }),
    snapshot({ id: "task-cancelled", title: "Retired experiment", status: "cancelled", lane: "research", priority: 1 }),
    snapshot({ id: "task-other", projectKey: "other-project", title: "Review the release", status: "open", lane: "review", priority: 4 }),
  ];
}

function snapshot(taskOverrides: Partial<AgentTask>, body = "Frozen spec body."): TaskSnapshot {
  const task: AgentTask = {
    id: "task-1",
    specId: `spec-${taskOverrides.id ?? "1"}`,
    projectKey: "black-box",
    title: "Task",
    lane: "codex",
    status: "open",
    priority: 1,
    createdBy: "planner",
    claimedBy: null,
    blockedReason: null,
    resultHandoffId: null,
    createdAt: "2026-07-10T00:00:00Z",
    updatedAt: "2026-07-10T00:05:00Z",
    ...taskOverrides,
  };
  return {
    task,
    spec: {
      id: task.specId,
      projectKey: task.projectKey,
      title: "Agentic coordination loop",
      body,
      specRef: { path: "specs/queue.md", sha: "abc1234" },
      status: "active",
      createdBy: "planner",
      createdAt: "2026-07-09T23:00:00Z",
      updatedAt: "2026-07-09T23:00:00Z",
    },
  };
}
