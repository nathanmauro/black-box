import { A } from "@solidjs/router";
import type { EventFeedItem } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import { EventRenderer, eventHeadline } from "./EventRow";

type StreamRowProps = {
  item: EventFeedItem;
  expanded: boolean;
  sessionHref: string;
  onToggle: () => void;
};

export default function StreamRow(props: StreamRowProps) {
  const item = () => props.item;
  const headline = () => eventHeadline(item());

  return (
    <article classList={{ "stream-row-wrap": true, "stream-row-wrap--expanded": props.expanded }}>
      <button
        type="button"
        class="stream-row"
        aria-expanded={props.expanded}
        aria-label={`${headline()} in ${truncatePath(item().cwd)}`}
        onClick={props.onToggle}
      >
        <SourceDot source={item().source} />
        <KindBadge kind={item().eventType} />
        <span class="stream-row-project" title={item().cwd || undefined}>
          {truncatePath(item().cwd)}
        </span>
        <strong title={headline()}>{headline()}</strong>
        <time dateTime={item().observedAt}>{timeAgo(item().observedAt)}</time>
      </button>
      {props.expanded ? (
        <div class={`stream-row-expanded stream-row-expanded--${kindClass(item().eventType)}`}>
          <div class="stream-row-expanded-head">
            <span>
              <small>session</small>
              <strong>{item().sessionTitle || item().clientSessionId}</strong>
            </span>
            <A href={props.sessionHref} class="stream-session-link">
              View session <span aria-hidden="true">→</span>
            </A>
          </div>
          <EventRenderer event={item()} />
        </div>
      ) : null}
    </article>
  );
}

function kindClass(kind: string | null | undefined): string {
  return String(kind || "event").toLowerCase().replace(/[^a-z0-9]+/g, "-");
}
