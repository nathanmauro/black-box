import { Show } from "solid-js";
import { durationLabel } from "../../lib/format";
import type { FoldRow } from "../../lib/streamGroups";
import { eventHeadlineSpans } from "./EventRow";

type StreamFoldProps = {
  fold: FoldRow;
  onUnfold: () => void;
};

const MAX_NAMED_ARGS = 2;

// One honest row for a chatter streak (spec §4.4): kind-mark · "Read ×12 — a.ts, b.ts, +10 more"
// · "over 3m". The mark goes red when the fold swallowed a failure (loudest tone, P4/D3).
// Clicking unfolds in place; the page moves focus to the first revealed row.
export default function StreamFold(props: StreamFoldProps) {
  const fold = () => props.fold;
  const count = () => fold().items.length;

  const argSummary = () => {
    const args: string[] = [];
    for (const member of fold().items) {
      if (args.length >= MAX_NAMED_ARGS) break;
      const arg = eventHeadlineSpans(member).find((span) => span.kind === "arg")?.text;
      if (arg) args.push(arg);
    }
    if (!args.length) return "";
    const remainder = count() - args.length;
    return remainder > 0 ? `${args.join(", ")}, +${remainder} more` : args.join(", ");
  };

  const span = () => {
    const newest = fold().items[0];
    const oldest = fold().items[fold().items.length - 1];
    const ms = Date.parse(newest.observedAt) - Date.parse(oldest.observedAt);
    return Number.isFinite(ms) && ms >= 1000 ? `over ${durationLabel(ms)}` : "";
  };

  return (
    <article class="stream-row-wrap">
      <button
        type="button"
        class="stream-row stream-fold"
        aria-expanded="false"
        onClick={props.onUnfold}
      >
        <span
          classList={{ "kind-mark": true, "kind-mark--error": fold().loudestTone === "error" }}
          aria-hidden="true"
        >
          {fold().mark}
        </span>
        <span class="stream-row-headline stream-fold-label">
          {fold().items[0].toolName} ×{count()}
          <Show when={argSummary()}>
            {(summary) => <span class="headline-arg"> — {summary()}</span>}
          </Show>
        </span>
        <span class="stream-fold-span">{span()}</span>
      </button>
    </article>
  );
}
