import { createMemo, For, Show } from "solid-js";
import type { CompanionModel, ExpandedViewState } from "../../lib/companion/model";
import { timeAgo } from "../../lib/format";
import KindBadge from "../KindBadge";
import { pulseText } from "./CompactList";

type ExpandedViewProps = {
  model: CompanionModel;
  view: ExpandedViewState;
  onBack: () => void;
  onToggleView: () => void;
  onCollapse: () => void;
  // Lets CompanionPage hand focus to the Back button when the user opens a project or the river,
  // instead of leaving focus on <body>.
  focusRef?: (el: HTMLButtonElement) => void;
};

function ago(iso: string | null): string {
  return iso ? `${timeAgo(iso)} ago` : "none";
}

export default function ExpandedView(props: ExpandedViewProps) {
  const card = createMemo(() => {
    const view = props.view;
    return view.kind === "project" ? props.model.projects.find((project) => project.key === view.projectKey) ?? null : null;
  });
  const items = createMemo(() => (props.view.kind === "river" ? props.model.river : card()?.items ?? []));
  // The view carries its own projectName (set once, when opened) so a project whose card ages out of
  // the model while its view stays open keeps a real title and strip instead of "Project" with none.
  const title = () => (props.view.kind === "river" ? "River" : props.view.projectName);

  return (
    <div class="companion-panel companion-panel--expanded">
      <header class="companion-header">
        <button type="button" class="companion-icon-button" aria-label="Back to projects" ref={props.focusRef} onClick={() => props.onBack()}>
          ‹
        </button>
        <span class="companion-title">{title()}</span>
        <button type="button" class="companion-link-button" onClick={() => props.onToggleView()}>
          {props.view.kind === "river" ? "By project" : "River"}
        </button>
        <button type="button" class="companion-icon-button" aria-label="Collapse" onClick={() => props.onCollapse()}>
          –
        </button>
      </header>
      <Show when={props.view.kind === "project"}>
        <p class="companion-strip">
          {card()?.liveSessions ?? 0} live · activity {ago(card()?.lastActivityAt ?? null)} · capture {ago(card()?.lastCaptureAt ?? null)}
        </p>
      </Show>
      <Show
        when={items().length > 0}
        // Spec 3.1: disconnected must never look like quiet. An empty river/project reads as calm
        // only when the stream is actually live; while disconnected, say so instead.
        fallback={<p class="companion-empty">{props.model.pulse === "disconnected" ? "Disconnected from Black Box." : "Nothing meaningful in the last 24h."}</p>}
      >
        <ul class="companion-items">
          <For each={items()}>
            {(item) => (
              <li>
                <a class={`companion-item${item.seen ? "" : " companion-item--unseen"}`} href={item.href} target="_blank" rel="noreferrer">
                  <span class="companion-item-head">
                    {/* The unseen border color is a visual-only cue (WCAG 1.4.1); say it too. */}
                    <Show when={!item.seen}>
                      <span class="visually-hidden">Unseen</span>
                    </Show>
                    <KindBadge kind={item.eventType} />
                    <Show when={props.view.kind === "river"}>
                      <span class="companion-item-project">{item.projectName}</span>
                    </Show>
                    <span class="companion-age">
                      {item.source} · {timeAgo(item.observedAt)}
                    </span>
                  </span>
                  <span class="companion-item-headline">{item.headline}</span>
                  <Show when={item.nextAction}>
                    <span class="companion-next">Next: {item.nextAction}</span>
                  </Show>
                  <Show when={item.openLoops.length > 0}>
                    <span class="companion-loops">
                      {item.openLoops.length} open loop{item.openLoops.length === 1 ? "" : "s"}
                    </span>
                  </Show>
                </a>
              </li>
            )}
          </For>
        </ul>
      </Show>
      <footer class={`companion-footer companion-footer--${props.model.pulse}`}>{pulseText(props.model)}</footer>
    </div>
  );
}
