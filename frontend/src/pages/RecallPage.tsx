import HandoffContext from "../components/events/HandoffContext";
import { A, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createSignal, For, Show, untrack, type JSX } from "solid-js";
import KindBadge from "../components/KindBadge";
import SourceDot from "../components/SourceDot";
import { getRecall, type RecalledItem, type RecallResult } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import { sourceFilter } from "../lib/stores";

const RECALL_KINDS = ["decision", "handoff", "observation"] as const;
const TIME_WINDOWS = [
  { label: "24h", value: 24 },
  { label: "1w", value: 168 },
  { label: "30d", value: 720 },
  { label: "Three months · 90d", value: 2160 },
  { label: "Six months · 180d", value: 4320 },
];

export default function RecallPage() {
  const [params, setParams] = useSearchParams<{ scope?: string }>();
  let requestToken = 0;
  const [scope, setScope] = createSignal(params.scope ?? "");
  const [withinHours, setWithinHours] = createSignal(168);
  const [kinds, setKinds] = createSignal<string[]>(["decision", "handoff"]);
  const [result, setResult] = createSignal<RecallResult | null>(null);
  const [error, setError] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(false);
  const filteredItems = createMemo(() => sourceFilter.matches(result()?.items || []));
  const groupedItems = createMemo(() => groupByKind(filteredItems()));

  createEffect(() => {
    const routeScope = params.scope ?? "";
    if (routeScope === untrack(scope)) return;
    requestToken += 1;
    setScope(routeScope);
    setResult(null);
    setError(null);
    setLoading(false);
  });

  async function runRecall() {
    const resolvedScope = scope().trim();
    const token = ++requestToken;
    if (scope() !== resolvedScope) setScope(resolvedScope);
    setLoading(true);
    setError(null);
    setParams({ scope: resolvedScope || undefined });
    try {
      const nextResult = await getRecall(resolvedScope, withinHours(), kinds());
      if (token === requestToken) setResult(nextResult);
    } catch (err) {
      if (token === requestToken) setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (token === requestToken) setLoading(false);
    }
  }

  function toggleKind(kind: string) {
    setKinds((current) => {
      if (current.includes(kind)) return current.filter((item) => item !== kind);
      return [...current, kind];
    });
  }

  return (
    <section class="page recall-page">
      <header class="recall-hero">
        <div>
          <p class="eyebrow">structured recall</p>
          <h1>Ask what agents already decided</h1>
          <p>
            Query Black Box for decisions, handoffs, and observations without digging through raw
            transcripts.
          </p>
        </div>
      </header>

      <form
        class="recall-form"
        onSubmit={(event) => {
          event.preventDefault();
          void runRecall();
        }}
      >
        <div class="recall-field">
          <div class="recall-control-heading">
            <label for="recall-scope">Scope</label>
            <RecallHelp label="Help with scope">
              <p>
                <strong>Start with a place or a subject.</strong>
              </p>
              <ul>
                <li>
                  <code>/workspace/example-app</code> finds matching paths or captured text.
                </li>
                <li>
                  <code>example-app</code> can match a repo name or a mention in the text.
                </li>
                <li>
                  <code>recover after a failed deploy</code> tries a topic or paraphrase. Semantic
                  matching needs an available model; otherwise use words from the captured text.
                  Write topics without slashes.
                </li>
              </ul>
              <p>
                Leave Scope blank for recent intent across repos. A pasted event ID can match
                recorded intent too; the window and kinds still apply.
              </p>
              <p>
                Use one scope at a time. A path is a text match, not an exact project filter;
                combining a repo with a separate topic is not supported.
              </p>
            </RecallHelp>
          </div>
          <input
            id="recall-scope"
            value={scope()}
            onInput={(event) => setScope(event.currentTarget.value)}
            placeholder="/workspace/example-app or a topic"
          />
        </div>
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
                  onChange={() => setWithinHours(option.value)}
                />
                <span>{option.label}</span>
              </label>
            )}
          </For>
          <RecallHelp label="Help with time windows">
            <p>
              <strong>Look back from now.</strong> Windows use each capture's observed time.
            </p>
            <p>
              Three months means <strong>90 days</strong>; six months means{" "}
              <strong>180 days</strong>, rather than calendar months.
            </p>
            <p>
              For example, choose 90 days to revisit work from two months ago. Run recall after
              changing the window. A wider window still returns up to 10 results, not every capture.
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
                  onChange={() => toggleKind(kind)}
                />
                <span>{titleKind(kind)}</span>
              </label>
            )}
          </For>
          <RecallHelp label="Help with filters">
            <p>
              <strong>Choose the intent you need.</strong>
            </p>
            <ul>
              <li>
                <strong>Decision:</strong> choices and their reasoning.
              </li>
              <li>
                <strong>Handoff:</strong> where work stands and what comes next.
              </li>
              <li>
                <strong>Observation:</strong> recorded facts or notes.
              </li>
            </ul>
            <p>
              For example, add Observation when looking for a recorded failure. Keep at least one
              kind selected, then run recall.
            </p>
            <p>
              The source filter in the top bar can hide returned items by client. Check it if the
              visible count is lower than the returned count.
            </p>
          </RecallHelp>
        </fieldset>
        <button type="submit" class="primary-action" disabled={loading() || kinds().length === 0}>
          {loading() ? "Running..." : "Run recall"}
        </button>
      </form>

      <Show when={error()}>
        {(message) => <p class="inline-error">Recall failed: {message()}</p>}
      </Show>

      <section class="recall-results" aria-live="polite">
        <Show
          when={result()}
          fallback={
            <div class="recall-empty">
              <p class="eyebrow">ready</p>
              <h2>Run a recall query</h2>
              <p>
                Default kind filters start with decisions and handoffs, the highest-signal handoff
                surface.
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
              </div>
              <Show
                when={filteredItems().length}
                fallback={
                  <p class="empty-state">No recall items match this scope and source filter.</p>
                }
              >
                <For each={groupedItems()}>
                  {(group) => (
                    <section class="recall-group">
                      <h2>
                        <KindBadge kind={titleKind(group.kind)} />
                        <span>{group.items.length.toLocaleString()}</span>
                      </h2>
                      <div class="recall-card-stack">
                        <For each={group.items}>{(item) => <RecallCard item={item} />}</For>
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

function RecallCard(props: { item: RecalledItem }) {
  const confidence = () => clampConfidence(props.item.confidence);
  const alternatives = () => props.item.alternatives || [];
  const openLoops = () => props.item.openLoops || [];

  return (
    <article class={`recall-card recall-card--${props.item.kind.toLowerCase()}`}>
      <A
        class="recall-card-head recall-card-link"
        href={recalledItemHref(props.item)}
        aria-label={`Open ${props.item.headline || titleKind(props.item.kind)} in Browse`}
      >
        <SourceDot source={props.item.source} />
        <KindBadge kind={titleKind(props.item.kind)} />
        <strong>{props.item.headline || titleKind(props.item.kind)}</strong>
        <span>{timeAgo(props.item.observedAt)}</span>
      </A>
      <div class="recall-card-meta">
        <span>{truncatePath(props.item.repo)}</span>
        {props.item.clientSessionId ? <span>{props.item.clientSessionId}</span> : null}
        {props.item.toAgent ? <span>to {props.item.toAgent}</span> : null}
      </div>
      <Show when={props.item.kind.toLowerCase() === "handoff"}>
        <HandoffContext text={props.item.headline} label="Read recalled context">
          <A href={recalledItemHref(props.item)}>Open full handoff in Browse</A>
        </HandoffContext>
      </Show>
      <Show when={props.item.rationale}>
        {(rationale) => <p class="recall-rationale">{rationale()}</p>}
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

function recalledItemHref(item: RecalledItem): string {
  const query = new URLSearchParams({
    view: "browse",
    session: item.sessionId,
    event: item.eventId,
  });
  // An explicit empty project overrides remembered Activity scope so the exact
  // owning session remains reachable even when Recall has no trustworthy repo.
  query.set("project", "");
  return `/?${query.toString()}`;
}

function titleKind(kind: string): string {
  const normalized = kind.toLowerCase();
  if (normalized === "decision") return "Decision";
  if (normalized === "handoff") return "Handoff";
  if (normalized === "observation") return "Observation";
  return kind.charAt(0).toUpperCase() + kind.slice(1);
}

function clampConfidence(value: number | null | undefined): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, Number(value)));
}
