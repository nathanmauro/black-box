const KEY = "bb.streamDensity";

export type StreamDensity = "collapsed" | "expanded";

export function loadStreamDensity(): StreamDensity {
  try {
    return localStorage.getItem(KEY) === "expanded" ? "expanded" : "collapsed";
  } catch {
    return "collapsed";
  }
}

export function saveStreamDensity(density: StreamDensity): void {
  try {
    localStorage.setItem(KEY, density);
  } catch {
    // Private-mode storage failures degrade to session-only density.
  }
}
