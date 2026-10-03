import { ReaderText } from "../components/events/EventRow";
import HandoffContext from "../components/events/HandoffContext";
import { A, useSearchParams } from "@solidjs/router";
import {
  createEffect,
  createMemo,
  createResource,
  createSignal,
  For,
  onCleanup,
  Show,
  untrack,
  type JSX,
} from "solid-js";
import KindBadge from "../components/KindBadge";
import ProjectPicker from "../components/ProjectPicker";
import SourceDot from "../components/SourceDot";
import {
  captureDecision,
  getEvent,
  getProjects,
  getRecall,
  type RecalledItem,
  type RecallResult,
} from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { findProjectByIdentifier, primaryProjectScope } from "../lib/projects";
import { buildRecallBriefing, newestRecorded, recalledItemHref } from "../lib/recall";
import { sourceFilter } from "../lib/stores";

const RECALL_KINDS = ["decision", "handoff", "observation", "idea"] as const;
const TIME_WINDOWS = [
  { label: "24h", value: 24 },
  { label: "1w", value: 168 },
  { label: "30d", value: 720 },
  { label: "Three months · 90d", value: 2160 },
  { label: "Six months · 180d", value: 4320 },
  { label: "1y", value: 8760 },
];
type RecallParams = {
  scope?: string;
  project?: string;
  query?: string;
  run?: string;
  withinHours?: string;
  history?: string;
  kinds?: string;
};

export default function RecallPage() {
  const [params, setParams] = useSearchParams<RecallParams>();
  const [projects] = createResource(getProjects, { initialValue: [] });
  const [legacy, setLegacy] = createSignal(params.scope != null);
  const [project, setProject] = createSignal(params.project || "");
  const [query, setQuery] = createSignal(params.scope ?? params.query ?? "");
  const [withinHours, setWithinHours] = createSignal(routeWindow(params.withinHours));
  const [kinds, setKinds] = createSignal<string[]>(routeKinds(params.kinds));
  const [includeSuperseded, setIncludeSuperseded] = createSignal(params.history === "1");
  const [result, setResult] = createSignal<RecallResult | null>(null);
  const [error, setError] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(false);
  const [suggestions, setSuggestions] = createSignal<RecalledItem[]>([]);
  const [suggestionStatus, setSuggestionStatus] = createSignal("");
  const [suggestionsOpen, setSuggestionsOpen] = createSignal(false);
  const [activeSuggestion, setActiveSuggestion] = createSignal(-1);
  const [selectedEvidence, setSelectedEvidence] = createSignal<RecalledItem | null>(null);
  const [copyStatus, setCopyStatus] = createSignal("");
  // Backend sessions have one project scope. Keep writes stable within a target repo, not
  // across every project visited in this page (including the All projects view).
  const replacementSessions = new Map<string, string>();
  let requestToken = 0;
  let suggestionToken = 0;
  const filteredItems = createMemo(() => sourceFilter.matches(result()?.items || []));
  const groupedItems = createMemo(() => groupByKind(filteredItems()));
  const latestHandoff = createMemo(() => newestRecorded(filteredItems(), "handoff"));
  const recordedQuestions = createMemo(() =>
    filteredItems()
      .filter((item) => !item.supersededByEventId)
      .flatMap((item) => (item.openLoops || []).map((text) => ({ text, item })))
      .slice(0, 5),
  );

  function invalidate() {
    requestToken += 1;
    suggestionToken += 1;
    setResult(null);
    setError(null);
    setLoading(false);
    setCopyStatus("");
    setSuggestions([]);
    setSelectedEvidence(null);
    setActiveSuggestion(-1);
  }
  function changeFilter(action: () => void) {
    invalidate();
    action();
  }
  function recallSnapshot() {
    return {
      legacy: legacy(),
      project: project(),
      query: query().trim(),
      withinHours: withinHours(),
      kinds: [...kinds()],
      history: includeSuperseded(),
    };
  }
  function fetchRecall(snapshot: ReturnType<typeof recallSnapshot>) {
    if (snapshot.legacy)
      return snapshot.history
        ? getRecall(snapshot.query, snapshot.withinHours, snapshot.kinds, true)
        : getRecall(snapshot.query, snapshot.withinHours, snapshot.kinds);
    return getRecall(
      {
        project: snapshot.project || undefined,
        query: snapshot.query,
        includeSuperseded: snapshot.history,
      },
      snapshot.withinHours,
      snapshot.kinds,
    );
  }
  createEffect(() => {
    const next = {
      legacy: params.scope != null,
      project: params.project || "",
      query: params.scope ?? params.query ?? "",
      withinHours: routeWindow(params.withinHours),
      kinds: routeKinds(params.kinds),
      history: params.history === "1",
    };
    const shouldRun = params.run === "1";
    untrack(() => {
      if (JSON.stringify(next) !== JSON.stringify(recallSnapshot())) {
        invalidate();
        setLegacy(next.legacy);
        setProject(next.project);
        setQuery(next.query);
        setWithinHours(next.withinHours);
        setKinds(next.kinds);
        setIncludeSuperseded(next.history);
      }
      // Consume launcher intent once. Editing controls never launches a full recall request.
      if (shouldRun) void runRecall(true);
    });
  });
  createEffect(() => {
    const snapshot = recallSnapshot();
    const open = suggestionsOpen();
    sourceFilter.key();
    const token = ++suggestionToken;
    setSuggestions([]);
    setActiveSuggestion(-1);
    setSuggestionStatus("");
    if (!open || snapshot.query.length < 2 || !snapshot.kinds.length) return;
    setSuggestionStatus("Looking for recorded captures…");
    const timer = setTimeout(() => {
      void fetchRecall(snapshot)
        .then((response) => {
          if (token !== suggestionToken) return;
          const items = sourceFilter.matches(response.items).slice(0, 5);
          setSuggestions(items);
          setSuggestionStatus(
            items.length
              ? "Recorded captures · select to inspect evidence"
              : "No recorded suggestions match these filters. You can still run recall.",
          );
        })
        .catch(() => {
          if (token === suggestionToken)
            setSuggestionStatus("Suggestions unavailable. Run recall to try again.");
        });
    }, 300);
    onCleanup(() => clearTimeout(timer));
  });
  onCleanup(() => {
    requestToken += 1;
    suggestionToken += 1;
  });

  async function runRecall(fromLink = false) {
    const snapshot = recallSnapshot();
    if (!snapshot.kinds.length) return;
    // The router removes empty values; normalize before syncing the URL so it cannot cancel this request.
    if (snapshot.legacy && !snapshot.query) {
      snapshot.legacy = false;
      setLegacy(false);
    }
    const token = ++requestToken;
    setQuery(snapshot.query);
    setLoading(true);
    setError(null);
    setCopyStatus("");
    setSuggestionsOpen(false);
    setSelectedEvidence(null);
    setParams(
      {
        scope: snapshot.legacy ? snapshot.query || "" : undefined,
        project: snapshot.legacy ? undefined : snapshot.project || undefined,
        query: snapshot.legacy ? undefined : snapshot.query || undefined,
        run: undefined,
        withinHours: String(snapshot.withinHours),
        kinds: snapshot.kinds.join(","),
        history: snapshot.history ? "1" : undefined,
      },
      { replace: fromLink },
    );
    try {
      const response = await fetchRecall(snapshot);
      if (token === requestToken) setResult(response);
    } catch (err) {
      if (token === requestToken) setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (token === requestToken) setLoading(false);
    }
  }
  function chooseSuggestion(item: RecalledItem) {
    setSelectedEvidence(item);
    setSuggestionsOpen(false);
  }
  function suggestionKeys(event: KeyboardEvent) {
    if (event.key === "Escape") {
      setSuggestionsOpen(false);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setSuggestionsOpen(true);
      setActiveSuggestion((current) =>
        Math.max(
          0,
          Math.min(suggestions().length - 1, current + (event.key === "ArrowDown" ? 1 : -1)),
        ),
      );
    } else if (event.key === "Enter" && suggestionsOpen() && activeSuggestion() >= 0) {
      const item = suggestions()[activeSuggestion()];
      if (item) {
        event.preventDefault();
        chooseSuggestion(item);
      }
    }
  }
  async function copyBriefing() {
    try {
      await navigator.clipboard.writeText(
        buildRecallBriefing(filteredItems(), {
          project: project() || (legacy() ? `Legacy scope: ${query()}` : undefined),
          query: query(),
          withinHours: withinHours(),
          origin: window.location.origin,
        }),
      );
      setCopyStatus("Context copied with source links.");
    } catch {
      setCopyStatus("Clipboard unavailable. Allow clipboard access and try again.");
    }
  }
  async function replaceDecision(item: RecalledItem, decision: string, rationale: string) {
    const before = JSON.stringify(recallSnapshot());
    const repo = item.repo!;
    let clientSessionId = replacementSessions.get(repo);
    if (!clientSessionId) {
      clientSessionId = `blackbox-recall-${crypto.randomUUID()}`;
      replacementSessions.set(repo, clientSessionId);
    }
    await captureDecision({
      source: "manual",
      clientSessionId,
      repo,
      decision,
      rationale,
      supersedes: item.eventId,
    });
    if (before === JSON.stringify(recallSnapshot())) {
      // A successful write invalidates the old decision immediately, even if the refresh fails.
      invalidate();
      await runRecall();
      if (error()) setError(`Replacement saved, but refresh failed: ${error()}`);
    }
  }

  return (
    <section class="page recall-page">
      <header class="recall-hero">
        <div>
          <p class="eyebrow">structured recall</p>
          <h1>Pick up where you left off</h1>
          <p>Choose a project, ask what you half remember, and follow the recorded evidence.</p>
        </div>
      </header>
      <form
        class="recall-form recall-form--continuity"
        onSubmit={(event) => {
          event.preventDefault();
          void runRecall();
        }}
      >
        <div class="recall-project-row">
          <ProjectPicker
            projects={projects.error ? [] : projects()}
            selectedProjectKey={project() || undefined}
            loading={projects.loading}
            error={projects.error ? "Project catalog unavailable." : null}
            allDescription="Recent intent across projects"
            onSelect={(key) =>
              changeFilter(() => {
                const selected = findProjectByIdentifier(projects(), key);
                setProject(selected ? primaryProjectScope(selected).canonicalKey : key || "");
                setLegacy(false);
              })
            }
          />
          <Show when={legacy()}>
            <p class="recall-hint">
              Legacy scope link · paths and text match recorded content. Select a project for an
              exact project filter.
            </p>
          </Show>
          <Show
            when={project() && !projects.loading && !findProjectByIdentifier(projects(), project())}
          >
            <p class="recall-hint">
              Selected project: {project()}. It is unavailable in the catalog; recall keeps this
              scope.
            </p>
          </Show>
        </div>
        <div class="recall-field recall-question">
          <div class="recall-control-heading">
            <label for="recall-question">{legacy() ? "Scope" : "Question"}</label>
            <RecallHelp label="Help with scope">
              <p>
                Select a project to keep all results inside its registered scopes. Leave the
                question blank for recent recorded context.
              </p>
              <p>
                A topic or paraphrase uses semantic retrieval when available and text matching
                otherwise. Suggestions always come from actual captures.
              </p>
            </RecallHelp>
          </div>
          <input
            id="recall-question"
            value={query()}
            placeholder="Why did we choose this approach?"
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={suggestionsOpen() && suggestions().length > 0}
            aria-controls="recall-suggestions"
            aria-activedescendant={
              suggestionsOpen() && activeSuggestion() >= 0
                ? `recall-suggestion-${activeSuggestion()}`
                : undefined
            }
            autocomplete="off"
            onFocus={() => setSuggestionsOpen(true)}
            onKeyDown={suggestionKeys}
            onInput={(event) =>
              changeFilter(() => {
                setQuery(event.currentTarget.value);
                setSuggestionsOpen(true);
              })
            }
          />
          <Show when={suggestionsOpen() && suggestionStatus()}>
            <div class="recall-suggestions">
              <p role="status">{suggestionStatus()}</p>
              <ul id="recall-suggestions" role="listbox" aria-label="Recorded suggestions">
                <For each={suggestions()}>
                  {(item, index) => (
                    <li>
                      <button
                        type="button"
                        role="option"
                        id={`recall-suggestion-${index()}`}
                        aria-selected={activeSuggestion() === index()}
                        onClick={() => chooseSuggestion(item)}
                      >
                        <strong>{item.headline || titleKind(item.kind)}</strong>
                        <span>
                          {titleKind(item.kind)} · {item.source} ·{" "}
                          {item.observedAt || "time unavailable"}
                        </span>
                        <small>{item.repo || "No project recorded"}</small>
                      </button>
                    </li>
                  )}
                </For>
              </ul>
            </div>
          </Show>
        </div>
        <div class="recall-secondary-controls">
          <fieldset class="recall-window">
            <legend>Window</legend>
            <For each={TIME_WINDOWS}>
              {(option) => (
                <label
                  classList={{
                    "segmented-option": true,
                    "segmented-option--active": withinHours() === option.value,
                  }}
                >
                  <input
                    type="radio"
                    name="withinHours"
                    checked={withinHours() === option.value}
                    onChange={() => changeFilter(() => setWithinHours(option.value))}
                  />
                  <span>{option.label}</span>
                </label>
              )}
            </For>
            <RecallHelp label="Help with time windows">
              <p>
                Rolling windows use each capture’s observed time: 90, 180, or 365 days. Run recall
                after changing the window. Retrieval is bounded; a wider window does not return
                every capture.
              </p>
            </RecallHelp>
          </fieldset>
          <fieldset class="recall-kinds">
            <legend>Kinds</legend>
            <For each={RECALL_KINDS}>
              {(kind) => (
                <label class="check-chip">
                  <input
                    type="checkbox"
                    checked={kinds().includes(kind)}
                    onChange={() =>
                      changeFilter(() =>
                        setKinds((current) =>
                          current.includes(kind)
                            ? current.filter((value) => value !== kind)
                            : [...current, kind],
                        ),
                      )
                    }
                  />
                  <span>{titleKind(kind)}</span>
                </label>
              )}
            </For>
            <RecallHelp label="Help with filters">
              <p>
                Choose decisions, handoffs, observations, or ideas. Keep at least one kind selected.
                The source filter in the top bar can hide captures returned by recall.
              </p>
            </RecallHelp>
          </fieldset>
          <label class="check-chip recall-history">
            <input
              type="checkbox"
              checked={includeSuperseded()}
              onChange={(event) =>
                changeFilter(() => setIncludeSuperseded(event.currentTarget.checked))
              }
            />
            <span>Include replaced decisions</span>
          </label>
          <button type="submit" class="primary-action" disabled={loading() || !kinds().length}>
            {loading() ? "Running..." : "Run recall"}
          </button>
        </div>
      </form>
      <Show when={selectedEvidence()}>
        {(item) => (
          <section class="recall-evidence" aria-label="Selected evidence">
            <div class="recall-briefing-head">
              <h2>Recorded evidence</h2>
              <button
                type="button"
                class="secondary-action"
                onClick={() => setSelectedEvidence(null)}
              >
                Close evidence
              </button>
            </div>
            <RecallCard item={item()} onReplace={replaceDecision} />
          </section>
        )}
      </Show>
      <Show when={error()}>
        {(message) => (
          <p class="inline-error" role="alert">
            Recall failed: {message()}
          </p>
        )}
      </Show>
      <section class="recall-results" aria-live="polite">
        <Show
          when={result()}
          fallback={
            <div class="recall-empty">
              <p class="eyebrow">ready</p>
              <h2>Run a recall query</h2>
              <p>
                Choose a project and leave the question blank to resume from its latest recorded
                context.
              </p>
            </div>
          }
        >
          {(resolved) => (
            <>
              <div class="recall-summary">
                <span>{filteredItems().length.toLocaleString()} visible</span>
                <span>{resolved().count.toLocaleString()} returned</span>
                <span>{resolved().withinHours.toLocaleString()}h</span>
                <Show when={resolved().mode === "lexical"}>
                  <span>Text matching · semantic retrieval unavailable or not used</span>
                </Show>
              </div>
              <Show
                when={filteredItems().length}
                fallback={
                  <p class="empty-state">
                    No recall items match this project, question, and source filter.
                  </p>
                }
              >
                <section class="recall-briefing" aria-labelledby="recall-briefing-title">
                  <div class="recall-briefing-head">
                    <h2 id="recall-briefing-title">Latest recorded context</h2>
                    <button
                      type="button"
                      class="secondary-action"
                      onClick={() => void copyBriefing()}
                    >
                      Copy context
                    </button>
                  </div>
                  <p class="recall-hint">
                    A bounded view of retrieved evidence. Recency does not establish current truth;
                    recorded questions may already be resolved.
                  </p>
                  <Show when={latestHandoff()}>
                    {(item) => (
                      <p>
                        <strong>Latest retrieved handoff: </strong>
                        <A href={recalledItemHref(item())}>
                          {item().headline || "Open handoff"}
                        </A>{" "}
                        <time datetime={item().observedAt || undefined}>{item().observedAt}</time>
                      </p>
                    )}
                  </Show>
                  <Show when={recordedQuestions().length}>
                    <h3>Recorded open questions</h3>
                    <ul>
                      <For each={recordedQuestions()}>
                        {(entry) => (
                          <li>
                            {entry.text} <A href={recalledItemHref(entry.item)}>Source</A>
                          </li>
                        )}
                      </For>
                    </ul>
                  </Show>
                  <p role="status">{copyStatus()}</p>
                </section>
                <For each={groupedItems()}>
                  {(group) => (
                    <section class="recall-group">
                      <h2>
                        <KindBadge kind={titleKind(group.kind)} />
                        <span>{group.items.length.toLocaleString()}</span>
                      </h2>
                      <div class="recall-card-stack">
                        <For each={group.items}>
                          {(item) => <RecallCard item={item} onReplace={replaceDecision} />}
                        </For>
                      </div>
                    </section>
                  )}
                </For>
              </Show>
            </>
          )}
        </Show>
      </section>
    </section>
  );
}

function routeWindow(value?: string) {
  return TIME_WINDOWS.some((option) => option.value === Number(value)) ? Number(value) : 168;
}
function routeKinds(value?: string): string[] {
  return value == null
    ? ["decision", "handoff"]
    : value
        .split(",")
        .filter((kind) => RECALL_KINDS.includes(kind as (typeof RECALL_KINDS)[number]));
}

function RecallHelp(props: { label: string; children: JSX.Element }) {
  let trigger!: HTMLElement;
  return (
    <details
      class="recall-help"
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.currentTarget.open = false;
        trigger.focus();
        event.preventDefault();
      }}
    >
      <summary ref={trigger} aria-label={props.label}>
        ?
      </summary>
      <div class="recall-help-body">{props.children}</div>
    </details>
  );
}

function RecallCard(props: {
  item: RecalledItem;
  onReplace: (item: RecalledItem, decision: string, rationale: string) => Promise<void>;
}) {
  const [replacing, setReplacing] = createSignal(false);
  const [decision, setDecision] = createSignal("");
  const [rationale, setRationale] = createSignal("");
  const [busy, setBusy] = createSignal(false);
  const [failure, setFailure] = createSignal("");
  async function submitReplacement(event: SubmitEvent) {
    event.preventDefault();
    if (busy() || !decision().trim() || !rationale().trim() || !props.item.repo) return;
    setBusy(true);
    setFailure("");
    try {
      await props.onReplace(props.item, decision().trim(), rationale().trim());
      setReplacing(false);
    } catch (error) {
      setFailure(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }
  const confidence = () => clampConfidence(props.item.confidence);
  const alternatives = () => props.item.alternatives || [];
  const openLoops = () => props.item.openLoops || [];

  return (
    <article
      class={`recall-card recall-card--${props.item.kind.toLowerCase()}`}
      aria-label={props.item.headline || titleKind(props.item.kind)}
    >
      <A
        class="recall-card-head recall-card-link"
        href={recalledItemHref(props.item)}
        aria-label={`Open ${props.item.headline || titleKind(props.item.kind)} in Browse`}
      >
        <SourceDot source={props.item.source} />
        <KindBadge kind={titleKind(props.item.kind)} />
        <strong>{props.item.headline || titleKind(props.item.kind)}</strong>
        <span title={props.item.observedAt || undefined}>{timeAgo(props.item.observedAt)}</span>
      </A>
      <div class="recall-card-meta">
        <span>{truncatePath(props.item.repo)}</span>
        {props.item.clientSessionId ? <span>{props.item.clientSessionId}</span> : null}
        {props.item.toAgent ? <span>to {props.item.toAgent}</span> : null}
      </div>
      <Show when={props.item.supersededByEventId}>
        {(id) => (
          <p class="recall-relation">
            Replaced by <RelatedDecisionLink eventId={id()} label="replacement decision" />.
            Preserved as historical evidence.
          </p>
        )}
      </Show>
      <Show when={props.item.supersedesEventId}>
        {(id) => (
          <p class="recall-relation">
            Replaces <RelatedDecisionLink eventId={id()} label="earlier decision" />.
          </p>
        )}
      </Show>
      <Show when={props.item.kind.toLowerCase() === "handoff"}>
        <HandoffContext text={props.item.headline} label="Read recalled context">
          <A href={recalledItemHref(props.item)}>Open full handoff in Browse</A>
        </HandoffContext>
      </Show>
      <Show when={props.item.body}>{(body) => <ReaderText text={body()} />}</Show>
      <Show when={props.item.rationale}>
        {(rationale) => <p class="recall-rationale">{rationale()}</p>}
      </Show>
      <Show when={props.item.kind.toLowerCase() === "idea"}>
        {/* RecalledItem carries only the idea title (headline) and one-liner (rationale); origin,
            status, and legs live on the Ideas view, so link there instead. */}
        <div class="idea-badges">
          <A
            class="idea-open-link"
            href={`/ideas?q=${encodeURIComponent(props.item.headline || "")}`}
          >
            Open in Ideas
          </A>
        </div>
      </Show>
      <Show when={props.item.confidence != null}>
        <div class="confidence-row recall-confidence">
          <span>confidence</span>
          <meter min="0" max="1" value={confidence()}>
            {confidence()}
          </meter>
          <span>{Math.round(confidence() * 100)}%</span>
        </div>
      </Show>
      <RecallList title="alternatives" items={alternatives()} />
      <RecallList title="open loops" items={openLoops()} />
      <Show when={props.item.nextAction}>
        {(nextAction) => (
          <p class="recall-next">
            <span>next</span> {nextAction()}
          </p>
        )}
      </Show>
      <Show
        when={
          props.item.kind.toLowerCase() === "decision" &&
          props.item.repo &&
          !props.item.supersededByEventId
        }
      >
        <Show
          when={replacing()}
          fallback={
            <button
              type="button"
              class="secondary-action recall-replace-trigger"
              onClick={() => setReplacing(true)}
            >
              Replace decision
            </button>
          }
        >
          <form
            class="recall-replacement"
            aria-label="Replace recorded decision"
            onSubmit={(event) => void submitReplacement(event)}
          >
            <p>
              Record a new decision and explain why it replaces this one. The original evidence
              stays available in history.
            </p>
            <label>
              New decision
              <textarea
                required
                maxlength="12000"
                value={decision()}
                onInput={(event) => setDecision(event.currentTarget.value)}
              />
            </label>
            <label>
              Why this replaces the earlier decision
              <textarea
                required
                maxlength="12000"
                value={rationale()}
                onInput={(event) => setRationale(event.currentTarget.value)}
              />
            </label>
            <Show when={failure()}>
              <p class="inline-error" role="alert">
                Replacement failed: {failure()}
              </p>
            </Show>
            <div>
              <button
                type="submit"
                class="primary-action"
                disabled={busy() || !decision().trim() || !rationale().trim()}
              >
                {busy() ? "Recording…" : "Record replacement"}
              </button>
              <button
                type="button"
                class="secondary-action"
                disabled={busy()}
                onClick={() => setReplacing(false)}
              >
                Cancel
              </button>
            </div>
          </form>
        </Show>
      </Show>
    </article>
  );
}

function RecallList(props: { title: string; items: string[] }) {
  if (!props.items.length) return null;
  return (
    <div class="metadata-list recall-list">
      <span>{props.title}</span>
      <ul>
        <For each={props.items}>{(item) => <li>{item}</li>}</For>
      </ul>
    </div>
  );
}

function groupByKind(items: RecalledItem[]) {
  const groups = new Map<string, RecalledItem[]>();
  for (const item of items) {
    const kind = item.kind || "event";
    groups.set(kind, [...(groups.get(kind) || []), item]);
  }
  return [...groups.entries()].map(([kind, groupItems]) => ({ kind, items: groupItems }));
}

function titleKind(kind: string): string {
  const normalized = kind.toLowerCase();
  if (normalized === "decision") return "Decision";
  if (normalized === "handoff") return "Handoff";
  if (normalized === "observation") return "Observation";
  if (normalized === "idea") return "Idea";
  return kind.charAt(0).toUpperCase() + kind.slice(1);
}

function clampConfidence(value: number | null | undefined): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, Number(value)));
}

function RelatedDecisionLink(props: { eventId: string; label: string }) {
  const [event] = createResource(() => props.eventId, getEvent);
  return (
    <Show
      when={event.error ? undefined : event()}
      fallback={
        <span>{event.error ? `Source unavailable (${props.eventId})` : "Loading source…"}</span>
      }
    >
      {(source) => (
        <A href={recalledItemHref({ eventId: props.eventId, sessionId: source().sessionId })}>
          {props.label}
        </A>
      )}
    </Show>
  );
}
