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

// An aria-label overrides a button's whole accessible name, so it has to carry everything the row
// visibly says (spec 4: name, live count, unseen, latest headline, kind, age) — a shorter one built
// separately from the visible text drifts out of sync (e.g. always saying "0 live" for a row that
// visibly reads "quiet") and silently drops content for assistive tech.
function projectRowLabel(card: { name: string; liveSessions: number; unseen: number; latest: { eventType: string; headline: string } | null }): string {
  const live = card.liveSessions > 0 ? `${card.liveSessions} live` : "quiet";
  const latest = card.latest ? `, latest ${card.latest.eventType}: ${card.latest.headline}` : "";
  return `${card.name}: ${live}, ${card.unseen} unseen${latest}`;
}

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
                    aria-label={projectRowLabel(card())}
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
