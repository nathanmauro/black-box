import type { AgentEvent } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import { looksLikeJson, metadataList, metadataText, parseMetadata } from "./eventData";

type HandoffCardProps = {
  event: AgentEvent;
};

export default function HandoffCard(props: HandoffCardProps) {
  const meta = () => parseMetadata(props.event.metadata);
  const summary = () =>
    metadataText(meta().contextSummary) ||
    (props.event.text && !looksLikeJson(props.event.text) ? props.event.text : "") ||
    "Handoff";

  return (
    <article class="event-card event-card--handoff">
      <div class="event-card-head">
        <SourceDot source={props.event.source} />
        <KindBadge kind="Handoff" />
        <strong>{truncatePath(summary())}</strong>
        <span class="event-card-time">{timeAgo(props.event.observedAt)}</span>
      </div>
      <div class="event-card-meta">
        {metadataText(meta().toAgent) ? <span>to {metadataText(meta().toAgent)}</span> : null}
        {metadataText(meta().nextAction) ? <span>next: {truncatePath(metadataText(meta().nextAction))}</span> : null}
      </div>
      <OpenLoops loops={metadataList(meta().openLoops)} />
    </article>
  );
}

function OpenLoops(props: { loops: string[] }) {
  if (!props.loops.length) return null;
  return (
    <div class="metadata-list">
      <span>open loops</span>
      <ul>
        {props.loops.map((item) => (
          <li>{truncatePath(item)}</li>
        ))}
      </ul>
    </div>
  );
}
