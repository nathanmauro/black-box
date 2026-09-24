import { Show } from "solid-js";
import type { PulseState } from "../../lib/companion/model";

export const PULSE_LABEL: Record<PulseState, string> = { connecting: "connecting", live: "live", idle: "idle", disconnected: "offline" };

type MiniChipProps = { pulse: PulseState; unseen: number; onExpand: () => void };

export default function MiniChip(props: MiniChipProps) {
  return (
    <button
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
