import { createEffect, Match, on, onCleanup, onMount, Show, Switch } from "solid-js";
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

  // Switch/Match unmounts the level being left and mounts the new one, which drops DOM focus to
  // <body> (there is nothing left to hold it) — forcing a keyboard user to tab from the top of the
  // document after every chip click, project open, Back, Collapse or Escape. Each level's primary
  // control reports itself here via focusRef as it mounts, and every level change after the first
  // hands focus to whichever one is current.
  let miniRef: HTMLButtonElement | undefined;
  let compactRef: HTMLButtonElement | undefined;
  let expandedRef: HTMLButtonElement | undefined;
  createEffect(
    on(
      store.mode,
      (mode) => {
        queueMicrotask(() => {
          if (mode === "mini") miniRef?.focus();
          else if (mode === "compact") compactRef?.focus();
          else if (mode === "expanded") expandedRef?.focus();
        });
      },
      { defer: true },
    ),
  );

  return (
    // data-pulse lets an external checker (the macOS --self-test) tell a genuinely-ready page apart
    // from one that rendered .companion but is still connecting or behind a failed initial load.
    <section
      class={`companion companion--${store.mode()}${embedded ? " companion--embedded" : ""}`}
      data-mode={store.mode()}
      data-pulse={store.model().pulse}
    >
      <Switch>
        <Match when={store.mode() === "mini"}>
          <MiniChip
            pulse={store.model().pulse}
            unseen={store.model().unseenTotal}
            onExpand={() => store.setMode("compact")}
            onSize={store.reportMiniWidth}
            hasError={store.error() !== null}
            focusRef={(el) => (miniRef = el)}
          />
        </Match>
        <Match when={store.mode() === "compact"}>
          <CompactList
            model={store.model()}
            loading={store.loading()}
            onOpenProject={store.openProject}
            onOpenRiver={store.openRiver}
            onCollapse={() => store.setMode("mini")}
            focusRef={(el) => (compactRef = el)}
          />
        </Match>
        <Match when={store.mode() === "expanded"}>
          <ExpandedView
            model={store.model()}
            view={store.expanded()}
            onBack={() => store.setMode("compact")}
            onToggleView={store.toggleExpandedView}
            onCollapse={() => store.setMode("mini")}
            focusRef={(el) => (expandedRef = el)}
          />
        </Match>
      </Switch>
      <Show when={store.error()}>{(message) => <p class="companion-error" role="status">{message()}</p>}</Show>
    </section>
  );
}
