# Complete Session Transcripts

## Goal

Make Browse show the selected session's available conversation and tool activity without requiring
a Stream deep link, then add fast search inside that transcript. Keep the read path local,
session-scoped, and non-mutating.

## Observed gap

- Browse deliberately filters every tool-shaped event unless it is the exact Stream target.
- Hook-backed session events do not contain all assistant messages. The selected session already
  carries a known `transcript_path`, and its Codex/Claude JSONL contains the missing messages and
  tool records.
- The session rail asks for 2,000 rows even though the REST controller clamps it to 250; Browse then
  renders that whole recent slice and searches only those rows client-side.

## Scope

1. Add a read-only, cursor-paged session transcript endpoint. It hard-binds every query to the
   selected internal session id, reuses the indexed event feed for recorded activity, and enriches
   the page with user/assistant messages read from a transcript path already stored on that session.
   Path reads are confined to the supported Codex/Claude transcript roots.
2. Cache parsed results by canonical path, file size, and modification time. Return an honest
   availability/completeness status rather than failing the whole session when a transcript is
   missing, rotated, malformed, or beyond the safety bound.
3. Prefer recorded event IDs and hook tool payloads. Add only missing user/assistant transcript
   messages and remove obvious duplicate prompt/response pairs within the same turn/time window.
   Do not eagerly duplicate raw transcript tools: recorded tool payloads are already structured and
   the live evidence shows eager pages can exceed 13 MB.
4. Show tool events in Browse by default. Keep structured memory events behind their existing
   opt-in toggle.
5. Add in-session search across prompt text, assistant text, tool names, tool inputs, and tool
   outputs, with match counts and next/previous navigation. Search is always server-bound to the
   selected session and reaches beyond the currently loaded page.
6. Reduce the idle session rail to a bounded recent slice while preserving direct hydration of an
   exact session URL. Request 50 transcript events at a time so tool-heavy sessions stay responsive;
   older events remain reachable by cursor and search still spans the full session server-side.

## Out of scope

- No live database writes, transcript backfill, filesystem watcher, usage/cost accounting, or
  Observatory migration.
- No exposure of system/developer messages or model reasoning records.
- No global semantic-search redesign. Existing Stream search remains the corpus-wide search plane.
- No eager replay of unclipped historical tool output. A later detail-on-demand endpoint can use
  stored call ids for that without burdening the default reader.

## Verification

- Parser tests cover representative Codex and Claude messages, malformed lines,
  missing files, path confinement, and cache refresh after append.
- Web tests cover transcript availability and session ownership/not-found behavior.
- Browse tests prove tools no longer depend on `targetEventId`, transcript-only assistant messages
  appear once, search covers tool payloads and conversation text, and direct old-session hydration
  still works.
- Run focused frontend and Maven tests, full frontend tests/build, relevant Maven suite, and
  `git diff --check`.
- Package and run the candidate on an isolated port, then exercise the real Browse route in the
  collaborative browser before deploying the verified build to port 8766.
