import type { AgentEvent } from "./api";

export type SessionConversationRole = "user" | "assistant";

export type SessionTranscriptTurnLike = {
  events: AgentEvent[];
};

const SEMANTIC_DUPLICATE_WINDOW_MS = 10_000;
const INLINE_PAYLOAD_IDENTITY_CHARS = 32_768;

export function isSessionMemoryEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return type === "decision" || type === "observation" || type === "handoff";
}

export function isSessionToolEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return Boolean(
    event.toolName ||
    event.toolInputJson ||
    event.toolOutputJson ||
    normalizedRole(event) === "tool" ||
    type.includes("tooluse") ||
    type.includes("toolresult") ||
    type.includes("tooloutput"),
  );
}

export function sessionConversationRole(event: AgentEvent): SessionConversationRole | null {
  if (isSessionMemoryEvent(event) || isSessionToolEvent(event) || isHiddenSessionEvent(event))
    return null;
  if (isPromptEvent(event)) return "user";
  if (isAssistantEvent(event)) return "assistant";
  return null;
}

export function isSessionReaderEvent(event: AgentEvent): boolean {
  if (isSessionMemoryEvent(event) || isHiddenSessionEvent(event)) return false;
  return isSessionToolEvent(event) || sessionConversationRole(event) !== null;
}

/**
 * Merge newest-first recorded events with newest-first transcript events.
 * Stable recorded ids and payloads win exact and clearly-semantic duplicates.
 */
export function mergeSessionEvents(recorded: AgentEvent[], transcript: AgentEvent[]): AgentEvent[] {
  type Candidate = {
    event: AgentEvent;
    origin: "recorded" | "transcript";
    index: number;
    time: number;
    identities: string[];
  };

  const accepted: Candidate[] = [];
  const byId = new Set<string>();
  const byIdentity = new Map<string, Candidate[]>();

  const add = (event: AgentEvent, origin: Candidate["origin"], index: number) => {
    if (byId.has(event.id)) return;
    const candidate: Candidate = {
      event,
      origin,
      index,
      time: parsedTime(event.observedAt),
      identities: semanticIdentities(event),
    };
    const duplicate = candidate.identities.some((identity) =>
      (byIdentity.get(identity) ?? []).some((existing) =>
        clearlySameOccurrence(existing.event, event),
      ),
    );
    if (duplicate) return;

    accepted.push(candidate);
    byId.add(event.id);
    for (const identity of candidate.identities) {
      const matches = byIdentity.get(identity);
      if (matches) matches.push(candidate);
      else byIdentity.set(identity, [candidate]);
    }
  };

  recorded.forEach((event, index) => add(event, "recorded", index));
  transcript.forEach((event, index) => add(event, "transcript", index));

  return accepted
    .sort((left, right) => {
      if (left.time !== right.time) return right.time - left.time;
      if (left.origin !== right.origin) return left.origin === "recorded" ? -1 : 1;
      return left.index - right.index;
    })
    .map((candidate) => candidate.event);
}

export function filterSessionTranscriptTurns<T extends SessionTranscriptTurnLike>(
  turns: T[],
  query: string,
): T[] {
  const terms = sessionSearchTerms(query);
  if (!terms.length) return turns;
  return turns.filter((turn) => {
    const fields = turn.events
      .flatMap(searchableEventFields)
      .map(normalizedSearchText)
      .filter(Boolean);
    return terms.every((term) => fields.some((field) => field.includes(term)));
  });
}

function searchableEventFields(event: AgentEvent): string[] {
  if (sessionConversationRole(event)) return [event.text ?? ""];
  if (!isSessionToolEvent(event) || isHiddenSessionEvent(event)) return [];
  return [event.toolName ?? "", event.toolInputJson ?? "", event.toolOutputJson ?? ""];
}

function sessionSearchTerms(query: string): string[] {
  const terms: string[] = [];
  const matcher = /"([^"]+)"|(\S+)/g;
  for (const match of query.matchAll(matcher)) {
    const term = normalizedSearchText(match[1] ?? match[2]);
    if (term) terms.push(term);
  }
  return terms;
}

function isPromptEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  return (
    normalizedRole(event) === "user" || type === "userpromptsubmit" || type === "beforesubmitprompt"
  );
}

function isAssistantEvent(event: AgentEvent): boolean {
  const type = normalizedEventType(event);
  if (normalizedRole(event) === "assistant") return Boolean(event.text?.trim());
  return (
    Boolean(event.text?.trim()) &&
    (type === "assistantmessage" ||
      type === "agentmessage" ||
      type === "agentresponse" ||
      type === "finalresponse" ||
      type === "stop")
  );
}

function isHiddenSessionEvent(event: AgentEvent): boolean {
  const role = normalizedRole(event);
  if (role === "system" || role === "developer" || role === "reasoning") return true;

  const type = normalizedEventType(event);
  return (
    type.includes("reasoning") ||
    type.includes("systemmessage") ||
    type.includes("developermessage") ||
    type.includes("lifecycle") ||
    type === "sessionstart" ||
    type === "sessionend" ||
    type === "turnstart" ||
    type === "turnend" ||
    type === "precompact" ||
    type === "notification"
  );
}

function semanticIdentities(event: AgentEvent): string[] {
  const role = sessionConversationRole(event);
  const text = normalizedConversationText(event.text);
  if (role && text) return [`message:${role}:${text}`];
  if (!isSessionToolEvent(event)) return [];

  const tool = normalizedSearchText(event.toolName || normalizedEventType(event));
  const identities: string[] = [];
  if (event.toolInputJson?.trim())
    identities.push(`tool:${tool}:input:${payloadIdentity(event.toolInputJson)}`);
  if (event.toolOutputJson?.trim())
    identities.push(`tool:${tool}:output:${payloadIdentity(event.toolOutputJson)}`);
  return identities;
}

function clearlySameOccurrence(left: AgentEvent, right: AgentEvent): boolean {
  if (left.sessionId !== right.sessionId) return false;
  const leftTurn = left.turnId?.trim();
  const rightTurn = right.turnId?.trim();
  if (leftTurn && rightTurn && leftTurn === rightTurn) return true;

  const leftTime = parsedTime(left.observedAt);
  const rightTime = parsedTime(right.observedAt);
  return (
    Number.isFinite(leftTime) &&
    Number.isFinite(rightTime) &&
    Math.abs(leftTime - rightTime) <= SEMANTIC_DUPLICATE_WINDOW_MS
  );
}

function payloadIdentity(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length <= INLINE_PAYLOAD_IDENTITY_CHARS) {
    try {
      return stableJson(JSON.parse(trimmed));
    } catch {
      return trimmed.replace(/\s+/g, " ");
    }
  }
  return `${trimmed.length}:${hashString(trimmed)}:${trimmed.slice(0, 48)}:${trimmed.slice(-48)}`;
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableJson(record[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value) ?? String(value);
}

function hashString(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

function parsedTime(value: string | null | undefined): number {
  const parsed = Date.parse(String(value ?? ""));
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
}

function normalizedConversationText(value: string | null | undefined): string {
  return normalizedSearchText(value);
}

function normalizedSearchText(value: unknown): string {
  return String(value ?? "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function normalizedEventType(event: AgentEvent): string {
  return String(event.eventType ?? "")
    .replace(/[^a-z0-9]/gi, "")
    .toLowerCase();
}

function normalizedRole(event: AgentEvent): string {
  return String(event.role ?? "")
    .trim()
    .toLowerCase();
}
