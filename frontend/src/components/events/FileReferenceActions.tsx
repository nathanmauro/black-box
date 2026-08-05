import { createMemo, createSignal, createUniqueId, Show } from "solid-js";
import { ApiError, openInEditor, revealInFinder } from "../../lib/api";
import { useCodeNavigation } from "../../lib/codeNavigation";
import { toCodeReference } from "../../lib/codeReferences";
import type { FileRef } from "../../lib/presenters/types";

type FileReferenceActionsProps = {
  file: FileRef;
  label?: string;
};

export default function FileReferenceActions(props: FileReferenceActionsProps) {
  const navigation = useCodeNavigation();
  const reference = createMemo(() => toCodeReference(props.file, navigation.scopes()));
  const [pending, setPending] = createSignal<"open" | "reveal" | null>(null);
  const [message, setMessage] = createSignal("");
  const label = () => props.label || props.file.path;
  const unresolvedReasonId = `file-reference-reason-${createUniqueId()}`;
  const unresolvedReason = () => {
    switch (navigation.catalogStatus()) {
      case "loading":
        return "Checking eligible project roots.";
      case "error":
        return navigation.catalogError() || "Eligible project roots could not be loaded.";
      default:
        return "This path is outside the eligible project roots.";
    }
  };

  async function open(clickEvent: MouseEvent) {
    stop(clickEvent);
    const current = reference();
    if (!current || pending()) return;
    setPending("open");
    setMessage("");
    try {
      await openInEditor(current);
      setMessage("Opened in editor.");
    } catch (error) {
      setMessage(navigationErrorMessage(error, "open"));
    } finally {
      setPending(null);
    }
  }

  async function reveal(clickEvent: MouseEvent) {
    stop(clickEvent);
    const current = reference();
    if (!current || pending()) return;
    setPending("reveal");
    setMessage("");
    try {
      await revealInFinder(current);
      setMessage("Revealed in Finder.");
    } catch (error) {
      setMessage(navigationErrorMessage(error, "reveal"));
    } finally {
      setPending(null);
    }
  }

  async function copy(clickEvent: MouseEvent) {
    stop(clickEvent);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(props.file.path);
      setMessage("Path copied.");
    } catch {
      setMessage("Could not copy path.");
    }
  }

  function refresh(clickEvent: MouseEvent) {
    stop(clickEvent);
    if (navigation.catalogStatus() === "loading") return;
    setMessage("");
    navigation.refreshCatalog();
  }

  return (
    <span class="file-reference-actions" data-resolved={reference() ? "true" : "false"}>
      <Show
        when={reference()}
        fallback={<span class="inline-file-path">{label()}</span>}
      >
        <button
          type="button"
          class="inline-file-link"
          title={`Open ${props.file.path} in editor`}
          aria-label={`Open ${props.file.path} in editor`}
          disabled={Boolean(pending())}
          onClick={open}
        >
          {label()}
        </button>
      </Show>
      <span class="file-reference-fallbacks" aria-label={`File actions for ${props.file.path}`}>
        <button
          type="button"
          class="file-reference-action"
          title="Copy path"
          aria-label={`Copy path ${props.file.path}`}
          onClick={copy}
        >
          copy
        </button>
        <button
          type="button"
          class="file-reference-action"
          title="Reveal in Finder"
          aria-label={`Reveal in Finder ${props.file.path}`}
          aria-disabled={!reference() || Boolean(pending()) ? "true" : undefined}
          aria-describedby={!reference() ? unresolvedReasonId : undefined}
          disabled={Boolean(pending())}
          onClick={reveal}
        >
          finder
        </button>
        <Show when={!reference()}>
          <button
            type="button"
            class="file-reference-action"
            aria-label={`${navigation.catalogStatus() === "error" ? "Retry" : "Refresh"} eligible project roots`}
            disabled={navigation.catalogStatus() === "loading"}
            onClick={refresh}
          >
            {navigation.catalogStatus() === "error" ? "retry roots" : "refresh roots"}
          </button>
        </Show>
      </span>
      <Show when={!reference()}>
        <span id={unresolvedReasonId} class="file-reference-reason">{unresolvedReason()}</span>
      </Show>
      <span class="file-reference-status" aria-live="polite">{message()}</span>
    </span>
  );
}

function stop(event: MouseEvent) {
  event.preventDefault();
  event.stopPropagation();
}

function navigationErrorMessage(error: unknown, action: "open" | "reveal"): string {
  if (error instanceof ApiError) {
    switch (error.type) {
      case "file_missing":
        return "File no longer exists.";
      case "outside_project_root":
        return "Blocked: path is outside the project root.";
      case "project_unresolved":
        return "Project root is no longer available.";
      case "editor_disabled":
        return "Editor integration is unavailable.";
      case "reveal_unavailable":
        return "Finder integration is unavailable.";
      case "invalid_reference":
        return "File location is invalid.";
      default:
        return error.message || `Could not ${action === "open" ? "open the file" : "reveal the file"}.`;
    }
  }
  return `Could not ${action === "open" ? "open the file" : "reveal the file"}.`;
}
