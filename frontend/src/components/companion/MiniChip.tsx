import { onCleanup, onMount, Show } from "solid-js";
import type { PulseState } from "../../lib/companion/model";

export const PULSE_LABEL: Record<PulseState, string> = { connecting: "connecting", live: "live", idle: "idle", disconnected: "offline" };

type MiniChipProps = { pulse: PulseState; unseen: number; onExpand: () => void; onSize?: (width: number) => void };

export default function MiniChip(props: MiniChipProps) {
  let ref: HTMLButtonElement | undefined;

  // The shell panel is only as wide as the fixed default unless told otherwise; report this chip's
  // real rendered width so a long pulse label plus a two-digit unseen count is never clipped.
  const report = () => {
    if (ref) props.onSize?.(ref.getBoundingClientRect().width);
  };

  onMount(() => {
    report();
    if (typeof ResizeObserver === "undefined" || !ref) return;
    const observer = new ResizeObserver(report);
    observer.observe(ref);
    onCleanup(() => observer.disconnect());
  });

  return (
    <button
      ref={ref}
      type="button"
      class={`companion-chip companion-chip--${props.pulse}`}
      aria-label={`Black Box companion: ${PULSE_LABEL[props.pulse]}, ${props.unseen} unseen`}
      title="Expand"
      onClick={() => props.onExpand()}
    >
      <span class="companion-dot" aria-hidden="true" />
      <span class="companion-chip-label">{PULSE_LABEL[props.pulse]}</span>
      <Show when={props.unseen > 0}>
        <span class="companion-count">{props.unseen}</span>
      </Show>
    </button>
  );
}
