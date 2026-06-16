# Session Finding Workbench Implementation Plan

**Goal:** Turn the read-only Projects slice into a usable session-finding workbench. The sidebar should be project-first, searchable, and collapsible. Session traces should default to compact, collapsible rows with a right-side outline for files, tools, and event shape.

**Scope:** Static web UI only. No schema changes, no durable meld storage, no capture-hook behavior changes, and no claim of semantic project intelligence beyond the existing derived `cwd` project read model.

## Tasks

- [x] Add static contract coverage for the new UI shell: project rail, fuzzy session search, summary popup, session outline, minimap, and collapsed event rows.
- [x] Replace the folder-list feel in the left rail with project-key grouping backed by `/api/projects`, while preserving the no-project/manual/system bucket.
- [x] Add fuzzy filtering across project label, canonical path, session title, source, client session id, and `cwd`; auto-expand matching groups while search is active.
- [x] Make the rail collapsible and persist that state in local storage.
- [x] Render session events as collapsed rows by default. Tool rows show the derived action and target instead of the raw hook event type.
- [x] Render summaries as a bounded preview with a modal popup for the full text.
- [x] Add a right-side session outline with a compact minimap, files edited, files read, tools used, and event-shape sections. Outline sections can pop out into a focused dialog.
- [x] Fix the mobile layout by stacking the outline before the trace, bounding the long trace in its own scroll area, and removing horizontal overflow.

## Verification

- `mvn -Dtest=StaticUiContractTest test`
- Browser desktop check against `http://127.0.0.1:8767`
- Browser mobile viewport check at `390x844`
