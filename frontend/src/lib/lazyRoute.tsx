import { createSignal, ErrorBoundary, lazy, Show, type Component } from "solid-js";

export function lazyRoute<Props extends object>(
  load: () => Promise<{ default: Component<Props> }>,
): Component<Props> {
  const [loading, setLoading] = createSignal(true);
  const Page = lazy(async () => {
    const module = await load();

    setLoading(false);

    return module;
  });

  return (props) => {
    // Guard only the module load: a route-wide Suspense would also hide the pages' API loaders.
    return (
      <ErrorBoundary
        fallback={
          <div class="empty-state" role="alert">
            <p>This view could not be loaded.</p>
            <button type="button" onClick={() => window.location.reload()}>
              Reload view
            </button>
          </div>
        }
      >
        <Show when={loading()}>
          <p class="empty-state" role="status">
            Loading view…
          </p>
        </Show>
        <Page {...props} />
      </ErrorBoundary>
    );
  };
}
