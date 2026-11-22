import { useSearchParams } from "@solidjs/router";
import { createMemo, For, Show } from "solid-js";
import BroadcastList from "../components/BroadcastList";
import type { BroadcastKind, ProjectBroadcast } from "../lib/api";
import { createLiveBroadcasts } from "../lib/broadcasts";
import { truncatePath } from "../lib/format";

const KINDS: BroadcastKind[] = ["hazard", "invariant", "tentacle"];
const LIST_LIMIT = 200;

type BroadcastParams = {
  kind?: string;
  stale?: string;
  cleared?: string;
};

export default function BroadcastsPage() {
  const [params, setParams] = useSearchParams<BroadcastParams>();
  const kind = createMemo(() => KINDS.find((value) => value === params.kind));
  const staleOnly = () => params.stale === "1";
  const includeCleared = () => params.cleared === "1";

  const list = createLiveBroadcasts(() => ({
    kind: kind(),
    staleOnly: staleOnly(),
    includeCleared: includeCleared(),
    limit: LIST_LIMIT,
  }));

  const broadcasts = createMemo(() => (list.error ? [] : list().broadcasts));
  const groups = createMemo(() => groupByProject(broadcasts()));

  return (
    <section class="page ideas-page broadcasts-page">
      <header class="ideas-hero">
        <div>
          <p class="eyebrow">live project state</p>
          <h1>Broadcasts</h1>
          <p>
            What every session in a project should know right now: hazards, invariants, and
            unfinished tentacles. Agents set these with setBroadcast and clear them when the
            condition ends.
          </p>
        </div>
      </header>

      <div class="ideas-filters" role="group" aria-label="Filter broadcasts">
        <div class="ideas-chip-row" role="group" aria-label="Kind">
          <span class="ideas-chip-label">kind</span>
          <For each={KINDS}>
            {(value) => (
              <button
                type="button"
                class="ideas-chip"
                aria-pressed={kind() === value}
                onClick={() => setParams({ kind: kind() === value ? undefined : value })}
              >
                {value}
              </button>
            )}
          </For>
          <span class="ideas-chip-label">show</span>
          <button
            type="button"
            class="ideas-chip"
            aria-pressed={staleOnly()}
            onClick={() => setParams({ stale: staleOnly() ? undefined : "1" })}
          >
            stale tentacles only
          </button>
          <button
            type="button"
            class="ideas-chip"
            aria-pressed={includeCleared()}
            onClick={() => setParams({ cleared: includeCleared() ? undefined : "1" })}
          >
            include cleared
          </button>
        </div>
      </div>

      <Show when={list.error}>
        <p class="ideas-error" role="alert">
          Broadcasts unavailable: {String((list.error as Error)?.message ?? list.error)}
        </p>
      </Show>

      <Show
        when={groups().length}
        fallback={
          <Show when={!list.loading && !list.error}>
            <div class="ideas-empty">
              <h2>No broadcasts match</h2>
              <p>
                Nothing is live for these filters. Agents publish with setBroadcast; new ones appear
                here as they are set.
              </p>
            </div>
          </Show>
        }
      >
        <For each={groups()}>
          {(group) => (
            <section class="ideas-section broadcast-group" aria-label={group.scope}>
              <header class="ideas-section-head">
                <h2 title={group.scope}>{truncatePath(group.scope)}</h2>
                <span class="ideas-section-count">{group.items.length}</span>
              </header>
              <BroadcastList broadcasts={group.items} />
            </section>
          )}
        </For>
      </Show>
    </section>
  );
}

/** Groups keep the server's priority order; a group sorts by its first (most urgent) row. */
function groupByProject(broadcasts: ProjectBroadcast[]) {
  const groups = new Map<string, ProjectBroadcast[]>();

  for (const broadcast of broadcasts) {
    const items = groups.get(broadcast.projectScope) ?? [];

    items.push(broadcast);
    groups.set(broadcast.projectScope, items);
  }

  return [...groups.entries()].map(([scope, items]) => ({ scope, items }));
}
