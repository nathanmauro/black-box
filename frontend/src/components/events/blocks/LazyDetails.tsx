import { createSignal, Show, type JSX } from "solid-js";

/**
 * Native <details> whose children mount on first open and stay mounted (spec §4.5):
 * zero re-render cost, keyboard and find-in-page friendly, no expand-state bookkeeping.
 */
export default function LazyDetails(props: { summary: string; class?: string; children: JSX.Element }) {
  const [mounted, setMounted] = createSignal(false);
  return (
    <details
      class={props.class || "detail-block"}
      onToggle={(toggleEvent) => {
        if (toggleEvent.currentTarget.open) setMounted(true);
      }}
    >
      <summary>{props.summary}</summary>
      <Show when={mounted()}>{props.children}</Show>
    </details>
  );
}
