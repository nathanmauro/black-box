import { For } from "solid-js";
import { SOURCES, sourceLabel } from "../lib/format";
import { sourceFilter } from "../lib/stores";
import SourceDot from "./SourceDot";

export default function SourceChips() {
  return (
    <div class="source-chips" role="group" aria-label="Filter by source">
      <button
        type="button"
        classList={{ "source-chip": true, "source-chip--active": sourceFilter.selected().size === 0 }}
        onClick={() => sourceFilter.clear()}
      >
        All
      </button>
      <For each={SOURCES}>
        {(source) => (
          <button
            type="button"
            classList={{
              "source-chip": true,
              "source-chip--active": sourceFilter.selected().has(source),
              "source-chip--dim": sourceFilter.selected().size > 0 && !sourceFilter.selected().has(source),
            }}
            aria-pressed={sourceFilter.selected().has(source)}
            onClick={() => sourceFilter.toggle(source)}
          >
            <SourceDot source={source} />
            <span>{sourceLabel(source)}</span>
          </button>
        )}
      </For>
    </div>
  );
}
