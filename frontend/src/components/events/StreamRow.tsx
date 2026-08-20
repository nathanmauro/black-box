import { A } from "@solidjs/router";
import { For, Show } from "solid-js";
import type { EventFeedItem } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import { kindMarkOf } from "../../lib/presenters/marks";
import KindBadge from "../KindBadge";
import { EventRenderer, eventHeadline, eventHeadlineSpans } from "./EventRow";

type StreamRowProps = {
  item: EventFeedItem;
  expanded: boolean;
  textExpanded?: boolean;
  sessionHref: string;
  onToggle: () => void;
  // A row whose cwd differs from its run's shows its own cwd inline, dim (spec §4.1) — the
  // only per-row cwd; shared context lives on the run header (P2).
  cwdException?: boolean;
};

const LANDMARK_KINDS = new Set(["Decision", "Handoff", "Observation", "UserPromptSubmit"]);

// The shared 92px first column fits the longest landmark badge ("Observation");
// UserPromptSubmit shortens to "Prompt" so the prompt badge fits the same column.
const BADGE_LABELS: Record<string, string> = { UserPromptSubmit: "Prompt" };

export default function StreamRow(props: StreamRowProps) {
  const item = () => props.item;
  const headline = () => eventHeadline(item());
  const landmark = () => LANDMARK_KINDS.has(item().eventType ?? "");
  const mark = () => kindMarkOf(item());

  return (
    <article classList={{ "stream-row-wrap": true, "stream-row-wrap--expanded": props.expanded }}>
      <button
        type="button"
        classList={{
          "stream-row": true,
          "stream-row--landmark": landmark(),
          [`stream-row--landmark-${kindClass(item().eventType)}`]: landmark(),
        }}
        aria-expanded={props.expanded}
        aria-label={headline()}
        onClick={props.onToggle}
      >
        <Show
          when={landmark()}
          fallback={
            <span classList={{ "kind-mark": true, "kind-mark--error": mark().error }} aria-hidden="true">
              {mark().label}
            </span>
          }
        >
          <KindBadge kind={item().eventType} label={BADGE_LABELS[item().eventType ?? ""]} />
        </Show>
        <span class="stream-row-headline" title={headline()}>
          <For each={eventHeadlineSpans(item())}>
            {(span) => (span.kind === "arg" ? <span class="headline-arg">{span.text}</span> : span.text)}
          </For>
          <Show when={props.cwdException}>
            <span class="stream-row-cwd"> · {truncatePath(item().cwd)}</span>
          </Show>
        </span>
        <time dateTime={item().observedAt} title={item().observedAt}>
          {timeAgo(item().observedAt)}
        </time>
      </button>
      {props.expanded ? (
        <div class={`stream-row-expanded stream-row-expanded--${kindClass(item().eventType)}`}>
          {/* Session title and context-zone actions live on RunHeader (spec §4.3); the card
              keeps only the precise per-event position link — the §9 vocabulary. */}
          <div class="stream-row-expanded-head">
            <A href={props.sessionHref} class="stream-session-link">
              Open at this event <span aria-hidden="true">→</span>
            </A>
          </div>
          <EventRenderer event={item()} textExpanded={props.textExpanded} />
        </div>
      ) : null}
    </article>
  );
}

function kindClass(kind: string | null | undefined): string {
  return String(kind || "event").toLowerCase().replace(/[^a-z0-9]+/g, "-");
}
