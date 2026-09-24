import { createMemo, For } from "solid-js";
import { useLocation, useSearchParams } from "@solidjs/router";
import { SOURCES, sourceLabel } from "../lib/format";
import { parseQuery, setFacet } from "../lib/query";
import { sourceFilter } from "../lib/stores";
import SourceDot from "./SourceDot";

// The Sources menu is mode-aware by contract (spec §7, D11): when the Stream is the active surface
// it reads and writes the URL q's source facet — the one visible filter language — while on
// Sessions/Recall/Graph it keeps the client-side sourceFilter signal. The split is deliberate,
// named, and temporary: migrating the other surfaces to q is a recorded follow-up, not this spec.
export default function SourceChips() {
  const location = useLocation();
  const [params, setParams] = useSearchParams<{ q?: string; view?: string }>();

  const streamSurface = createMemo(
    () =>
      location.pathname === "/stream" ||
      (location.pathname === "/" && params.view !== "browse" && params.view !== "ask"),
  );
  // The grammar matches source values case-insensitively, so chips compare lowercased — a
  // hand-typed source:Codex presses the Codex chip instead of duplicating into Codex,codex.
  const streamState = createMemo(() => (streamSurface() ? parseQuery(params.q ?? "") : null));
  const querySources = createMemo(() => streamState()?.facets.source ?? []);
  const queryExcludedSources = createMemo(() => streamState()?.excludeFacets.source ?? []);

  const selectionEmpty = () =>
    streamSurface()
      ? querySources().length === 0 && queryExcludedSources().length === 0
      : sourceFilter.selected().size === 0;
  const isSelected = (source: string) =>
    streamSurface()
      ? querySources().some((value) => value.toLowerCase() === source)
      : sourceFilter.selected().has(source);

  function toggle(source: string) {
    if (!streamSurface()) {
      sourceFilter.toggle(source);
      return;
    }
    const current = querySources();
    const next = isSelected(source)
      ? current.filter((value) => value.toLowerCase() !== source)
      : [...current, source];
    setParams({ q: setFacet(params.q ?? "", "source", next.length ? next : null) || undefined });
  }

  function clear() {
    if (!streamSurface()) {
      sourceFilter.clear();
      return;
    }
    const withoutIncludes = setFacet(params.q ?? "", "source", null);
    setParams({ q: setFacet(withoutIncludes, "source", null, "exclude") || undefined });
  }

  return (
    <div class="source-chips" role="group" aria-label="Filter by source">
      <button
        type="button"
        classList={{ "source-chip": true, "source-chip--active": selectionEmpty() }}
        onClick={clear}
      >
        All
      </button>
      <For each={SOURCES}>
        {(source) => (
          <button
            type="button"
            classList={{
              "source-chip": true,
              "source-chip--active": isSelected(source),
              "source-chip--dim": !selectionEmpty() && !isSelected(source),
            }}
            aria-pressed={isSelected(source)}
            onClick={() => toggle(source)}
          >
            <SourceDot source={source} />
            <span>{sourceLabel(source)}</span>
          </button>
        )}
      </For>
    </div>
  );
}
