import { A } from "@solidjs/router";
import { type JSX, Show } from "solid-js";
import { durationLabel, timeAgo, truncatePath } from "../../lib/format";
import type { RunSegment } from "../../lib/streamGroups";
import SourceDot from "../SourceDot";
import { formatRelativeTime, getSessionStatus } from "../../lib/heartbeat";

type RunHeaderProps = {
  run: RunSegment;
  sticky: boolean;
  sessionHref: string;
  actions?: JSX.Element;
};

// The run header is the only place SourceDot and cwd render (P2); rows beneath drop both.
// Runs of ≥3 raw events get the sticky variant so the current session stays identified while
// scrolling; short runs get the compact inline variant with the same context-zone actions (§7).
// An <article> labeled by session (§4.6): the feed pattern owns articles, and the header is the
// run's own entry in that list — never a <section>/region interposed between feed and rows.
export default function RunHeader(props: RunHeaderProps) {
  const run = () => props.run;
  const title = () => run().sessionTitle || run().clientSessionId;
  const status = () => getSessionStatus(run().lastSeenAt);
  const heartbeat = () => formatRelativeTime(run().lastSeenAt);

  return (
    <article
      aria-label={`Session ${title()}`}
      classList={{
        "stream-run-head": true,
        "stream-run-head--sticky": props.sticky,
        "stream-run-head--compact": !props.sticky,
      }}
    >
      <SourceDot source={run().source} />
      <span class="stream-run-title" title={title()}>
        {title()}
      </span>
      <span class="stream-run-cwd" title={run().cwd ?? undefined}>
        {truncatePath(run().cwd)}
      </span>
      <span class="stream-run-count">
        {run().eventCount === 1 ? "1 event" : `${run().eventCount} events`}
      </span>
      <span class="stream-run-span">{runSpan(run())}</span>
      <Show when={run().lastSeenAt}>
        <span class="stream-run-heartbeat">
          <span
            class={`heartbeat-dot heartbeat-dot--${status()}`}
            title={`Session ${status()}`}
            aria-label={`Session status: ${status()}`}
          />
          <span class="heartbeat-time">{heartbeat()}</span>
        </span>
      </Show>
      {props.actions}
      <A href={props.sessionHref} class="stream-session-link">
        View session <span aria-hidden="true">→</span>
      </A>
    </article>
  );
}

// Timespan of the run: recency of its newest member, plus the stretch it covers when the run
// spans time ("2m · over 12m").
function runSpan(run: RunSegment): string {
  const recency = timeAgo(run.newestAt);
  const spanMs = Date.parse(run.newestAt) - Date.parse(run.oldestAt);
  if (!Number.isFinite(spanMs) || spanMs < 1000) return recency;
  return `${recency} · over ${durationLabel(spanMs)}`;
}
