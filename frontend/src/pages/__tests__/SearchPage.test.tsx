import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AgentEvent, ProjectSummary } from "../../lib/api";
import { CodeNavigationContext } from "../../lib/codeNavigation";
import SearchPage from "../SearchPage";

let params: { q?: string };
let setParams: SetStoreFunction<{ q?: string }>;

const mocks = vi.hoisted(() => ({
  askStatus: vi.fn(),
  openInEditor: vi.fn(),
  search: vi.fn(),
}));

const search = mocks.search;
const selectedProject: ProjectSummary = {
  projectKey: "sba-key",
  canonicalKey: "/Users/nathan/Developer/proj/sba-agentic",
  label: "~/Developer/proj/sba-agentic",
  sessionCount: 1,
  eventCount: 1,
  savedMeldCount: 0,
};

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    A: (props: { href: string; class?: string; onClick?: JSX.EventHandlerUnion<HTMLAnchorElement, MouseEvent>; children?: JSX.Element }) => (
      <a href={props.href} class={props.class} onClick={props.onClick}>
        {props.children}
      </a>
    ),
    useSearchParams: () => [params, setParams],
  };
});

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    askStatus: mocks.askStatus,
    openInEditor: mocks.openInEditor,
    search: mocks.search,
    searchValues: vi.fn(async (_field: string, prefix: string) =>
      ["Decision", "Handoff", "Observation"].filter((value) =>
        value.toLowerCase().startsWith(prefix.toLowerCase()),
      ),
    ),
  };
});

beforeEach(() => {
  [params, setParams] = createStore<{ q?: string }>({});
  mocks.askStatus.mockReset();
  mocks.askStatus.mockResolvedValue({
    chat: { enabled: false, available: false },
    elasticsearch: { enabled: false, available: false },
  });
  search.mockReset();
  search.mockImplementation(async (query: string) => ({
    query,
    local: [],
    elastic: [],
    elasticHealth: {},
  }));
  mocks.openInEditor.mockReset();
  mocks.openInEditor.mockResolvedValue({ status: "opened" });
});

describe("SearchPage", () => {
  it("renders and removes exclude facet chips", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "NOT kind:PostToolUse" });
    render(() => <SearchPage />);

    const chip = await screen.findByRole("button", { name: "kind != PostToolUse" });
    expect(chip).toHaveClass("facet-chip--exclude");

    fireEvent.click(chip);
    await waitFor(() => expect(params.q).toBeUndefined());
  });

  it("dismisses facet suggestions after selection, Escape, and click-away", async () => {
    render(() => <SearchPage />);

    const input = screen.getByLabelText("Search query");
    fireEvent.input(input, { target: { value: "kind:Dec" } });

    expect(await screen.findByRole("listbox")).toBeInTheDocument();

    fireEvent.click(within(screen.getByRole("listbox")).getByRole("button", { name: "Decision" }));
    await waitFor(() => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
    expect(input).toHaveValue("kind:Decision ");

    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(document.querySelector(".facet-chip--active")).toHaveTextContent("Decision"));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    fireEvent.click(document.querySelector(".facet-chip--active") as HTMLElement);
    await waitFor(() => expect(document.querySelector(".facet-chip--active")).not.toBeInTheDocument());

    fireEvent.input(input, { target: { value: "kind:Han" } });
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
    fireEvent.keyDown(input, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());

    fireEvent.input(input, { target: { value: "kind:Obs" } });
    expect(await screen.findByRole("listbox")).toBeInTheDocument();
    fireEvent.pointerDown(document.body);
    await waitFor(() => expect(screen.queryByRole("listbox")).not.toBeInTheDocument());
  });

  it("passes selected project as a hidden search facet", async () => {
    render(() => <SearchPage project={selectedProject} />);

    fireEvent.input(screen.getByLabelText("Search query"), { target: { value: "kind:Decision" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    await waitFor(() =>
      expect(search).toHaveBeenLastCalledWith("kind:Decision project_group:/Users/nathan/Developer/proj/sba-agentic", 120),
    );
  });

  it("keeps a failed project search scoped and retries without crashing the route", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });
    search
      .mockRejectedValueOnce(new Error("search backend offline"))
      .mockResolvedValueOnce({
        query: "kind:Decision project_group:/Users/nathan/Developer/proj/sba-agentic",
        local: [],
        elastic: [],
        elasticHealth: {},
      });

    render(() => <SearchPage project={selectedProject} />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Scoped project search unavailable.");
    expect(alert).toHaveTextContent("search backend offline");
    expect(alert).toHaveTextContent("The selected project scope was preserved; Black Box did not load global results.");
    expect(search).toHaveBeenCalledTimes(1);

    fireEvent.click(within(alert).getByRole("button", { name: "Retry search" }));

    await waitFor(() => expect(search).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(document.querySelector(".result-summary")).toHaveTextContent("0 events · 0 sessions"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("does not search globally while project scope is pending", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "kind:Decision" });

    render(() => <SearchPage projectScopePending />);

    await Promise.resolve();
    expect(search).not.toHaveBeenCalled();
  });

  it("removes visible positive project facets before applying hidden project scope", async () => {
    [params, setParams] = createStore<{ q?: string }>({ q: "project:cockpit kind:Decision" });

    render(() => <SearchPage project={selectedProject} />);

    await waitFor(() => expect(params.q).toBe("kind:Decision"));
    const projectGroup = Array.from(document.querySelectorAll(".facet-group")).find((element) =>
      within(element as HTMLElement).queryByText("Project"),
    ) as HTMLElement;
    expect(within(projectGroup).queryByRole("button", { name: /cockpit/ })).not.toBeInTheDocument();
    expect(search).toHaveBeenLastCalledWith("kind:Decision project_group:/Users/nathan/Developer/proj/sba-agentic", 120);
  });

  it("warns that Ask is not scoped by selected project", async () => {
    mocks.askStatus.mockResolvedValue({
      chat: { enabled: true, available: true },
      elasticsearch: { enabled: true, available: true },
    });

    render(() => <SearchPage mode="ask" showModeTabs={false} project={selectedProject} />);

    expect(await screen.findByText("Project context is not applied to Ask yet. Ask will search across all recorded memory."))
      .toBeInTheDocument();
    expect(await screen.findByPlaceholderText("Ask across the recorded memory…")).toBeInTheDocument();
  });

  it("keeps file actions independent from the explicit result session link", async () => {
    const path = "/Users/nathan/Developer/proj/opensource/t3code/src/service.ts";
    const event: AgentEvent = {
      id: "evt-read",
      sessionId: "session-read",
      source: "codex",
      clientSessionId: "client-read",
      eventType: "PreToolUse",
      role: "assistant",
      toolName: "Read",
      toolInputJson: JSON.stringify({ file_path: path, offset: 40 }),
      observedAt: "2026-07-29T18:00:00Z",
    };
    [params, setParams] = createStore<{ q?: string }>({ q: "tool:Read" });
    search.mockResolvedValue({
      query: "tool:Read",
      local: [event],
      elastic: [],
      elasticHealth: {},
    });
    const selectSession = vi.fn();

    render(() => (
      <CodeNavigationContext.Provider
        value={{
          scopes: () => [{
            projectKey: "t3-key",
            root: "/Users/nathan/Developer/proj/opensource/t3code",
          }],
          catalogStatus: () => "ready",
          catalogError: () => null,
          refreshCatalog: () => undefined,
        }}
      >
        <SearchPage onSelectSession={selectSession} />
      </CodeNavigationContext.Provider>
    ));

    const fileButton = await screen.findByRole("button", { name: `Open ${path} in editor` });
    fireEvent.click(fileButton);
    await waitFor(() => expect(mocks.openInEditor).toHaveBeenCalledWith({
      projectKey: "t3-key",
      relativePath: "src/service.ts",
      line: 40,
    }));
    expect(selectSession).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("link", { name: "View session" }));
    expect(selectSession).toHaveBeenCalledWith("session-read", "evt-read");
  });
});
