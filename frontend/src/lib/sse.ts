import { createContext, createSignal, onCleanup, useContext } from "solid-js";

export type LiveStatus = "connecting" | "live" | "down";

export type EventAppended = {
  id: string;
  sessionId: string;
  source: string;
  eventType: string;
  toolName?: string | null;
  cwd?: string | null;
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

export type AgentProcess = {
  pid: number;
  agent: string;
  cpuPercent: number;
  rssKb: number;
  elapsed: string;
};

export type ProcessesUpdated = {
  processes: AgentProcess[];
  available: boolean;
};

export type LiveStore = {
  status: () => LiveStatus;
  events: () => EventAppended[];
  processes: () => AgentProcess[];
  processesAvailable: () => boolean;
  onSessionUpdated: (callback: (event: SessionUpdated) => void) => () => void;
};

export const LiveStoreContext = createContext<LiveStore>();

export function createLiveStore(): LiveStore {
  const [status, setStatus] = createSignal<LiveStatus>("connecting");
  const [events, setEvents] = createSignal<EventAppended[]>([]);
  const [processes, setProcesses] = createSignal<AgentProcess[]>([]);
  const [processesAvailable, setProcessesAvailable] = createSignal(true);
  const sessionListeners = new Set<(event: SessionUpdated) => void>();

  if (typeof EventSource === "undefined") {
    setStatus("down");
    return {
      status,
      events,
      processes,
      processesAvailable,
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
    const payload = parseSseData<EventAppended>(message);
    if (!payload) return;
    setEvents((current) => [payload, ...current].slice(0, 50));
  });
  source.addEventListener("session.updated", (message) => {
    const payload = parseSseData<SessionUpdated>(message);
    if (!payload) return;
    for (const listener of sessionListeners) listener(payload);
  });
  source.addEventListener("processes", (message) => {
    const payload = parseSseData<ProcessesUpdated>(message);
    if (!payload) return;
    setProcesses(payload.processes);
    setProcessesAvailable(payload.available);
  });

  onCleanup(() => source.close());

  return {
    status,
    events,
    processes,
    processesAvailable,
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

export function parseSseData<T>(message: Event): T | null {
  const data = (message as MessageEvent<string>).data;
  if (!data) return null;
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}
