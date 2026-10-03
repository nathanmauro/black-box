import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { createStore, type SetStoreFunction } from "solid-js/store";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  captureDecision,
  getProjects,
  getRecall,
  type RecallResult,
  type RecalledItem,
} from "../../lib/api";
import { sourceFilter } from "../../lib/stores";
import RecallPage from "../RecallPage";

type Params = {
  scope?: string;
  project?: string;
  query?: string;
  run?: string;
  withinHours?: string;
  kinds?: string;
  history?: string;
};
let params: Params;
let updateParams: SetStoreFunction<Params>;
const setParams = vi.fn();
const writeText = vi.fn();
const item: RecalledItem = {
  eventId: "evt-1",
  sessionId: "session-1",
  kind: "decision",
  source: "codex",
  clientSessionId: "client-1",
  repo: "/repos/alpha",
  observedAt: "2026-10-01T20:00:00Z",
  headline: "Use SQLite storage",
  rationale: "Keep the local default simple.",
  alternatives: ["Shared server"],
  confidence: 0.82,
  openLoops: ["Verify recovery"],
};
const result = (items = [item]): RecallResult => ({
  withinHours: 168,
  kinds: ["decision", "handoff"],
  count: items.length,
  items,
  mode: "lexical",
});

vi.mock("@solidjs/router", () => ({
  A: (props: { href: string; children: JSX.Element; class?: string; "aria-label"?: string }) => (
    <a href={props.href} class={props.class} aria-label={props["aria-label"]}>
      {props.children}
    </a>
  ),
  useSearchParams: () => [params, setParams],
}));
vi.mock("../../lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api")>()),
  getRecall: vi.fn(),
  getProjects: vi.fn(),
  getEvent: vi.fn(async (eventId: string) => ({ id: eventId, sessionId: `owning-${eventId}` })),
  captureDecision: vi.fn(),
}));
beforeEach(() => {
  [params, updateParams] = createStore<Params>({});
  setParams.mockReset();
  setParams.mockImplementation((next: Params) =>
    updateParams(
      Object.fromEntries(
        Object.entries(next).map(([key, value]) => [key, value === "" ? undefined : value]),
      ),
    ),
  );
  vi.mocked(getRecall).mockReset().mockResolvedValue(result());
  vi.mocked(getProjects)
    .mockReset()
    .mockResolvedValue([
      {
        projectKey: "alpha-key",
        canonicalKey: "/repos/alpha",
        label: "alpha",
        sessionCount: 1,
        eventCount: 1,
        savedMeldCount: 0,
        lastSeenAt: "2026-10-01T20:00:00Z",
      },
      {
        projectKey: "beta-key",
        canonicalKey: "/repos/beta",
        label: "beta",
        sessionCount: 1,
        eventCount: 1,
        savedMeldCount: 0,
        lastSeenAt: "2026-10-01T19:00:00Z",
      },
    ]);
  vi.mocked(captureDecision)
    .mockReset()
    .mockResolvedValue({} as never);
  writeText.mockReset().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  sourceFilter.clear();
});
async function chooseProject(name: string) {
  fireEvent.click(screen.getByRole("button", { name: /^Project / }));
  fireEvent.click(await screen.findByRole("option", { name: new RegExp(`^${name} `) }));
}

describe("RecallPage", () => {
  it("links the latest handoff by full timestamp when recall returns relevance order", async () => {
    const older = {
      ...item,
      kind: "handoff",
      eventId: "older-handoff",
      headline: "Older handoff",
      observedAt: "2026-10-03T12:00:00.123456788Z",
    };
    const newer = {
      ...item,
      kind: "handoff",
      eventId: "newer-handoff",
      headline: "Newer handoff",
      observedAt: "2026-10-03T12:00:00.123456789Z",
    };
    vi.mocked(getRecall).mockResolvedValue(result([older, newer]));
    render(() => <RecallPage />);
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    const label = await screen.findByText("Latest retrieved handoff:");
    const briefing = label.parentElement!;
    expect(within(briefing).getByRole("link")).toHaveTextContent("Newer handoff");
    expect(within(briefing).getByRole("link")).toHaveAttribute(
      "href",
      expect.stringContaining("event=newer-handoff"),
    );
    expect(briefing).toHaveTextContent(newer.observedAt);
    expect(screen.getByRole("article", { name: "Older handoff" })).toBeInTheDocument();
  });

  it("keeps separate Projection basis visible when ingest capped the rendered body", async () => {
    const rationale = "Only consider a server after demonstrated demand.";
    vi.mocked(getRecall).mockResolvedValue(
      result([
        {
          ...item,
          kind: "projection",
          body: "Possible path. ".repeat(1500) + "[truncated]",
          rationale,
        },
      ]),
    );
    render(() => <RecallPage />);
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    const card = await screen.findByRole("article", { name: item.headline! });
    expect(card).toHaveTextContent(rationale);
    fireEvent.click(screen.getByRole("button", { name: "Copy context" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledOnce());
    expect(writeText.mock.calls[0][0]).toContain(`Recorded basis: ${rationale}`);
  });

  it("restores projection-only links and treats the first-path confidence as a path attribute", async () => {
    updateParams({ project: "/repos/alpha", kinds: "projection", run: "1" });
    const body = "Possible futures\nLocal default (0.8)\nShared server (0.2) only if needed.";
    vi.mocked(getRecall).mockResolvedValue(result([{ ...item, kind: "projection", body }]));
    render(() => <RecallPage />);
    const card = await screen.findByRole("article", { name: item.headline! });
    expect(getRecall).toHaveBeenCalledExactlyOnceWith(
      { project: "/repos/alpha", query: "", includeSuperseded: false },
      168,
      ["projection"],
    );
    expect(screen.getByRole("checkbox", { name: "Projection" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Decision" })).not.toBeChecked();
    expect(card).toHaveTextContent("Recorded possibilities; no selected outcome is implied.");
    expect(card).toHaveTextContent("Shared server (0.2) only if needed.");
    expect(within(card).queryByRole("meter")).not.toBeInTheDocument();
    expect(
      within(card).queryByRole("button", { name: "Replace decision" }),
    ).not.toBeInTheDocument();
  });

  it("offers an unselected Projection filter and source guidance for older headline-only results", async () => {
    vi.mocked(getRecall).mockResolvedValue(result([{ ...item, kind: "projection" }]));
    render(() => <RecallPage />);
    expect(screen.getByRole("checkbox", { name: "Projection" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("checkbox", { name: "Projection" }));
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    const card = await screen.findByRole("article", { name: item.headline! });
    expect(card).toHaveTextContent(
      "Open the source capture to inspect all recorded paths and their confidence.",
    );
    expect(within(card).queryByRole("meter")).not.toBeInTheDocument();
  });

  it("keeps observation headings short while exposing the complete body through reader and clipboard", async () => {
    const headline = "Observation checkpoint";
    const body =
      headline + "\n" + "Supporting evidence. ".repeat(80) + "\nDo not proceed until verified.";
    vi.mocked(getRecall).mockResolvedValue(
      result([{ ...item, kind: "observation", headline, body }]),
    );
    render(() => <RecallPage />);
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    const card = await screen.findByRole("article", { name: headline });
    expect(
      within(card).getByRole("link", { name: `Open ${headline} in Browse` }),
    ).toHaveTextContent(headline);
    expect(card).not.toHaveTextContent("Do not proceed until verified.");
    const toggle = within(card).getByRole("button", { name: "Show full message" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(card).toHaveTextContent("Do not proceed until verified.");
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole("button", { name: "Copy context" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledOnce());
    expect(writeText.mock.calls[0][0]).toContain(body);
    expect(writeText.mock.calls[0][0]).toContain("event=evt-1");
    expect(writeText.mock.calls[0][0]).toContain(
      "Export limits: 0 captures truncated; 0 captures omitted.",
    );
  });

  it("separates project from question and exports only the displayed evidence with provenance", async () => {
    render(() => <RecallPage />);
    await chooseProject("alpha");
    fireEvent.input(screen.getByLabelText("Question"), { target: { value: "storage" } });
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    expect(getRecall).toHaveBeenCalledWith(
      { project: "/repos/alpha", query: "storage", includeSuperseded: false },
      168,
      ["decision", "handoff"],
    );
    const card = await screen.findByRole("article", { name: item.headline! });
    expect(card).toHaveTextContent("Keep the local default simple.");
    expect(
      within(card).getByRole("link", { name: `Open ${item.headline} in Browse` }),
    ).toHaveAttribute("href", "/?view=browse&session=session-1&event=evt-1&project=");
    expect(screen.getByText(/Text matching/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy context" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledOnce());
    expect(writeText.mock.calls[0][0]).toContain("Project: /repos/alpha");
    expect(writeText.mock.calls[0][0]).toContain("event=evt-1");
    expect(writeText.mock.calls[0][0]).toContain("Recorded open questions: Verify recovery");
  });

  it.each([
    ["Three months · 90d", 2160],
    ["Six months · 180d", 4320],
  ])("retains scope-only compatibility for %s", async (label, hours) => {
    updateParams({ scope: "/workspace/example-app" });
    render(() => <RecallPage />);
    fireEvent.click(screen.getByRole("radio", { name: label }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Observation" }));
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    expect(getRecall).toHaveBeenCalledWith("/workspace/example-app", hours, [
      "decision",
      "handoff",
      "observation",
    ]);
    expect(await screen.findByRole("article", { name: item.headline! })).toBeInTheDocument();
  });

  it("consumes legacy run=1 once, normalizes text, and does not rerun when filters change", async () => {
    updateParams({ scope: "  C++ & café #1  ", run: "1" });
    render(() => <RecallPage />);
    await screen.findByRole("article", { name: item.headline! });
    expect(getRecall).toHaveBeenCalledExactlyOnceWith("C++ & café #1", 168, [
      "decision",
      "handoff",
    ]);
    expect(setParams).toHaveBeenCalledWith(
      expect.objectContaining({ scope: "C++ & café #1", run: undefined }),
      { replace: true },
    );
    fireEvent.click(screen.getByRole("radio", { name: "30d" }));
    expect(getRecall).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });

  it("runs the project resume URL with a one-year window and blank question", async () => {
    updateParams({ project: "/repos/alpha", withinHours: "8760", run: "1" });
    render(() => <RecallPage />);
    await screen.findByRole("article", { name: item.headline! });
    expect(getRecall).toHaveBeenCalledExactlyOnceWith(
      { project: "/repos/alpha", query: "", includeSuperseded: false },
      8760,
      ["decision", "handoff"],
    );
    expect(screen.getByLabelText("Question")).toHaveValue("");
    expect(screen.getByRole("radio", { name: "1y" })).toBeChecked();
  });

  it("offers real bounded suggestions and keyboard inspection without changing the selected project", async () => {
    vi.mocked(getRecall).mockResolvedValue(
      result(
        Array.from({ length: 7 }, (_, index) => ({
          ...item,
          eventId: `evt-${index}`,
          headline: `Recorded storage ${index}`,
        })),
      ),
    );
    updateParams({ project: "/repos/alpha" });
    render(() => <RecallPage />);
    const input = screen.getByLabelText("Question");
    fireEvent.input(input, { target: { value: "storage" } });
    await screen.findByRole("option", { name: /Recorded storage 0/ });
    expect(screen.getAllByRole("option")).toHaveLength(5);
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    const evidence = screen.getByRole("region", { name: "Selected evidence" });
    expect(
      within(evidence).getByRole("article", { name: "Recorded storage 0" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Question")).toHaveValue("storage");
    expect(getRecall).toHaveBeenCalledExactlyOnceWith(
      { project: "/repos/alpha", query: "storage", includeSuperseded: false },
      168,
      ["decision", "handoff"],
    );
    expect(captureDecision).not.toHaveBeenCalled();
  });

  it("ignores stale full recall and suggestion responses after scope or filter changes", async () => {
    let resolveOld!: (value: RecallResult) => void;
    vi.mocked(getRecall).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    );
    updateParams({ scope: "old", run: "1" });
    render(() => <RecallPage />);
    updateParams({ project: "/repos/beta", scope: undefined, query: "", run: "1" });
    await screen.findByRole("article", { name: item.headline! });
    resolveOld(result([{ ...item, headline: "Stale full query" }]));
    await Promise.resolve();
    expect(screen.queryByText("Stale full query")).not.toBeInTheDocument();
    let resolveSuggestion!: (value: RecallResult) => void;
    vi.mocked(getRecall).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveSuggestion = resolve;
        }),
    );
    fireEvent.input(screen.getByLabelText("Question"), { target: { value: "old suggestion" } });
    await waitFor(() => expect(resolveSuggestion).toBeDefined());
    fireEvent.click(screen.getByRole("checkbox", { name: "Decision" }));
    resolveSuggestion(result([{ ...item, headline: "Stale suggestion" }]));
    await Promise.resolve();
    expect(screen.queryByText("Stale suggestion")).not.toBeInTheDocument();
  });

  it("records a replacement only on explicit form submit, requires rationale, and preserves history links", async () => {
    updateParams({ project: "/repos/alpha", run: "1" });
    render(() => <RecallPage />);
    const card = await screen.findByRole("article", { name: item.headline! });
    fireEvent.click(within(card).getByRole("button", { name: "Replace decision" }));
    fireEvent.input(screen.getByLabelText("New decision"), {
      target: { value: "Use shared PostgreSQL" },
    });
    expect(screen.getByRole("button", { name: "Record replacement" })).toBeDisabled();
    expect(captureDecision).not.toHaveBeenCalled();
    fireEvent.input(screen.getByLabelText("Why this replaces the earlier decision"), {
      target: { value: "Shared deployment now needs remote access." },
    });
    vi.mocked(getRecall).mockResolvedValue(
      result([
        {
          ...item,
          eventId: "evt-new",
          headline: "Use shared PostgreSQL",
          supersedesEventId: "evt-1",
        },
      ]),
    );
    fireEvent.click(screen.getByRole("button", { name: "Record replacement" }));
    await screen.findByRole("article", { name: "Use shared PostgreSQL" });
    expect(captureDecision).toHaveBeenCalledExactlyOnceWith({
      source: "manual",
      clientSessionId: expect.stringMatching(/^blackbox-recall-/),
      repo: "/repos/alpha",
      decision: "Use shared PostgreSQL",
      rationale: "Shared deployment now needs remote access.",
      supersedes: "evt-1",
    });
    expect(await screen.findByRole("link", { name: "earlier decision" })).toHaveAttribute(
      "href",
      "/?view=browse&session=owning-evt-1&event=evt-1&project=",
    );
    vi.mocked(getRecall).mockResolvedValue(result([{ ...item, supersededByEventId: "evt-new" }]));
    fireEvent.click(screen.getByRole("checkbox", { name: "Include replaced decisions" }));
    fireEvent.click(screen.getByRole("button", { name: "Run recall" }));
    const old = await screen.findByRole("article", { name: item.headline! });
    expect(getRecall).toHaveBeenLastCalledWith(
      { project: "/repos/alpha", query: "", includeSuperseded: true },
      168,
      ["decision", "handoff"],
    );
    expect(within(old).queryByRole("button", { name: "Replace decision" })).not.toBeInTheDocument();
    expect(await within(old).findByRole("link", { name: "replacement decision" })).toHaveAttribute(
      "href",
      "/?view=browse&session=owning-evt-new&event=evt-new&project=",
    );
  });

  it.each([false, true])(
    "groups replacement sessions by target repo (switch project filters: %s)",
    async (switchProjects) => {
      const targets = [
        { ...item, eventId: "alpha-one", repo: "/repos/alpha", headline: "Alpha decision one" },
        { ...item, eventId: "beta-one", repo: "/repos/beta", headline: "Beta decision one" },
        { ...item, eventId: "alpha-two", repo: "/repos/alpha", headline: "Alpha decision two" },
      ];
      let remaining = [...targets];
      vi.mocked(getRecall).mockImplementation(async (scope) => {
        const selected = typeof scope === "string" ? undefined : scope.project;
        return result(remaining.filter((candidate) => !selected || candidate.repo === selected));
      });
      vi.mocked(captureDecision).mockImplementation(async (request) => {
        remaining = remaining.filter((candidate) => candidate.eventId !== request.supersedes);
        return {
          eventId: `replacement-${request.supersedes}`,
          sessionId: request.clientSessionId,
          source: request.source,
          clientSessionId: request.clientSessionId,
          eventType: "Decision",
        };
      });
      updateParams({ project: switchProjects ? "/repos/alpha" : undefined, run: "1" });
      render(() => <RecallPage />);
      for (const target of targets) {
        if (switchProjects) updateParams({ project: target.repo, run: "1" });
        const card = await screen.findByRole("article", { name: target.headline });
        fireEvent.click(within(card).getByRole("button", { name: "Replace decision" }));
        fireEvent.input(screen.getByLabelText("New decision"), {
          target: { value: `Replacement for ${target.headline}` },
        });
        fireEvent.input(screen.getByLabelText("Why this replaces the earlier decision"), {
          target: { value: "Updated project evidence" },
        });
        fireEvent.click(screen.getByRole("button", { name: "Record replacement" }));
        await waitFor(() =>
          expect(screen.queryByRole("article", { name: target.headline })).not.toBeInTheDocument(),
        );
      }
      const requests = vi.mocked(captureDecision).mock.calls.map(([request]) => request);
      expect(requests.map((request) => request.repo)).toEqual([
        "/repos/alpha",
        "/repos/beta",
        "/repos/alpha",
      ]);
      expect(requests[0].clientSessionId).toMatch(/^blackbox-recall-/);
      expect(requests[0].clientSessionId).toBe(requests[2].clientSessionId);
      expect(requests[0].clientSessionId).not.toBe(requests[1].clientSessionId);
    },
  );

  it("keeps the replacement draft on a rejected write and reports clipboard failure", async () => {
    updateParams({ run: "1" });
    render(() => <RecallPage />);
    await screen.findByRole("article", { name: item.headline! });
    vi.mocked(captureDecision).mockRejectedValue(new Error("Decision already replaced"));
    fireEvent.click(screen.getByRole("button", { name: "Replace decision" }));
    fireEvent.input(screen.getByLabelText("New decision"), { target: { value: "New decision" } });
    fireEvent.input(screen.getByLabelText("Why this replaces the earlier decision"), {
      target: { value: "New evidence" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Record replacement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Decision already replaced");
    expect(screen.getByLabelText("New decision")).toHaveValue("New decision");
    writeText.mockRejectedValue(new Error("Blocked"));
    fireEvent.click(screen.getByRole("button", { name: "Copy context" }));
    expect(await screen.findByText(/Clipboard unavailable/)).toBeInTheDocument();
  });

  it("does not retain replaced evidence if the write succeeds but refresh fails", async () => {
    updateParams({ project: "/repos/alpha", run: "1" });
    render(() => <RecallPage />);
    await screen.findByRole("article", { name: item.headline! });
    fireEvent.click(screen.getByRole("button", { name: "Replace decision" }));
    fireEvent.input(screen.getByLabelText("New decision"), { target: { value: "New decision" } });
    fireEvent.input(screen.getByLabelText("Why this replaces the earlier decision"), {
      target: { value: "New evidence" },
    });
    vi.mocked(getRecall).mockRejectedValueOnce(new Error("Refresh offline"));
    fireEvent.click(screen.getByRole("button", { name: "Record replacement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Replacement saved, but refresh failed",
    );
    expect(screen.queryByRole("article", { name: item.headline! })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Copy context" })).not.toBeInTheDocument();
  });

  it("runs an empty legacy scope after the router removes the blank URL value", async () => {
    updateParams({ scope: "", run: "1" });
    render(() => <RecallPage />);
    expect(await screen.findByRole("article", { name: item.headline! })).toBeInTheDocument();
    expect(screen.getByLabelText("Question")).toHaveValue("");
  });

  it("clears results on query-only navigation and closes accessible help with Escape", async () => {
    updateParams({ scope: "first", run: "1" });
    render(() => <RecallPage />);
    await screen.findByRole("article", { name: item.headline! });
    updateParams({ scope: "second" });
    expect(screen.getByLabelText("Scope")).toHaveValue("second");
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    for (const label of ["Help with scope", "Help with time windows", "Help with filters"]) {
      const trigger = screen.getByLabelText(label);
      fireEvent.click(trigger);
      expect(trigger.closest("details")).toHaveAttribute("open");
      fireEvent.keyDown(trigger, { key: "Escape" });
      expect(trigger.closest("details")).not.toHaveAttribute("open");
      expect(trigger).toHaveFocus();
    }
  });
});
