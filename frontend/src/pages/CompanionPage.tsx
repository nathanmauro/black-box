import { Match, onCleanup, onMount, Show, Switch } from "solid-js";
import CompactList from "../components/companion/CompactList";
import ExpandedView from "../components/companion/ExpandedView";
import MiniChip from "../components/companion/MiniChip";
import { createCompanionStore } from "../lib/companion/store";
import { useLiveStore } from "../lib/sse";
import "../companion.css";

export default function CompanionPage() {
  const live = useLiveStore();
  const store = createCompanionStore(live);
  // The macOS shell loads ?embedded=1 into a transparent panel.
  const embedded = new URLSearchParams(window.location.search).get("embedded") === "1";

  onMount(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === "Escape") store.stepDown();
    };
    window.addEventListener("keydown", handler);
    onCleanup(() => window.removeEventListener("keydown", handler));
  });

  return (
    <section class={`companion companion--${store.mode()}${embedded ? " companion--embedded" : ""}`} data-mode={store.mode()}>
      <Switch>
        <Match when={store.mode() === "mini"}>
          <MiniChip pulse={store.model().pulse} unseen={store.model().unseenTotal} onExpand={() => store.setMode("compact")} onSize={store.reportMiniWidth} hasError={store.error() !== null} />
        </Match>
        <Match when={store.mode() === "compact"}>
          <CompactList model={store.model()} loading={store.loading()} onOpenProject={store.openProject} onOpenRiver={store.openRiver} onCollapse={() => store.setMode("mini")} />
        </Match>
        <Match when={store.mode() === "expanded"}>
          <ExpandedView model={store.model()} view={store.expanded()} onBack={() => store.setMode("compact")} onToggleView={store.toggleExpandedView} onCollapse={() => store.setMode("mini")} />
        </Match>
      </Switch>
      <Show when={store.error()}>{(message) => <p class="companion-error" role="status">{message()}</p>}</Show>
    </section>
  );
}
