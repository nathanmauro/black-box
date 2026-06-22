import { A, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, Show } from "solid-js";
import SourceDot from "../components/SourceDot";
import { EventRenderer } from "../components/events/EventRow";
import { ask, askStatus, search, searchValues, type AgentEvent, type SearchResponse } from "../lib/api";
import { FACET_FIELDS, parseQuery, setFacet, type FacetField } from "../lib/query";
import { timeAgo, truncatePath } from "../lib/format";

const STRUCTURED = new Set(["Decision", "Handoff", "Observation"]);

// UI facet key -> the physical field name the /search/values endpoint understands.
const VALUE_FIELD: Record<FacetField["key"], string> = {
  source: "source",
  kind: "event_type",
  tool: "tool_name",
  project: "cwd",
};

const QUICK_VALUES: Record<FacetField["key"], string[]> = {
  source: ["claude", "codex", "cursor", "raycast", "cockpit", "cli", "manual"],
  kind: ["Decision", "Handoff", "Observation", "UserPromptSubmit", "PostToolUse"],
  tool: [],
  project: [],
};

type Mode = "find" | "ask";

export default function SearchPage() {
  let inputRef: HTMLInputElement | undefined;
  let inputWrapRef: HTMLDivElement | undefined;
  const [params, setParams] = useSearchParams<{ q?: string }>();
  const [draft, setDraft] = createSignal(params.q ?? "");
  const [mode, setMode] = createSignal<Mode>("find");
  const [meaningfulOnly, setMeaningfulOnly] = createSignal(true);
  const [suggestionsOpen, setSuggestionsOpen] = createSignal(false);

  // The URL's q is the source of truth for what was actually searched.
  const submitted = () => params.q ?? "";
  createEffect(() => setDraft(params.q ?? ""));

  const [response] = createResource<SearchResponse, string>(submitted, async (q) =>
    q.trim() ? search(q, 120) : { query: "", local: [], elastic: [], elasticHealth: {} },
  );

  // Ask mode is offered only when the backend reports the dependency is reachable.
  const [askReady] = createResource(async () => {
    try {
      const status = await askStatus();
      return Boolean(status.chat?.available || status.elasticsearch?.available);
    } catch {
      return false;
    }
  });

  const parsed = createMemo(() => parseQuery(submitted()));

  function run(next: string) {
    setParams({ q: next });
  }
  function submit(event: SubmitEvent) {
    event.preventDefault();
    run(draft().trim());
  }
  function applyFacet(key: FacetField["key"], value: string | null) {
    run(setFacet(submitted(), key, value));
  }

  const local = () => response()?.local ?? [];
  const filtered = createMemo(() => {
    const hasKindFacet = Boolean(parsed().facets.kind);
    return local().filter((event) => !(meaningfulOnly() && !hasKindFacet && event.eventType === "PostToolUse"));
  });
  const structured = () => filtered().filter((event) => STRUCTURED.has(event.eventType));
  const others = () => filtered().filter((event) => !STRUCTURED.has(event.eventType));
  const sessionCount = () => new Set(filtered().map((event) => event.sessionId)).size;

  // --- value autocomplete for the token currently being typed -------------------
  const editing = createMemo(() => {
    const tokens = draft().split(/\s+/);
    const last = tokens[tokens.length - 1] ?? "";
    const sep = last.indexOf(":");
    if (sep <= 0) return null;
    const raw = last.slice(0, sep).toLowerCase();
    const field = FACET_FIELDS.find((f) => f.key === raw || (raw === "agent" && f.key === "source"));
    if (!field) return null;
    return { key: field.key, prefix: last.slice(sep + 1) };
  });
  const [suggestions] = createResource(editing, async (edit) =>
    edit ? searchValues(VALUE_FIELD[edit.key], edit.prefix, 8).catch(() => []) : [],
  );
  const showSuggestions = () => suggestionsOpen() && editing() !== null && (suggestions()?.length ?? 0) > 0;

  createEffect(() => {
    if (editing() === null) {
      setSuggestionsOpen(false);
    }
  });

  function dismissSuggestions() {
    setSuggestionsOpen(false);
  }

  function pickSuggestion(value: string) {
    const edit = editing();
    if (!edit) return;
    const tokens = draft().split(/\s+/);
    tokens[tokens.length - 1] = `${edit.key}:${/\s/.test(value) ? `"${value}"` : value}`;
    setDraft(tokens.join(" ") + " ");
    dismissSuggestions();
    inputRef?.focus();
  }

  function handleDocumentPointerDown(event: PointerEvent) {
    if (!inputWrapRef?.contains(event.target as Node)) {
      dismissSuggestions();
    }
  }

  document.addEventListener("pointerdown", handleDocumentPointerDown);
  onCleanup(() => document.removeEventListener("pointerdown", handleDocumentPointerDown));

  return (
    <section class="page page--search">
      <div class="search-head">
        <div class="mode-tabs">
          <button type="button" classList={{ "mode-tab": true, "mode-tab--active": mode() === "find" }}
            onClick={() => setMode("find")}>Find</button>
          <Show when={askReady()}>
            <button type="button" classList={{ "mode-tab": true, "mode-tab--active": mode() === "ask" }}
              onClick={() => setMode("ask")}>Ask</button>
          </Show>
        </div>

        <Show when={mode() === "find"} fallback={<AskPanel />}>
          <form class="search-form" onSubmit={submit} autocomplete="off">
            <div ref={inputWrapRef} class="search-input-wrap">
              <input
                ref={inputRef}
                class="search-input"
                value={draft()}
                onInput={(event) => {
                  setDraft(event.currentTarget.value);
                  setSuggestionsOpen(true);
                }}
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    dismissSuggestions();
                  }
                }}
                placeholder="source:codex kind:Decision recall bug"
                aria-label="Search query"
              />
              <button type="submit">Search</button>
              <Show when={showSuggestions()}>
                <ul class="suggest-popover" role="listbox">
                  <For each={suggestions()}>
                    {(value) => (
                      <li>
                        <button type="button" onClick={() => pickSuggestion(value)}>{value}</button>
                      </li>
                    )}
                  </For>
                </ul>
              </Show>
            </div>
          </form>

          <div class="facet-rail">
            <For each={FACET_FIELDS}>
              {(field) => (
                <div class="facet-group">
                  <span class="facet-label">{field.label}</span>
                  <Show
                    when={parsed().facets[field.key]}
                    fallback={
                      <div class="facet-quick">
                        <For each={QUICK_VALUES[field.key]}>
                          {(value) => (
                            <button type="button" class="facet-chip" onClick={() => applyFacet(field.key, value)}>{value}</button>
                          )}
                        </For>
                        <Show when={QUICK_VALUES[field.key].length === 0}>
                          <span class="facet-hint">type {field.key}:…</span>
                        </Show>
                      </div>
                    }
                  >
                    <button type="button" class="facet-chip facet-chip--active" onClick={() => applyFacet(field.key, null)}>
                      {parsed().facets[field.key]} ✕
                    </button>
                  </Show>
                </div>
              )}
            </For>
            <label class="meaningful-toggle">
              <input type="checkbox" checked={meaningfulOnly()} onChange={(e) => setMeaningfulOnly(e.currentTarget.checked)} />
              meaningful events only
            </label>
          </div>
        </Show>
      </div>

      <Show when={mode() === "find"}>
        <Show
          when={submitted().trim()}
          fallback={<p class="empty-state">Search the recorded memory — try <code>source:codex kind:Decision</code> or a phrase.</p>}
        >
          <Show when={!response.loading} fallback={<p class="empty-state">Searching…</p>}>
            <p class="result-summary">
              {filtered().length.toLocaleString()} events · {sessionCount().toLocaleString()} sessions
              <Show when={response()?.elasticHealth?.available}> · elastic</Show>
            </p>

            <Show when={structured().length}>
              <div class="result-group">
                <h2 class="result-group-title">Decisions &amp; handoffs</h2>
                <For each={structured()}>{(event) => <ResultRow event={event} />}</For>
              </div>
            </Show>

            <Show when={others().length}>
              <div class="result-group">
                <h2 class="result-group-title">Events</h2>
                <For each={others()}>{(event) => <ResultRow event={event} />}</For>
              </div>
            </Show>

            <Show when={!filtered().length}>
              <p class="empty-state">No results. Remove a facet or turn off “meaningful events only”.</p>
            </Show>
          </Show>
        </Show>
      </Show>
    </section>
  );
}

function ResultRow(props: { event: AgentEvent }) {
  return (
    <A href={`/sessions/${encodeURIComponent(props.event.sessionId)}`} class="result-row">
      <div class="result-row-meta">
        <SourceDot source={props.event.source} label />
        <span class="result-row-time">{timeAgo(props.event.observedAt)}</span>
      </div>
      <div class="result-row-body">
        <EventRenderer event={props.event} />
      </div>
    </A>
  );
}

function AskPanel() {
  const [question, setQuestion] = createSignal("");
  const [asked, setAsked] = createSignal("");
  const [answer] = createResource(asked, async (q) => (q.trim() ? ask(q) : null));
  return (
    <form
      class="ask-form"
      onSubmit={(event) => {
        event.preventDefault();
        setAsked(question().trim());
      }}
    >
      <textarea
        class="ask-input"
        rows="3"
        value={question()}
        onInput={(event) => setQuestion(event.currentTarget.value)}
        placeholder="Ask across the recorded memory…"
      />
      <button type="submit">Ask</button>
      <Show when={answer.loading}><p class="empty-state">Thinking…</p></Show>
      <Show when={answer()}>
        {(result) => (
          <div class="ask-answer">
            <p>{result().answer}</p>
            <Show when={result().citations?.length}>
              <ol class="ask-citations">
                <For each={result().citations}>
                  {(cite) => (
                    <li>
                      <Show when={cite.sessionId} fallback={<span>{cite.title || cite.snippet}</span>}>
                        <A href={`/sessions/${encodeURIComponent(cite.sessionId!)}`}>{cite.title || truncatePath(cite.sourcePath || "")}</A>
                      </Show>
                    </li>
                  )}
                </For>
              </ol>
            </Show>
          </div>
        )}
      </Show>
    </form>
  );
}
