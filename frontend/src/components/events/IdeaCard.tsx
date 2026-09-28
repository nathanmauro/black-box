import { Show } from "solid-js";
import type { AgentEvent } from "../../lib/api";
import { timeAgo } from "../../lib/format";
import { ideaFieldsFromEvent } from "../../lib/ideas";
import {
  IdeaConnects,
  IdeaLegsMeter,
  IdeaLink,
  IdeaOriginBadge,
  IdeaStatusPill,
} from "../IdeaBadges";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import { parseMetadata } from "./eventData";

type IdeaCardProps = {
  event: AgentEvent;
};

export default function IdeaCard(props: IdeaCardProps) {
  const idea = () => ideaFieldsFromEvent(parseMetadata(props.event.metadata), props.event.text);

  return (
    <article class="event-card event-card--idea">
      <div class="event-card-head">
        <SourceDot source={props.event.source} />
        <KindBadge kind="Idea" />
        <strong>{idea().title}</strong>
        <span class="event-card-time">{timeAgo(props.event.observedAt)}</span>
      </div>
      <Show when={idea().oneLiner}>
        <p class="event-rationale">{idea().oneLiner}</p>
      </Show>
      <div class="idea-badges">
        <IdeaOriginBadge origin={idea().origin} />
        <IdeaStatusPill status={idea().status} />
        <IdeaLegsMeter legs={idea().legs} />
      </div>
      <Show when={idea().quote}>
        <blockquote class="idea-quote">{idea().quote}</blockquote>
      </Show>
      <IdeaConnects items={idea().connects} />
      <Show when={idea().resumeStep}>
        <p class="idea-resume">
          <span>resume</span> {idea().resumeStep}
        </p>
      </Show>
      <IdeaLink link={idea().link} />
    </article>
  );
}
