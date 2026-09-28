import { For, Show } from "solid-js";
import { ideaValueLabel, LEGS_MAX, normalizeOrigin, safeIdeaLink } from "../lib/ideas";

export function IdeaOriginBadge(props: { origin: string | null | undefined }) {
  const origin = () => normalizeOrigin(props.origin) || "unknown";
  return (
    <span class={`idea-origin idea-origin--${origin()}`} title="origin">
      {ideaValueLabel(origin())}
    </span>
  );
}

export function IdeaStatusPill(props: { status: string | null | undefined }) {
  const status = () => props.status || "untouched";
  return (
    <span class={`idea-status idea-status--${status()}`} title="status">
      {ideaValueLabel(status())}
    </span>
  );
}

/** Legs as a 0–10 meter; the accessible name reads "legs N/10". */
export function IdeaLegsMeter(props: { legs: number | null | undefined }) {
  return (
    <Show when={props.legs != null}>
      <span class="idea-legs">
        <span aria-hidden="true">legs</span>
        <meter
          min="0"
          max={LEGS_MAX}
          value={props.legs ?? 0}
          aria-label={`legs ${props.legs}/${LEGS_MAX}`}
        />
        <span aria-hidden="true">
          {props.legs}/{LEGS_MAX}
        </span>
      </span>
    </Show>
  );
}

export function IdeaConnects(props: { items: string[] }) {
  return (
    <Show when={props.items.length}>
      <ul class="idea-connects" aria-label="connects">
        <For each={props.items}>{(item) => <li>{item}</li>}</For>
      </ul>
    </Show>
  );
}

/** Captured link rendered safely: web links open in a new tab, obsidian:// opens the app. */
export function IdeaLink(props: { link: string | null | undefined }) {
  const safe = () => safeIdeaLink(props.link);
  return (
    <Show
      when={safe()}
      fallback={
        <Show when={props.link?.trim()}>
          <span class="idea-link idea-link--plain">{props.link}</span>
        </Show>
      }
    >
      {(link) => (
        <a
          class="idea-link"
          href={link().href}
          target={link().external ? "_blank" : undefined}
          rel={link().external ? "noopener noreferrer" : undefined}
        >
          {link().href}
        </a>
      )}
    </Show>
  );
}
