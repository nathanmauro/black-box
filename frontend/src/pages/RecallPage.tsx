import { createMemo, createSignal, For, Show } from "solid-js";
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
];

export default function RecallPage() {
  const [scope, setScope] = createSignal("");
  const [withinHours, setWithinHours] = createSignal(168);
  const [kinds, setKinds] = createSignal<string[]>(["decision", "handoff"]);
  const [result, setResult] = createSignal<RecallResult | null>(null);
  const [error, setError] = createSignal<string | null>(null);
  const [loading, setLoading] = createSignal(false);
  const filteredItems = createMemo(() => sourceFilter.matches(result()?.items || []));
  const groupedItems = createMemo(() => groupByKind(filteredItems()));

  async function runRecall() {
    setLoading(true);
    setError(null);
    try {
      setResult(await getRecall(scope(), withinHours(), kinds()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
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
          <p>Query Black Box for decisions, handoffs, and observations without digging through raw transcripts.</p>
        </div>
      </header>

      <form
        class="recall-form"
        onSubmit={(event) => {
          event.preventDefault();
          void runRecall();
        }}
      >
        <label class="recall-field">
          <span>Scope</span>
          <input
            value={scope()}
            onInput={(event) => setScope(event.currentTarget.value)}
            placeholder="/Users/nathan/Developer/proj/sba-agentic or a topic"
          />
        </label>
        <fieldset class="recall-window">
          <legend>Window</legend>
          <For each={TIME_WINDOWS}>
            {(option) => (
              <label classList={{ "segmented-option": true, "segmented-option--active": withinHours() === option.value }}>
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
        </fieldset>
        <fieldset class="recall-kinds">
          <legend>Kinds</legend>
          <For each={RECALL_KINDS}>
            {(kind) => (
              <label class="check-chip">
                <input type="checkbox" checked={kinds().includes(kind)} onChange={() => toggleKind(kind)} />
                <span>{titleKind(kind)}</span>
              </label>
            )}
          </For>
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
              <p>Default kind filters start with decisions and handoffs, the highest-signal handoff surface.</p>
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
                fallback={<p class="empty-state">No recall items match this scope and source filter.</p>}
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

function RecallCard(props: { item: RecalledItem }) {
  const confidence = () => clampConfidence(props.item.confidence);
  const alternatives = () => props.item.alternatives || [];
  const openLoops = () => props.item.openLoops || [];

  return (
    <article class={`recall-card recall-card--${props.item.kind.toLowerCase()}`}>
      <div class="recall-card-head">
        <SourceDot source={props.item.source} />
        <KindBadge kind={titleKind(props.item.kind)} />
        <strong>{props.item.headline || titleKind(props.item.kind)}</strong>
        <span>{timeAgo(props.item.observedAt)}</span>
      </div>
      <div class="recall-card-meta">
        <span>{truncatePath(props.item.repo)}</span>
        {props.item.clientSessionId ? <span>{props.item.clientSessionId}</span> : null}
        {props.item.toAgent ? <span>to {props.item.toAgent}</span> : null}
      </div>
      <Show when={props.item.rationale}>
        {(rationale) => <p class="recall-rationale">{rationale()}</p>}
      </Show>
      <div class="confidence-row recall-confidence">
        <span>confidence</span>
        <meter min="0" max="1" value={confidence()}>
          {confidence()}
        </meter>
        <span>{Math.round(confidence() * 100)}%</span>
      </div>
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
