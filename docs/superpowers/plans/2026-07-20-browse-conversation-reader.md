# Browse Conversation Reader

## Goal

Make Browse read like a conversation instead of a filtered event log. The reader must show each
captured user prompt together with the agent response, while keeping the project-scoped flat session
rail introduced by the Activity project-context work.

Add a compact conversation navigator inspired by the Codex conversation map: user prompts remain
the stable navigation anchors, hovering or focusing an anchor magnifies it and previews both sides
of that exchange, and activating it scrolls to the full turn.

## Observed Gap

The frontend already accepts canonical `AssistantMessage` events and `role=assistant` archive
events. Live hook events use a broader vocabulary, though: historical response text is commonly
stored as `eventType=Stop`, `role=agent`, and prompt/tool event names also have casing and separator
variants. The existing reader classifier drops those response-shaped `Stop` events.

This slice must therefore solve both presentation and compatibility:

- treat text-bearing response/stop events as agent messages;
- recognize prompt and tool event-name variants without treating tool output as conversation text;
- preserve explicitly typed archive events such as `response_item` with `role=user|assistant`;
- derive precise roles for new generic hook events at ingest time while preserving explicit roles;
- do not mutate or backfill the live SQLite database.

## Interaction Contract

### Reader

- Render one semantic turn per user prompt, in chronological order.
- Render the user prompt as a `You` message and each captured assistant response as an agent message
  labelled with the event source (`Codex`, `Claude`, or another source label).
- Keep memory cards behind the existing opt-in toggle and keep tool telemetry out of the default
  reader.
- Preserve Find-to-Browse event targeting and target highlighting.
- Merge adjacent duplicate prompt captures from hook and transcript/archive sources when their
  normalized text matches and they share a turn id or arrive within two minutes before a response.
- If a session has assistant output before its first captured prompt, render a preamble turn rather
  than discarding it.

### Conversation Navigator

- Show one compact anchor per prompt on the reader edge.
- Clicking an anchor scrolls the owning turn into view without replacing Activity URL state.
- Hover and keyboard focus magnify the current anchor and its nearest neighbors with restrained,
  spring-like motion.
- A preview card shows the prompt and the latest captured agent-response excerpt, which represents
  the final response when a source emits both intermediate and final output.
- Track the turn nearest the reading position with `aria-current` when IntersectionObserver is
  available; keep click/focus behavior functional without it.
- Respect `prefers-reduced-motion`.
- On narrow layouts, switch to a horizontal sticky strip rather than overlaying the transcript.

## Implementation

1. Extend the session reader's event classifiers with normalized event-type matching.
2. Replace generic user/assistant event cards inside the reader with a small conversation-message
   renderer while leaving structured memory cards on the existing `EventRenderer` path.
3. Replace the hover-only prompt menu with an always-present `ConversationNavigator` that receives
   grouped turns, previews prompt/response excerpts, magnifies nearby anchors, and scrolls within
   the timeline pane.
4. Update the shared hook normalizer to emit `user`, `assistant`, and `tool` roles for known hook
   shapes. Also normalize generic `role=agent` requests in the ingest service so current machine
   wrappers gain the same behavior without requiring a database rewrite.
5. Update repo-owned setup documentation only where the event role contract is described.

## Verification

- Frontend tests prove canonical assistant messages, historical text-bearing `Stop` responses,
  prompt-name variants, turn grouping, preview content, navigation, and keyboard focus.
- Event-ingest tests prove generic hook roles are normalized while explicit roles are preserved.
- Smoke-test `scripts/hooks/sba-agent-hook.sh` with synthetic prompt, stop, and tool fixtures against
  a fake `curl` capture.
- Run the focused frontend tests, complete frontend test suite and build, targeted Maven tests,
  complete Maven suite, `git diff --check`, and a candidate UI smoke check on an isolated port.
- Do not deploy or restart the launchd-owned live service until the candidate build is verified.
