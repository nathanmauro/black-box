import { createMemo, createResource, For, Show } from "solid-js";
import KindBadge from "../components/KindBadge";
import SourceDot from "../components/SourceDot";
import { getDashboardStats, type DashboardBreakdown, type DashboardStats } from "../lib/api";

export default function StatsPage() {
  const [stats, { refetch }] = createResource(getDashboardStats);
  const hasData = createMemo(() => {
    const resolved = stats();
    if (!resolved) return false;
    return resolved.totalSessions > 0 || resolved.totalEvents > 0;
  });

  return (
    <section class="page stats-page">
      <header class="stats-hero">
        <div>
          <p class="eyebrow">store statistics</p>
          <h1>Activity shape across Black Box</h1>
          <p>Counts, source mix, event kinds, and recent event volume from the local SQLite store.</p>
        </div>
      </header>

      <Show when={stats.error}>
        {(error) => (
          <p class="inline-error stats-error">
            Stats failed: {errorMessage(error())}
            <button type="button" onClick={() => void refetch()}>
              Retry
            </button>
          </p>
        )}
      </Show>

      <Show when={!stats.loading} fallback={<StatsSkeleton />}>
        <Show when={!stats.error}>
          <Show
            when={stats()}
            fallback={
              <div class="stats-empty">
                <p class="eyebrow">empty</p>
                <h2>No stored activity yet</h2>
                <p>Stats will populate after agents write sessions and events.</p>
              </div>
            }
          >
            {(resolved) => (
              <Show
                when={hasData()}
                fallback={
                  <div class="stats-empty">
                    <p class="eyebrow">empty</p>
                    <h2>No stored activity yet</h2>
                    <p>Stats will populate after agents write sessions and events.</p>
                  </div>
                }
              >
                <StatsDashboard stats={resolved()} />
              </Show>
            )}
          </Show>
        </Show>
      </Show>
    </section>
  );
}

function StatsDashboard(props: { stats: DashboardStats }) {
  const maxDaily = createMemo(() => Math.max(1, ...props.stats.recentActivity.map((item) => item.count)));

  return (
    <>
      <section class="stats-total-grid" aria-label="Headline totals">
        <TotalCard label="Total sessions" value={`${formatCount(props.stats.totalSessions)} sessions`} />
        <TotalCard label="Total events" value={`${formatCount(props.stats.totalEvents)} events`} />
      </section>

      <section class="stats-grid">
        <BreakdownPanel title="events by source" items={props.stats.eventsBySource} variant="source" />
        <BreakdownPanel title="events by kind" items={props.stats.eventsByKind} variant="kind" />
        <BreakdownPanel title="sessions by source" items={props.stats.sessionsBySource} variant="source" />
        <section class="stats-panel stats-panel--wide">
          <div class="stats-panel-title">
            <span class="eyebrow">recent activity</span>
            <span>{props.stats.recentActivity.length.toLocaleString()} days</span>
          </div>
          <Show when={props.stats.recentActivity.length} fallback={<p class="empty-state">No recent events in the last 14 days.</p>}>
            <div class="stats-daily-list">
              <For each={props.stats.recentActivity}>
                {(item) => (
                  <div class="stats-daily-row">
                    <time>{item.day}</time>
                    <div class="stats-bar-track" aria-hidden="true">
                      <span style={{ width: `${Math.max(4, (item.count / maxDaily()) * 100)}%` }} />
                    </div>
                    <strong>{formatCount(item.count)}</strong>
                  </div>
                )}
              </For>
            </div>
          </Show>
        </section>
      </section>
    </>
  );
}

function TotalCard(props: { label: string; value: string }) {
  return (
    <article class="stats-total-card">
      <span>{props.label}</span>
      <strong>{props.value}</strong>
    </article>
  );
}

function BreakdownPanel(props: { title: string; items: DashboardBreakdown[]; variant: "source" | "kind" }) {
  const maxCount = createMemo(() => Math.max(1, ...props.items.map((item) => item.count)));
  return (
    <section class="stats-panel">
      <div class="stats-panel-title">
        <span class="eyebrow">{props.title}</span>
        <span>{props.items.length.toLocaleString()}</span>
      </div>
      <Show when={props.items.length} fallback={<p class="empty-state">No {props.title} yet.</p>}>
        <div class="stats-breakdown-list">
          <For each={props.items}>
            {(item) => (
              <div class="stats-breakdown-row">
                <span class="stats-breakdown-label">
                  <Show when={props.variant === "source"} fallback={<KindBadge kind={item.name} />}>
                    <SourceDot source={item.name} />
                    <strong>{item.name}</strong>
                  </Show>
                </span>
                <div class="stats-bar-track" aria-hidden="true">
                  <span style={{ width: `${Math.max(4, (item.count / maxCount()) * 100)}%` }} />
                </div>
                <strong>{formatCount(item.count)}</strong>
              </div>
            )}
          </For>
        </div>
      </Show>
    </section>
  );
}

function StatsSkeleton() {
  return (
    <>
      <section class="stats-total-grid" aria-label="Loading stats">
        <div class="stats-skeleton stats-skeleton--total" />
        <div class="stats-skeleton stats-skeleton--total" />
      </section>
      <section class="stats-grid">
        <div class="stats-skeleton stats-skeleton--panel" />
        <div class="stats-skeleton stats-skeleton--panel" />
        <div class="stats-skeleton stats-skeleton--panel" />
        <div class="stats-skeleton stats-skeleton--panel stats-panel--wide" />
      </section>
    </>
  );
}

function formatCount(value: number): string {
  return value.toLocaleString();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
