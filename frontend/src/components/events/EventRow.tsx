import { createSignal, For, Show } from "solid-js";
import type { AgentEvent } from "../../lib/api";
import { timeAgo, truncatePath } from "../../lib/format";
import { presentationOf } from "../../lib/presenters/registry";
import KindBadge from "../KindBadge";
import SourceDot from "../SourceDot";
import DecisionCard from "./DecisionCard";
import HandoffCard from "./HandoffCard";
import InlineSpans from "./InlineSpans";
import ObservationCard from "./ObservationCard";
import BlockView from "./blocks/BlockView";
import { looksLikeJson, parseJsonObject } from "./eventData";
import ToolPayload, { payloadText } from "./ToolPayload";

type EventRowProps = {
  event: AgentEvent;
  textExpanded?: boolean;
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

const COMPACT_TEXT_CHARS = 900;
const COMPACT_TEXT_LINES = 10;

export default function EventRow(props: EventRowProps) {
  const event = () => props.event;
  const headline = () => eventHeadline(event());
  const presentation = () => (event().toolName ? presentationOf(event()) : null);

  return (
    <article classList={{ "event-card": true, "event-card--muted": event().eventType === "PostToolUse" }}>
      <div class="event-card-head">
        <SourceDot source={event().source} />
        <KindBadge kind={event().eventType} />
        <Show when={presentation()} fallback={<strong>{headline()}</strong>}>
          {(current) => (
            <>
              <span class={`tone-pill tone-pill--${current().kindPill.tone}`}>{current().kindPill.label}</span>
              <strong>
                <Show when={current().headline.length} fallback={headline()}>
                  <InlineSpans spans={current().headline} />
                </Show>
              </strong>
            </>
          )}
        </Show>
        <span class="event-card-time">{timeAgo(event().observedAt)}</span>
      </div>
      <div class="event-card-meta">
        {event().role ? <span>{event().role}</span> : null}
        {event().toolName && !presentation() ? <span>{event().toolName}</span> : null}
        <span>seq {event().turnId || event().id.slice(0, 8)}</span>
      </div>
      {event().text && !looksLikeJson(event().text) && !duplicatesToolOutput(event()) ? <ReaderText text={event().text ?? ""} expanded={props.textExpanded} /> : null}
      <Show
        when={presentation()}
        fallback={
          event().toolInputJson || event().toolOutputJson ? (
            <ToolPayload toolName={event().toolName} inputJson={event().toolInputJson} outputJson={event().toolOutputJson} />
          ) : null
        }
      >
        {(current) => (
          <For each={current().blocks}>
            {(block, index) => <BlockView block={block} eventId={event().id} index={index()} />}
          </For>
        )}
      </Show>
    </article>
  );
}

export type HeadlineSpan = { kind: "label" | "arg"; text: string };

/**
 * The collapsed-row headline split into label vs machine-literal spans
 * (spec §4.3: bold = intent, mono = machine artifact). Extraction logic is
 * unchanged from eventHeadline — presenter spans first, then the primary-arg
 * fallback; `code`/`fileLink`/`url` presenter spans are the machine literals.
 */
export function eventHeadlineSpans(event: AgentEvent): HeadlineSpan[] {
  if (event.toolName) {
    const spans = presentationOf(event)
      .headline.map((span): HeadlineSpan =>
        span.kind === "text"
          ? { kind: "label", text: span.text }
          : { kind: "arg", text: span.kind === "code" ? span.text : span.label },
      )
      .filter((span) => span.text);
    if (spans.some((span) => span.text.trim())) return spans;
  }
  const input = parseJsonObject(event.toolInputJson);
  const key = input ? primaryArgKey(input) : null;
  if (key && input) return [{ kind: "arg", text: commandHeadline(String(input[key]), event.toolName) }];
  if (event.text && !looksLikeJson(event.text)) return [{ kind: "label", text: event.text || "" }];
  return [{ kind: "label", text: event.toolName || event.role || event.eventType || "Event" }];
}

export function eventHeadline(event: AgentEvent): string {
  return eventHeadlineSpans(event)
    .map((span) => span.text)
    .join("")
    .trim();
}

export function ReaderText(props: { text: string; expanded?: boolean }) {
  const [override, setOverride] = createSignal<boolean | null>(null);
  const expanded = () => override() ?? props.expanded ?? false;
  const compact = () => shouldCompactText(props.text);
  const collapsed = () => compact() && !expanded();

  return (
    <>
      <p classList={{ "reader-text": true, "reader-text--collapsed": collapsed() }}>{props.text}</p>
      <Show when={compact()}>
        <button type="button" class="reader-text-toggle" onClick={() => setOverride(!expanded())}>
          {collapsed() ? "Show full message" : "Collapse message"}
        </button>
      </Show>
    </>
  );
}

export function EventRenderer(props: { event: AgentEvent; textExpanded?: boolean }) {
  switch (props.event.eventType) {
    case "Decision":
      return <DecisionCard event={props.event} />;
    case "Handoff":
      return <HandoffCard event={props.event} />;
    case "Observation":
      return <ObservationCard event={props.event} />;
    default:
      return <EventRow event={props.event} textExpanded={props.textExpanded} />;
  }
}

function primaryArgKey(args: Record<string, unknown>): string | null {
  for (const key of PRIMARY_KEYS) {
    if (typeof args[key] === "string" && args[key].trim()) return key;
  }
  return Object.keys(args).find((key) => typeof args[key] === "string" && String(args[key]).trim()) || null;
}

function shouldCompactText(text: string): boolean {
  if (text.length > COMPACT_TEXT_CHARS) return true;
  return text.split(/\r?\n/).filter((line) => line.trim()).length > COMPACT_TEXT_LINES;
}

function duplicatesToolOutput(event: AgentEvent): boolean {
  if (!event.text || !event.toolOutputJson) return false;
  return payloadText(event.toolOutputJson)?.trim() === event.text.trim();
}

function commandHeadline(value: string, toolName: string | null | undefined): string {
  const patchFile = /^\*\*\*\s+(?:Update|Add|Delete) File:\s*(.+)$/mu.exec(value)?.[1];
  if (String(toolName || "").toLowerCase() === "apply_patch" && patchFile) {
    return `Patch ${truncatePath(patchFile.trim())}`;
  }
  const lines = value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const firstUseful = lines.find((line) => !line.startsWith("#")) ?? lines[0] ?? value.trim();
  const suffix = lines.length > 1 ? ` +${lines.length - 1} lines` : "";
  return `${truncatePath(firstUseful).slice(0, 180)}${suffix}`;
}
