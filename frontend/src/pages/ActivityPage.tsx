import { useSearchParams } from "@solidjs/router";
import { createEffect, createSignal, For, Show } from "solid-js";
import SessionsPage from "./SessionsPage";
import SearchPage, { type SearchMode } from "./SearchPage";

type ActivityMode = "browse" | SearchMode;

const MODES: Array<{ id: ActivityMode; label: string; hint: string }> = [
  { id: "browse", label: "Browse", hint: "session rail and prompt reader" },
  { id: "find", label: "Find", hint: "faceted event search" },
  { id: "ask", label: "Ask", hint: "synthesized answer with citations" },
];

export default function ActivityPage() {
  const [params, setParams] = useSearchParams<{ q?: string; view?: string }>();
  const [mode, setModeSignal] = createSignal<ActivityMode>(modeFromParams(params));

  createEffect(() => setModeSignal(modeFromParams(params)));

  function selectMode(next: ActivityMode) {
    setModeSignal(next);
    if (next === "browse") {
      setParams({ q: undefined, view: undefined });
    } else {
      setParams({ view: next });
    }
  }

  return (
    <section class="activity-page">
      <header class="activity-header">
        <div class="activity-title">
          <p class="eyebrow">workspace</p>
          <h1>Activity</h1>
          <p>Browse sessions, find events, and ask recorded memory from one surface.</p>
        </div>

        <div class="activity-mode-tabs" role="tablist" aria-label="Activity mode">
          <For each={MODES}>
            {(item) => (
              <button
                type="button"
                role="tab"
                aria-label={item.label}
                aria-selected={mode() === item.id}
                classList={{ "activity-mode-tab": true, "activity-mode-tab--active": mode() === item.id }}
                onClick={() => selectMode(item.id)}
              >
                <span>{item.label}</span>
                <small>{item.hint}</small>
              </button>
            )}
          </For>
        </div>
      </header>

      <div class="activity-workspace">
        <Show
          when={mode() === "browse"}
          fallback={<SearchPage mode={mode() as SearchMode} showModeTabs={false} />}
        >
          <SessionsPage />
        </Show>
      </div>
    </section>
  );
}

function modeFromParams(params: { q?: string; view?: string }): ActivityMode {
  if (params.view === "ask") return "ask";
  if (params.view === "find" || params.q?.trim()) return "find";
  return "browse";
}
