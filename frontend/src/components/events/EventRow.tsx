import type { AgentEvent } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import DecisionCard from "./DecisionCard";
import HandoffCard from "./HandoffCard";
import ObservationCard from "./ObservationCard";
import { looksLikeJson, parseJsonObject } from "./eventData";

type EventRowProps = {
  event: AgentEvent;
};

const PRIMARY_KEYS = [
  "command",
  "cmd",
  "script",
  "file_path",
  "filePath",
  "target_file",
  "targetFile",
  "path",
  "url",
  "query",
  "pattern",
  "prompt",
  "question",
  "text",
  "content",
];

export default function EventRow(props: EventRowProps) {
  const event = () => props.event;
  const parsedInput = () => parseJsonObject(event().toolInputJson);
  const headline = () => {
    const input = parsedInput();
    const key = input ? primaryArgKey(input) : null;
    if (key && input) return truncatePath(String(input[key]));
    if (event().text && !looksLikeJson(event().text)) return event().text || "";
    return event().toolName || event().role || event().eventType || "Event";
  };

  return (
    <article classList={{ "event-card": true, "event-card--muted": event().eventType === "PostToolUse" }}>
      <div class="event-card-head">
        <SourceDot source={event().source} />
        <KindBadge kind={event().eventType} />
        <strong>{headline()}</strong>
        <span class="event-card-time">{timeAgo(event().observedAt)}</span>
      </div>
      <div class="event-card-meta">
        {event().role ? <span>{event().role}</span> : null}
        {event().toolName ? <span>{event().toolName}</span> : null}
        <span>seq {event().turnId || event().id.slice(0, 8)}</span>
      </div>
      {event().text && !looksLikeJson(event().text) ? <p>{event().text}</p> : null}
      {event().toolInputJson || event().toolOutputJson ? (
        <details class="payload-details">
          <summary>tool payload</summary>
          {event().toolInputJson ? (
            <>
              <span class="payload-label">input</span>
              <pre>{prettyJson(event().toolInputJson)}</pre>
            </>
          ) : null}
          {event().toolOutputJson ? (
            <>
              <span class="payload-label">output</span>
              <pre>{prettyJson(event().toolOutputJson)}</pre>
            </>
          ) : null}
        </details>
      ) : null}
    </article>
  );
}

export function EventRenderer(props: EventRowProps) {
  switch (props.event.eventType) {
    case "Decision":
      return <DecisionCard event={props.event} />;
    case "Handoff":
      return <HandoffCard event={props.event} />;
    case "Observation":
      return <ObservationCard event={props.event} />;
    default:
      return <EventRow event={props.event} />;
  }
}

function primaryArgKey(args: Record<string, unknown>): string | null {
  for (const key of PRIMARY_KEYS) {
    if (typeof args[key] === "string" && args[key].trim()) return key;
  }
  return Object.keys(args).find((key) => typeof args[key] === "string" && String(args[key]).trim()) || null;
}

function prettyJson(raw: string | null | undefined): string {
  if (!raw) return "";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
