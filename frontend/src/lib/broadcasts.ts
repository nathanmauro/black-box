import { createResource, onCleanup, useContext, type Accessor } from "solid-js";
import { getBroadcasts, type BroadcastListParams, type BroadcastListResponse } from "./api";
import { LiveStoreContext, type LiveStore } from "./sse";

const EMPTY: BroadcastListResponse = { broadcasts: [], count: 0, staleAfterHours: 72, asOf: "" };

/**
 * The broadcast list for `params`, refetched whenever the stream reports a set or clear. A null
 * params value skips the request (no project selected yet).
 */
export function createLiveBroadcasts(
  params: Accessor<BroadcastListParams | null>,
  // The app shell owns the store before its context provider exists, so it passes it directly.
  live: LiveStore | undefined = useContext(LiveStoreContext),
) {
  const [list, { refetch }] = createResource(params, (query) => getBroadcasts(query), {
    initialValue: EMPTY,
  });

  // Every change triggers a reload: the list endpoint owns ordering, staleness, and expiry.
  const unsubscribe = live?.onBroadcastChanged?.(() => void refetch());

  if (unsubscribe) {
    onCleanup(unsubscribe);
  }

  return list;
}

/** Items that need a human's eye: hazards above info, blockers, and stale tentacles. */
export function needsAttention(broadcast: { severity: string; stale?: boolean }): boolean {
  return broadcast.severity !== "info" || Boolean(broadcast.stale);
}
