import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getIdeas, type IdeaView } from "../../lib/api";
import IdeasPage from "../IdeasPage";

type Params = { status?: string; origin?: string; q?: string; project?: string };

let params: Params;
let updateParams: SetStoreFunction<Params>;
const setParams = vi.fn();

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element; class?: string }) => (
    <a href={props.href} class={props.class}>
      {props.children}
    </a>
  ),
  useSearchParams: () => [params, setParams],
}));

function idea(overrides: Partial<IdeaView>): IdeaView {
  return {
    eventId: "evt-1",
    sessionId: "ses-1",
    source: "claude",
    clientSessionId: "client-1",
    repo: "/workspace/black-box",
    title: "Idea",
    oneLiner: "One line.",
    origin: "human-aside",
    quote: null,
    sourceRef: null,
    legs: null,
    status: "untouched",
    connects: [],
    resumeStep: null,
    link: null,
    notes: null,
    ideaKey: "idea",
    capturedAt: "2026-09-28T12:00:00Z",
    firstCapturedAt: "2026-09-27T12:00:00Z",
    revisions: 1,
    migratedFrom: null,
    ...overrides,
  };
}

const AGENT_IDEA = idea({
  eventId: "evt-agent",
  sessionId: "ses-agent",
  title: "Tangent router",
  oneLiner: "Detect human asides and file them as ideas.",
  origin: "agent-proposed",
  status: "untouched",
  legs: 8,
  quote: "what if the stream led\nwith what I actually said",
  connects: ["human-turn-first", "BB-12"],
  resumeStep: "Sketch the classifier",
  link: "https://example.com/issue/BB-12",
  ideaKey: "black-box/tangent-router",
  revisions: 3,
  migratedFrom: "obs-9",
});

const HUMAN_IDEA = idea({
  eventId: "evt-human",
  title: "Evidence kind",
  oneLiner: "Capture evidence separately from observations.",
  origin: "human-aside",
  status: "tracked",
  legs: 4,
  link: "javascript:alert(1)",
  ideaKey: "black-box/evidence-kind",
});

vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    getProjects: vi.fn(async () => []),
    getIdeas: vi.fn(),
  };
});

beforeEach(() => {
  [params, updateParams] = createStore<Params>({});
  setParams.mockReset();
  setParams.mockImplementation((next: Params) => updateParams(next));
  vi.mocked(getIdeas).mockReset();
  vi.mocked(getIdeas).mockResolvedValue({ items: [HUMAN_IDEA, AGENT_IDEA], count: 2 });
});

describe("IdeasPage", () => {
  it("pins agent-proposed untouched ideas above everything else by default", async () => {
    render(() => <IdeasPage />);

    const pinned = await screen.findByRole("region", { name: "Nobody answered these" });
    expect(within(pinned).getByText("Tangent router")).toBeInTheDocument();
    expect(within(pinned).queryByText("Evidence kind")).not.toBeInTheDocument();
    expect(within(pinned).getByText("1")).toHaveClass("ideas-section-count");

    const rest = screen.getByRole("region", { name: "Everything else" });
    expect(within(rest).getByText("Evidence kind")).toBeInTheDocument();
    expect(getIdeas).toHaveBeenCalledWith({
      status: [],
      origin: undefined,
      project: undefined,
      q: undefined,
      limit: 500,
    });
  });

  it("renders the full idea row contract", async () => {
    render(() => <IdeasPage />);

    const row = await screen.findByRole("article", { name: "Tangent router" });
    expect(
      within(row).getByText("Detect human asides and file them as ideas."),
    ).toBeInTheDocument();
    expect(within(row).getByText("agent proposed")).toHaveClass("idea-origin--agent-proposed");
    expect(within(row).getByText("untouched")).toHaveClass("idea-status--untouched");
    expect(within(row).getByRole("meter", { name: "legs 8/10" })).toHaveAttribute("value", "8");
    const quote = row.querySelector(".idea-quote");
    expect(quote?.textContent).toBe("what if the stream led\nwith what I actually said");
    expect(within(row).getByText("human-turn-first")).toBeInTheDocument();
    expect(within(row).getByText("BB-12")).toBeInTheDocument();
    expect(within(row).getByText("Sketch the classifier")).toBeInTheDocument();
    expect(within(row).getByText("3 revisions")).toBeInTheDocument();
    expect(within(row).getByText("migrated")).toHaveAttribute(
      "title",
      "Migrated from observation obs-9",
    );
    const link = within(row).getByRole("link", { name: "https://example.com/issue/BB-12" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    expect(within(row).getByRole("link", { name: /Capturing session/ })).toHaveAttribute(
      "href",
      "/sessions/ses-agent",
    );

    const human = screen.getByRole("article", { name: "Evidence kind" });
    expect(within(human).queryByText(/revisions/)).not.toBeInTheDocument();
    expect(within(human).queryByRole("link", { name: /javascript/ })).not.toBeInTheDocument();
    expect(within(human).getByText("javascript:alert(1)")).toHaveClass("idea-link--plain");
  });

  it("puts status and origin filters in the URL and refetches", async () => {
    render(() => <IdeasPage />);
    await screen.findByText("Tangent router");

    fireEvent.click(
      within(screen.getByRole("group", { name: "Status" })).getByRole("button", {
        name: "tracked",
      }),
    );
    expect(setParams).toHaveBeenLastCalledWith({ status: "tracked" });
    fireEvent.click(
      within(screen.getByRole("group", { name: "Status" })).getByRole("button", {
        name: "untouched",
      }),
    );
    expect(setParams).toHaveBeenLastCalledWith({ status: "untouched,tracked" });

    fireEvent.click(
      within(screen.getByRole("group", { name: "Origin" })).getByRole("button", {
        name: "human aside",
      }),
    );
    expect(setParams).toHaveBeenLastCalledWith({ origin: "human-aside" });

    await waitFor(() =>
      expect(getIdeas).toHaveBeenLastCalledWith({
        status: ["untouched", "tracked"],
        origin: "human-aside",
        project: undefined,
        q: undefined,
        limit: 500,
      }),
    );
    expect(
      within(screen.getByRole("group", { name: "Origin" })).getByRole("button", {
        name: "human aside",
      }),
    ).toHaveAttribute("aria-pressed", "true");
    // Filtered views drop the pinned section and show one flat list.
    expect(await screen.findByRole("region", { name: "Matching ideas" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Nobody answered these" })).not.toBeInTheDocument();

    fireEvent.click(
      within(screen.getByRole("group", { name: "Origin" })).getByRole("button", {
        name: "human aside",
      }),
    );
    expect(setParams).toHaveBeenLastCalledWith({ origin: undefined });
  });

  it("restores filters from the URL, ignoring unknown values, and submits text search", async () => {
    updateParams({ status: "tracked,bogus", origin: "martian", project: "black-box" });
    render(() => <IdeasPage />);
    await screen.findByText("Tangent router");

    expect(getIdeas).toHaveBeenCalledWith({
      status: ["tracked"],
      origin: undefined,
      project: "black-box",
      q: undefined,
      limit: 500,
    });
    expect(
      within(screen.getByRole("group", { name: "Status" })).getByRole("button", {
        name: "tracked",
      }),
    ).toHaveAttribute("aria-pressed", "true");

    fireEvent.input(screen.getByLabelText("Search ideas"), { target: { value: "  router " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(setParams).toHaveBeenLastCalledWith({ q: "router" });
    await waitFor(() =>
      expect(getIdeas).toHaveBeenLastCalledWith(expect.objectContaining({ q: "router" })),
    );

    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(setParams).toHaveBeenLastCalledWith({
      status: undefined,
      origin: undefined,
      q: undefined,
    });
  });

  it("shows an honest empty state", async () => {
    vi.mocked(getIdeas).mockResolvedValue({ items: [], count: 0 });
    render(() => <IdeasPage />);

    expect(await screen.findByRole("heading", { name: "No ideas captured yet" })).toBeVisible();
  });

  it("shows a filtered empty state and a retryable error", async () => {
    updateParams({ origin: "joint" });
    vi.mocked(getIdeas).mockResolvedValueOnce({ items: [], count: 0 });
    render(() => <IdeasPage />);
    expect(
      await screen.findByRole("heading", { name: "No ideas match these filters" }),
    ).toBeVisible();

    vi.mocked(getIdeas).mockRejectedValueOnce(new Error("503 Service Unavailable"));
    fireEvent.click(
      within(screen.getByRole("group", { name: "Status" })).getByRole("button", {
        name: "superseded",
      }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Ideas failed to load: 503 Service Unavailable",
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });
});
