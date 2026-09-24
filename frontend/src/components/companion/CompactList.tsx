import { Index, Show } from "solid-js";
import type { CompanionModel } from "../../lib/companion/model";
import { timeAgo } from "../../lib/format";
import KindBadge from "../KindBadge";

type CompactListProps = {
  model: CompanionModel;
  loading?: boolean;
  onOpenProject: (projectKey: string) => void;
  onOpenRiver: () => void;
  onCollapse: () => void;
};

export function pulseText(model: CompanionModel): string {
  switch (model.pulse) {
    case "disconnected":
      return "Disconnected from Black Box";
    case "connecting":
      return "Connecting…";
    case "live":
      return `Live · last event ${timeAgo(model.lastEventAt)} ago`;
    default:
      return model.lastEventAt ? `Idle · last event ${timeAgo(model.lastEventAt)} ago` : "Idle";
  }
}

export default function CompactList(props: CompactListProps) {
  return (
    <div class="companion-panel">
      <header class="companion-header">
        <span class="companion-title">Projects</span>
        <button type="button" class="companion-link-button" onClick={() => props.onOpenRiver()}>
          River
        </button>
        <button type="button" class="companion-icon-button" aria-label="Collapse" onClick={() => props.onCollapse()}>
          –
        </button>
      </header>
      <Show when={!props.loading} fallback={<p class="companion-empty">Loading…</p>}>
        <Show when={props.model.projects.length > 0} fallback={<p class="companion-empty">Quiet. No active projects in the last 24h.</p>}>
          <ul class="companion-projects">
            <Index each={props.model.projects}>
              {(card) => (
                <li>
                  <button
                    type="button"
                    class="companion-project-row"
                    aria-label={`${card().name}: ${card().unseen} unseen, ${card().liveSessions} live`}
                    onClick={() => props.onOpenProject(card().key)}
                  >
                    <span class={`companion-live-dot${card().liveSessions > 0 ? " companion-live-dot--on" : ""}`} aria-hidden="true" />
                    <span class="companion-project-name">{card().name}</span>
                    <span class="companion-project-meta">{card().liveSessions > 0 ? `${card().liveSessions} live` : "quiet"}</span>
                    <Show when={card().unseen > 0}>
                      <span class="companion-count">{card().unseen}</span>
                    </Show>
                    <Show when={card().latest}>
                      {(latest) => (
                        <span class="companion-project-latest">
                          <KindBadge kind={latest().eventType} />
                          <span class="companion-project-headline">{latest().headline}</span>
                          <span class="companion-age">{timeAgo(latest().observedAt)}</span>
                        </span>
                      )}
                    </Show>
                  </button>
                </li>
              )}
            </Index>
          </ul>
        </Show>
      </Show>
      <footer class={`companion-footer companion-footer--${props.model.pulse}`}>{pulseText(props.model)}</footer>
    </div>
  );
}
