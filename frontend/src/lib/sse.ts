import { createContext, createSignal, onCleanup, useContext } from "solid-js";

export type LiveStatus = "connecting" | "live" | "down";

export type EventAppended = {
  sessionId: string;
  source: string;
  eventType: string;
  toolName?: string | null;
  title?: string | null;
  observedAt: string;
};

export type SessionUpdated = {
  sessionId: string;
  source: string;
  title?: string | null;
  cwd?: string | null;
  eventCount?: number;
  lastSeenAt?: string | null;
};

export type LiveStore = {
  status: () => LiveStatus;
  events: () => EventAppended[];
  onSessionUpdated: (callback: (event: SessionUpdated) => void) => () => void;
};

export const LiveStoreContext = createContext<LiveStore>();

export function createLiveStore(): LiveStore {
  const [status, setStatus] = createSignal<LiveStatus>("connecting");
  const [events, setEvents] = createSignal<EventAppended[]>([]);
  const sessionListeners = new Set<(event: SessionUpdated) => void>();

  if (typeof EventSource === "undefined") {
    setStatus("down");
    return {
      status,
      events,
      onSessionUpdated: (callback) => {
        sessionListeners.add(callback);
        return () => sessionListeners.delete(callback);
      },
    };
  }

  const source = new EventSource("/api/stream");

  source.onopen = () => setStatus("live");
  source.onerror = () => setStatus("down");
  source.addEventListener("event.appended", (message) => {
    const payload = parseSse<EventAppended>(message);
    if (!payload) return;
    setEvents((current) => [payload, ...current].slice(0, 50));
  });
  source.addEventListener("session.updated", (message) => {
    const payload = parseSse<SessionUpdated>(message);
    if (!payload) return;
    for (const listener of sessionListeners) listener(payload);
  });

  onCleanup(() => source.close());

  return {
    status,
    events,
    onSessionUpdated: (callback) => {
      sessionListeners.add(callback);
      return () => sessionListeners.delete(callback);
    },
  };
}

export function useLiveStore(): LiveStore {
  const store = useContext(LiveStoreContext);
  if (!store) {
    throw new Error("LiveStoreContext is missing");
  }
  return store;
}

function parseSse<T>(message: Event): T | null {
  const data = (message as MessageEvent<string>).data;
  if (!data) return null;
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}
