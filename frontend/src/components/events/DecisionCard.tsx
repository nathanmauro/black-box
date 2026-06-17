import type { AgentEvent } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import { looksLikeJson, metadataList, metadataText, parseMetadata } from "./eventData";

type DecisionCardProps = {
  event: AgentEvent;
};

export default function DecisionCard(props: DecisionCardProps) {
  const meta = () => parseMetadata(props.event.metadata);
  const decision = () =>
    metadataText(meta().decision) ||
    (props.event.text && !looksLikeJson(props.event.text) ? props.event.text : "") ||
    "Decision";
  const confidence = () => clampConfidence(Number(meta().confidence));

  return (
    <article class="event-card event-card--decision">
      <div class="event-card-head">
        <SourceDot source={props.event.source} />
        <KindBadge kind="Decision" />
        <strong>{truncatePath(decision())}</strong>
        <span class="event-card-time">{timeAgo(props.event.observedAt)}</span>
      </div>
      {metadataText(meta().rationale) ? <p class="event-rationale">{truncatePath(metadataText(meta().rationale))}</p> : null}
      <div class="confidence-row">
        <span>confidence</span>
        <meter min="0" max="1" value={confidence()}>
          {confidence()}
        </meter>
        <span>{Math.round(confidence() * 100)}%</span>
      </div>
      <MetadataList title="alternatives" items={metadataList(meta().alternatives)} />
      <MetadataList title="open loops" items={metadataList(meta().openLoops)} />
    </article>
  );
}

function MetadataList(props: { title: string; items: string[] }) {
  if (!props.items.length) return null;
  return (
    <div class="metadata-list">
      <span>{props.title}</span>
      <ul>
        {props.items.map((item) => (
          <li>{truncatePath(item)}</li>
        ))}
      </ul>
    </div>
  );
}

function clampConfidence(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, value));
}
