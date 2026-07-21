import { createEffect, createSignal, For, onCleanup, type JSX } from "solid-js";
import { A, useLocation, useSearchParams } from "@solidjs/router";
import CommandPalette from "./components/CommandPalette";
import SourceChips from "./components/SourceChips";
import { createLiveStore, LiveStoreContext } from "./lib/sse";

type AppProps = {
  children?: JSX.Element;
};

type UtilityLinkId = "stream" | "browse" | "projects" | "board" | "recall";

const UTILITY_LINKS: Array<{ id: UtilityLinkId; href: string; label: string; icon: UtilityIconKind }> = [
  { id: "stream", href: "/", label: "Stream", icon: "activity" },
  { id: "browse", href: "/?view=browse", label: "Browse", icon: "browse" },
  { id: "projects", href: "/projects", label: "Projects", icon: "projects" },
  { id: "board", href: "/board", label: "Board", icon: "board" },
  { id: "recall", href: "/recall", label: "Recall", icon: "recall" },
];

export default function App(props: AppProps) {
  const live = createLiveStore();
  const location = useLocation();
  const [params] = useSearchParams<{ view?: string }>();
  const [paletteOpen, setPaletteOpen] = createSignal(false);
  const [sourcesOpen, setSourcesOpen] = createSignal(false);

  createEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", handler);
    onCleanup(() => window.removeEventListener("keydown", handler));
  });

  return (
    <LiveStoreContext.Provider value={live}>
      <div class="app-shell">
        <header class="app-utility-bar" aria-label="Black Box utility bar">
          <div class="utility-cluster">
            <A href="/" class="brand utility-brand" aria-label="Black Box overview">
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

            <nav class="utility-nav" aria-label="Utility">
              <For each={UTILITY_LINKS}>
                {(item) => (
                  <A
                    href={item.href}
                    activeClass=""
                    class={utilityLinkClass(item, location.pathname, params.view)}
                    aria-label={item.label}
                    title={item.label}
                  >
                    <UtilityIcon kind={item.icon} />
                  </A>
                )}
              </For>
            </nav>

            <div class="sources-menu">
              <button
                type="button"
                class="utility-icon-button sources-menu-trigger"
                aria-label="Filter sources"
                aria-expanded={sourcesOpen()}
                aria-controls="source-filter-panel"
                title="Filter sources"
                onClick={() => setSourcesOpen((open) => !open)}
              >
                <UtilityIcon kind="sources" />
              </button>
              <div id="source-filter-panel" class="sources-menu-panel" hidden={!sourcesOpen()}>
                <span class="sources-menu-title">Sources</span>
                <SourceChips />
              </div>
            </div>

            <span class={`live-pill utility-status live-pill--${live.status()}`} aria-label={`Connection status ${live.status()}`}>
              <span class="live-dot" />
              {live.status()}
            </span>
            <button type="button" class="command-button utility-command-button" aria-label="Open command palette" onClick={() => setPaletteOpen(true)}>
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

function utilityLinkClass(item: (typeof UTILITY_LINKS)[number], pathname: string, view: string | undefined): string {
  const active =
    item.id === "stream"
      ? pathname === "/" && !view
      : item.id === "browse"
        ? pathname === "/" && view === "browse"
        : item.id === "projects"
          ? pathname === "/projects" || pathname.startsWith("/projects/")
          : pathname === item.href;
  return active ? "utility-icon-link active" : "utility-icon-link";
}

type UtilityIconKind = "activity" | "browse" | "projects" | "board" | "recall" | "sources";

function UtilityIcon(props: { kind: UtilityIconKind }) {
  if (props.kind === "activity") {
    return (
      <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M3 13.5h3.2l1.8-7 3.2 9 2.1-5H17" />
      </svg>
    );
  }

  if (props.kind === "browse") {
    return (
      <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M4 5.4h12" />
        <path d="M4 10h12" />
        <path d="M4 14.6h12" />
        <path d="M7 3.8v12.4" />
      </svg>
    );
  }

  if (props.kind === "projects") {
    return (
      <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M3.2 6.2h5l1.4-2h7.2v11.6H3.2z" />
        <path d="M3.2 8.6h13.6" />
      </svg>
    );
  }

  if (props.kind === "board") {
    return (
      <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
        <rect x="3.2" y="4" width="5.6" height="12" rx="1" />
        <rect x="11.2" y="4" width="5.6" height="7.5" rx="1" />
        <path d="M5.2 7h1.6M13.2 7h1.6M5.2 10h1.6" />
      </svg>
    );
  }

  if (props.kind === "recall") {
    return (
      <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M6.5 5.4a5.4 5.4 0 1 1-1.2 6" />
        <path d="M5.4 3.2v2.6h2.7" />
      </svg>
    );
  }

  return (
    <svg class="utility-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d="M3.2 5.4h13.6" />
      <path d="M3.2 10h13.6" />
      <path d="M3.2 14.6h13.6" />
      <circle cx="7.2" cy="5.4" r="1.5" />
      <circle cx="12.6" cy="10" r="1.5" />
      <circle cx="9.4" cy="14.6" r="1.5" />
    </svg>
  );
}
