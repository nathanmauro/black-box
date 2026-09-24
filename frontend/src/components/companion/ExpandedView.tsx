import { createMemo, For, Show } from "solid-js";
import type { CompanionModel, ExpandedViewState } from "../../lib/companion/model";
import { timeAgo } from "../../lib/format";
import KindBadge from "../KindBadge";

type ExpandedViewProps = {
  model: CompanionModel;
  view: ExpandedViewState;
  onBack: () => void;
  onToggleView: () => void;
  onCollapse: () => void;
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
  const title = () => (props.view.kind === "river" ? "River" : card()?.name ?? "Project");

  return (
    <div class="companion-panel companion-panel--expanded">
      <header class="companion-header">
        <button type="button" class="companion-icon-button" aria-label="Back to projects" onClick={() => props.onBack()}>
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
      <Show when={card()}>
        {(current) => (
          <p class="companion-strip">
            {current().liveSessions} live · activity {ago(current().lastActivityAt)} · capture {ago(current().lastCaptureAt)}
          </p>
        )}
      </Show>
      <Show when={items().length > 0} fallback={<p class="companion-empty">Nothing meaningful in the last 24h.</p>}>
        <ul class="companion-items">
          <For each={items()}>
            {(item) => (
              <li>
                <a class={`companion-item${item.seen ? "" : " companion-item--unseen"}`} href={item.href} target="_blank" rel="noreferrer">
                  <span class="companion-item-head">
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
    </div>
  );
}
