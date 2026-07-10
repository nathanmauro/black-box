import { createRoot } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createLiveStore } from "./sse";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  private listeners = new Map<string, Array<(event: Event) => void>>();

  constructor(_url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close() {}

  emit(type: string, data: string) {
    for (const listener of this.listeners.get(type) ?? []) listener(new MessageEvent(type, { data }));
  }
}

afterEach(() => {
  FakeEventSource.instances = [];
  vi.unstubAllGlobals();
});

describe("Activity live store isolation", () => {
  it("continues applying Activity frames after malformed and unknown task frames", () => {
    vi.stubGlobal("EventSource", FakeEventSource);

    createRoot((dispose) => {
      const store = createLiveStore();
      const source = FakeEventSource.instances[0]!;

      source.emit("task.created", "{not-json");
      source.emit("task.unknown", JSON.stringify({ task: { id: "task-1" } }));
      source.emit("event.appended", "{not-json");
      source.emit("event.appended", JSON.stringify({
        id: "event-1",
        sessionId: "session-1",
        source: "codex",
        eventType: "assistant",
        observedAt: "2026-07-10T00:00:00Z",
      }));

      expect(store.events()).toHaveLength(1);
      expect(store.events()[0]?.id).toBe("event-1");
      dispose();
    });
  });
});
