import { createEffect, createSignal, type JSX } from "solid-js";
import { A } from "@solidjs/router";
import CommandPalette from "./components/CommandPalette";
import SourceChips from "./components/SourceChips";
import { createLiveStore, LiveStoreContext } from "./lib/sse";

type AppProps = {
  children?: JSX.Element;
};

export default function App(props: AppProps) {
  const live = createLiveStore();
  const [paletteOpen, setPaletteOpen] = createSignal(false);

  createEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  });

  return (
    <LiveStoreContext.Provider value={live}>
      <div class="app-shell">
        <header class="app-header">
          <A href="/" class="brand" aria-label="Black Box overview">
            <span class="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32">
                <rect x="5.5" y="8.5" width="21" height="15" fill="none" stroke="currentColor" stroke-width="2" />
                <path
                  d="M5.5 16 H10 L12.5 11 L16 21 L19 13.5 L21 16 H26.5"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.6"
                  stroke-linejoin="round"
                  stroke-linecap="round"
                />
              </svg>
            </span>
            <span class="brand-word">
              BLACK<span>BOX</span>
            </span>
          </A>
          <nav class="nav-pills" aria-label="Primary">
            <A href="/" end class="nav-pill">
              Overview
            </A>
            <A href="/sessions" class="nav-pill">
              Sessions
            </A>
            <A href="/search" class="nav-pill">
              Search
            </A>
            <A href="/recall" class="nav-pill">
              Recall
            </A>
            <A href="/projects" class="nav-pill">
              Projects
            </A>
            <A href="/stats" class="nav-pill">
              Stats
            </A>
          </nav>
          <div class="header-actions">
            <SourceChips />
            <span class={`live-pill live-pill--${live.status()}`}>
              <span class="live-dot" />
              {live.status()}
            </span>
            <button type="button" class="command-button" aria-label="Open command palette" onClick={() => setPaletteOpen(true)}>
              <span>⌘K</span>
            </button>
          </div>
        </header>
        <main class="app-main">{props.children}</main>
        <CommandPalette open={paletteOpen()} onClose={() => setPaletteOpen(false)} />
      </div>
    </LiveStoreContext.Provider>
  );
}
