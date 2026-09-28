import { createSignal } from "solid-js";

const KEY = "bb.humanOnly";

function load(): boolean {
  try {
    return localStorage.getItem(KEY) === "true";
  } catch {
    return false;
  }
}

function save(value: boolean): void {
  try {
    localStorage.setItem(KEY, String(value));
  } catch {
    // Storage failures degrade to session-only state.
  }
}

const [humanOnlySignal, setSignal] = createSignal(load());

export const humanOnly = humanOnlySignal;

export function setHumanOnly(value: boolean): void {
  setSignal(value);
  save(value);
}

export function toggleHumanOnly(): void {
  setHumanOnly(!humanOnlySignal());
}

/** First non-empty line of a human turn, for one-line list rows. */
export function leadLine(text: string | null | undefined): string | null {
  const line = text?.split(/\r?\n/).find((part) => part.trim());
  return line ? line.trim() : null;
}

/** In human mode a feed item shows the cleaned human text in place of the raw event text. */
export function withHumanText<T extends { text?: string | null; humanText?: string | null }>(
  item: T,
  active: boolean,
): T {
  return active && item.humanText ? { ...item, text: item.humanText } : item;
}
