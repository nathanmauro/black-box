import { createSignal } from "solid-js";

type RowSessionActionsProps = {
  sessionId: string;
  streamLink: string;
  onFilterToSession: (sessionId: string) => void;
};

// Per-run affordances (spec §7): "Filter to this session" patches the visible q with a session:
// token; "Copy link" copies an absolute /stream deep link built from the visible q (never the
// hidden project_group injection). Mounted on both RunHeader variants (spec §4.1).
export default function RowSessionActions(props: RowSessionActionsProps) {
  const [message, setMessage] = createSignal("");

  function filter(event: MouseEvent) {
    event.stopPropagation();
    props.onFilterToSession(props.sessionId);
  }

  async function copyLink(event: MouseEvent) {
    event.stopPropagation();
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(props.streamLink);
      setMessage("Link copied.");
    } catch {
      setMessage("Could not copy link.");
    }
  }

  return (
    <span class="row-session-actions">
      <button type="button" class="row-session-action" onClick={filter}>
        Filter to this session
      </button>
      <button type="button" class="row-session-action" title={props.streamLink} onClick={copyLink}>
        Copy link
      </button>
      <span class="row-session-actions-status" aria-live="polite">{message()}</span>
    </span>
  );
}
