import type { AgentEvent } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import { looksLikeJson, metadataText, parseMetadata } from "./eventData";

type ObservationCardProps = {
  event: AgentEvent;
};

export default function ObservationCard(props: ObservationCardProps) {
  const meta = () => parseMetadata(props.event.metadata);
  const text = () =>
    metadataText(meta().text) ||
    metadataText(meta().observation) ||
    (props.event.text && !looksLikeJson(props.event.text) ? props.event.text : "") ||
    "Observation";

  return (
    <article class="event-card event-card--observation">
      <div class="event-card-head">
        <SourceDot source={props.event.source} />
        <KindBadge kind="Observation" />
        <strong>{truncatePath(text())}</strong>
        <span class="event-card-time">{timeAgo(props.event.observedAt)}</span>
      </div>
    </article>
  );
}
