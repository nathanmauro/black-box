import { A, useSearchParams } from "@solidjs/router";
import { createEffect, createMemo, createResource, createSignal, For, on, Show } from "solid-js";
import {
  IdeaConnects,
  IdeaLegsMeter,
  IdeaLink,
  IdeaOriginBadge,
  IdeaStatusPill,
} from "../components/IdeaBadges";
import ProjectPicker from "../components/ProjectPicker";
import SourceDot from "../components/SourceDot";
import { getIdeas, getProjects, type IdeaStatus, type IdeaView } from "../lib/api";
import { timeAgo, truncatePath } from "../lib/format";
import {
  IDEA_ORIGINS,
  IDEA_STATUSES,
  ideaValueLabel,
  isUnanswered,
  parseOriginParam,
  parseStatusParam,
} from "../lib/ideas";
import { recalledItemHref } from "../lib/recall";
import { sourceFilter } from "../lib/stores";

// The list endpoint caps at 500; ask for all of it so the unanswered section is not truncated by
// the default page size.
const IDEAS_LIMIT = 500;

type IdeaParams = {
  status?: string;
  origin?: string;
  q?: string;
  project?: string;
};

export default function IdeasPage() {
  const [params, setParams] = useSearchParams<IdeaParams>();
  const statuses = createMemo(() => parseStatusParam(params.status));
  const origin = createMemo(() => parseOriginParam(params.origin));
  const query = createMemo(() => (params.q ?? "").trim());
  const project = createMemo(() => (params.project ?? "").trim());
  const filtered = createMemo(() => statuses().length > 0 || origin() !== null || !!query());

  const [draft, setDraft] = createSignal(query());
  createEffect(on(query, (next) => setDraft(next)));

  const [projects] = createResource(getProjects, { initialValue: [] });

  const [items, setItems] = createSignal<IdeaView[] | null>(null);
  const [loading, setLoading] = createSignal(false);
  const [error, setError] = createSignal<string | null>(null);
  let requestToken = 0;

  async function load() {
    const token = ++requestToken;
    setLoading(true);
    setError(null);
    try {
      const response = await getIdeas({
        status: statuses(),
        origin: origin() ?? undefined,
        project: project() || undefined,
        q: query() || undefined,
        limit: IDEAS_LIMIT,
      });
      if (token === requestToken) setItems(response.items ?? []);
    } catch (err) {
      if (token === requestToken) {
        // Drop any earlier rows so a failure shows only the error, never rows from another query.
        setItems(null);
        setError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      if (token === requestToken) setLoading(false);
    }
  }

  // Refetch whenever the URL-held filter state changes (keyed on the normalized values, so an
  // unknown status that parses away does not trigger a second identical request).
  createEffect(
    on(
      () => [statuses().join(","), origin(), project(), query()].join("|"),
      () => {
        // Clear the previous query's rows so they never sit under the new filter's heading while
        // this request is in flight.
        setItems(null);
        void load();
      },
    ),
  );

  const visible = createMemo(() => sourceFilter.matches(items() ?? []));
  const unanswered = createMemo(() => visible().filter(isUnanswered));
  const others = createMemo(() => visible().filter((idea) => !isUnanswered(idea)));
  // The global source filter can hide every idea the server returned.
  const hiddenBySource = createMemo(() => (items()?.length ?? 0) > visible().length);
  const emptyState = createMemo(() => {
    if (filtered())
      return {
        title: "No ideas match these filters",
        hint: "Clear a filter or search for different words.",
      };
    if (project())
      return {
        title: "No ideas in this project",
        hint: "Pick All projects to see ideas from every project.",
      };
    if (hiddenBySource())
      return {
        title: "No ideas from the selected sources",
        hint: "Change the source filter to see ideas from other agents.",
      };
    return {
      title: "No ideas captured yet",
      hint: "Agents capture ideas with captureIdea; they appear here once recorded.",
    };
  });

  function toggleStatus(status: IdeaStatus) {
    const current = statuses();
    const next = current.includes(status)
      ? current.filter((value) => value !== status)
      : IDEA_STATUSES.filter((value) => value === status || current.includes(value));
    setParams({ status: next.length ? next.join(",") : undefined });
  }

  function selectOrigin(next: string) {
    setParams({ origin: origin() === next ? undefined : next });
  }

  function submitSearch(event: SubmitEvent) {
    event.preventDefault();
    setParams({ q: draft().trim() || undefined });
  }

  let searchInput: HTMLInputElement | undefined;

  function clearFilters() {
    setDraft("");
    setParams({ status: undefined, origin: undefined, q: undefined });
    // The button unmounts once nothing is filtered; keep keyboard focus in the filter form.
    searchInput?.focus();
  }

  return (
    <section class="page ideas-page">
      <header class="ideas-hero">
        <div>
          <p class="eyebrow">captured ideas</p>
          <h1>Ideas</h1>
          <p>
            Proposals agents made and asides people dropped that nobody acted on yet. Each row is
            the latest capture for its idea.
          </p>
        </div>
        <ProjectPicker
          projects={projects.error ? [] : projects()}
          selectedProjectKey={project() || undefined}
          loading={projects.loading}
          error={projects.error ? "Unable to load projects." : null}
          allDescription="Ideas from every project"
          onSelect={(projectKey) => setParams({ project: projectKey })}
        />
      </header>

      <form class="ideas-filters" role="search" aria-label="Filter ideas" onSubmit={submitSearch}>
        <div class="ideas-search">
          <label for="ideas-q" class="visually-hidden">
            Search ideas
          </label>
          <input
            ref={searchInput}
            id="ideas-q"
            type="search"
            value={draft()}
            onInput={(event) => setDraft(event.currentTarget.value)}
            placeholder="Search titles, quotes, and notes"
            autocomplete="off"
          />
          <button type="submit" class="secondary-action">
            Search
          </button>
          <Show when={filtered()}>
            <button type="button" class="ideas-clear" onClick={clearFilters}>
              Clear filters
            </button>
          </Show>
        </div>
        <div class="ideas-chip-row" role="group" aria-label="Status">
          <span class="ideas-chip-label">status</span>
          <For each={IDEA_STATUSES}>
            {(status) => (
              <button
                type="button"
                class="ideas-chip"
                aria-pressed={statuses().includes(status)}
                onClick={() => toggleStatus(status)}
              >
                {ideaValueLabel(status)}
              </button>
            )}
          </For>
        </div>
        <div class="ideas-chip-row" role="group" aria-label="Origin">
          <span class="ideas-chip-label">origin</span>
          <For each={IDEA_ORIGINS}>
            {(value) => (
              <button
                type="button"
                class="ideas-chip"
                aria-pressed={origin() === value}
                onClick={() => selectOrigin(value)}
              >
                {ideaValueLabel(value)}
              </button>
            )}
          </For>
        </div>
      </form>

      <Show when={error()}>
        {(message) => (
          <div class="inline-error ideas-error" role="alert">
            <span>Ideas failed to load: {message()}</span>
            <button type="button" class="secondary-action" onClick={() => void load()}>
              Retry
            </button>
          </div>
        )}
      </Show>

      <section class="ideas-results" aria-live="polite" aria-busy={loading()}>
        <Show
          when={items()}
          fallback={
            <Show when={!error()}>
              <p class="empty-state">Loading ideas…</p>
            </Show>
          }
        >
          <Show
            when={visible().length}
            fallback={
              <div class="ideas-empty">
                <p class="eyebrow">nothing here</p>
                <h2>{emptyState().title}</h2>
                <p>{emptyState().hint}</p>
              </div>
            }
          >
            <Show
              when={!filtered()}
              fallback={
                <IdeaSection
                  title="Matching ideas"
                  count={visible().length}
                  items={visible()}
                  label="Matching ideas"
                />
              }
            >
              <Show when={unanswered().length}>
                <IdeaSection
                  title="Nobody answered these"
                  hint="agent-proposed and untouched"
                  count={unanswered().length}
                  items={unanswered()}
                  label="Nobody answered these"
                  highlight
                />
              </Show>
              <Show when={others().length}>
                <IdeaSection
                  title={unanswered().length ? "Everything else" : "All ideas"}
                  count={others().length}
                  items={others()}
                  label={unanswered().length ? "Everything else" : "All ideas"}
                />
              </Show>
            </Show>
          </Show>
        </Show>
      </section>
    </section>
  );
}

function IdeaSection(props: {
  title: string;
  hint?: string;
  count: number;
  items: IdeaView[];
  label: string;
  highlight?: boolean;
}) {
  return (
    <section
      classList={{ "ideas-section": true, "ideas-section--highlight": !!props.highlight }}
      aria-label={props.label}
    >
      <h2 class="ideas-section-head">
        <span>{props.title}</span>
        <span class="ideas-section-count">{props.count.toLocaleString()}</span>
        <Show when={props.hint}>
          <small>{props.hint}</small>
        </Show>
      </h2>
      <div class="ideas-list">
        <For each={props.items}>{(idea) => <IdeaRow idea={idea} />}</For>
      </div>
    </section>
  );
}

function IdeaRow(props: { idea: IdeaView }) {
  const idea = () => props.idea;
  const connects = () => idea().connects ?? [];

  return (
    <article class="idea-row" aria-label={idea().title} data-idea-key={idea().ideaKey}>
      <div class="idea-row-head">
        <SourceDot source={idea().source} />
        <strong class="idea-title">{idea().title}</strong>
        <IdeaOriginBadge origin={idea().origin} />
        <IdeaStatusPill status={idea().status} />
        <time dateTime={idea().capturedAt} title={idea().capturedAt}>
          {timeAgo(idea().capturedAt)}
        </time>
      </div>
      <p class="idea-one-liner">{idea().oneLiner}</p>
      <div class="idea-badges">
        <IdeaLegsMeter legs={idea().legs} />
        <Show when={idea().revisions > 1}>
          <span class="idea-meta-pill">{idea().revisions} revisions</span>
        </Show>
        <Show when={idea().migratedFrom}>
          {(from) => (
            <span class="idea-meta-pill" title={`Migrated from observation ${from()}`}>
              migrated
            </span>
          )}
        </Show>
      </div>
      <Show when={idea().quote}>
        <blockquote class="idea-quote">{idea().quote}</blockquote>
      </Show>
      <IdeaConnects items={connects()} />
      <Show when={idea().resumeStep}>
        <p class="idea-resume">
          <span>resume</span> {idea().resumeStep}
        </p>
      </Show>
      <Show when={idea().notes}>
        <details class="idea-notes">
          <summary>notes</summary>
          <p>{idea().notes}</p>
        </details>
      </Show>
      <div class="idea-row-foot">
        <Show when={idea().repo}>
          <span>{truncatePath(idea().repo)}</span>
        </Show>
        <Show when={idea().sourceRef}>
          <span>from {idea().sourceRef}</span>
        </Show>
        <IdeaLink link={idea().link} />
        <A class="idea-session-link" href={recalledItemHref(idea())}>
          Capturing session <span aria-hidden="true">→</span>
        </A>
      </div>
    </article>
  );
}
