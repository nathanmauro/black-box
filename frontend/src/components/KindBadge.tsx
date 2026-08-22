const KIND_CLASSES: Record<string, string> = {
  Decision: "kind-badge--decision",
  Handoff: "kind-badge--handoff",
  Observation: "kind-badge--observation",
  PostToolUse: "kind-badge--muted",
  UserPromptSubmit: "kind-badge--prompt",
  SessionStart: "kind-badge--session",
  SessionEnd: "kind-badge--session",
};

type KindBadgeProps = {
  kind?: string | null;
  /** Display text override; the kind still drives color. Lets tight layouts shorten long kinds. */
  label?: string;
};

export default function KindBadge(props: KindBadgeProps) {
  const kind = () => props.kind || "Event";
  return <span class={`kind-badge ${KIND_CLASSES[kind()] || ""}`}>{props.label ?? kind()}</span>;
}
