import { A } from "@solidjs/router";
import { type JSX } from "solid-js";
import { timeAgo, truncatePath } from "../../lib/format";
import type { RunSegment } from "../../lib/streamGroups";
import SourceDot from "../SourceDot";

type RunHeaderProps = {
  run: RunSegment;
  sticky: boolean;
  sessionHref: string;
  actions?: JSX.Element;
};

// The run header is the only place SourceDot and cwd render (P2); rows beneath drop both.
// Runs of ≥3 raw events get the sticky variant so the current session stays identified while
// scrolling; short runs get the compact inline variant with the same context-zone actions (§7).
export default function RunHeader(props: RunHeaderProps) {
  const run = () => props.run;
  const title = () => run().sessionTitle || run().clientSessionId;

  return (
    <header
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
      {props.actions}
      <A href={props.sessionHref} class="stream-session-link">
        View session <span aria-hidden="true">→</span>
      </A>
    </header>
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

function durationLabel(ms: number): string {
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}
