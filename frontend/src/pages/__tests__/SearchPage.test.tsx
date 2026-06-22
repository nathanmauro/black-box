import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SearchPage from "../SearchPage";

let params: { q?: string };
let setParams: SetStoreFunction<{ q?: string }>;

vi.mock("@solidjs/router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@solidjs/router")>();
  return {
    ...actual,
    useSearchParams: () => [params, setParams],
  };
});

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    askStatus: vi.fn(async () => ({
      chat: { enabled: false, available: false },
      elasticsearch: { enabled: false, available: false },
    })),
    search: vi.fn(async (query: string) => ({
      query,
      local: [],
      elastic: [],
      elasticHealth: {},
    })),
    searchValues: vi.fn(async (_field: string, prefix: string) =>
      ["Decision", "Handoff", "Observation"].filter((value) =>
        value.toLowerCase().startsWith(prefix.toLowerCase()),
      ),
    ),
  };
});

beforeEach(() => {
  [params, setParams] = createStore<{ q?: string }>({});
});

describe("SearchPage", () => {
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
});
