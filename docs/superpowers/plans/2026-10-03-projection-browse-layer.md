# Keep Projection evidence in the Browse memory layer

## Contract

Projection records possibilities, not an assistant's conversational answer. Include its normalized
event type in the existing memory classifier. Default Browse keeps it hidden alongside other memory
captures; Show memory events reveals its existing event card, and exact-source links reveal and
highlight the requested evidence even with the memory toggle off. Preserve original role metadata,
canonical capture content, retrieval defaults, and every other event classification.

No taxonomy redesign, new renderer, semantic indexing, provider, or live-data change is in scope.

## Reproduction and implementation

Before the fix, two actual SessionsPage component cases failed: default Browse rendered the
Projection row, and an exact source displayed it as an agent response without a Projection badge.
The normal Observation control had already passed in the coordinator's fixture.

Add only Projection to isSessionMemoryEvent. The existing reader filter, conversation-role helper,
memory count/toggle, and generic event renderer then follow the same path as other memory evidence.
No stored event or API response changes. Captured assistant role metadata remains visible as metadata;
it does not become an agent-response heading or satisfy a missing conversational answer.

## Verification

- Focused helper/component tests: 42 passed, including normalized type spellings, default hiding,
  toggle round trip, exact-target exception/highlighting and unchanged actual conversation replies.
- Packaged journey creates a real prompt and Projection through HTTP, opens Browse, toggles memory
  using the keyboard, then follows Recall's exact-source link using Enter. It checks Projection
  labeling, alternatives/basis, the still-missing conversation answer, canonical bytes unchanged,
  onscreen evidence and viewport bounds at 1440/390px. All providers remain disabled.
- Full frontend suite: 691 tests across 60 files passed. Lint, formatting and TypeScript checks
  passed with the existing 70 lint warnings and no errors.
- New Browse and existing Projection recall/copy journeys: 4/4 passed at desktop and narrow widths,
  with zero retries (30.9 seconds). The standard Maven frontend profile rebuilt the static assets.
  The new journey used actual HTTP responses without mocks and confirmed the captured event was
  unchanged afterward.
- Desktop and narrow screenshots reviewed; no horizontal overflow. At 390px the existing stacked
  panes leave limited vertical reading space, so evidence is explicitly scrolled into view.
- Protected port 8766 listener PID was unchanged; the runner could not discover its database from
  this checkout, so production row identity was not asserted. The owned temporary database and
  project fixture were removed, and port 8799 had no listener after completion.
- Whitespace checks passed; the behavior note links to this saved plan.

## Handoff

Source-only worker; coordinator owns final review, Git, PR and merge. No live state or provider calls.
The existing generic Projection event card is reused, without a new visual design. Exact-source
highlighting is tested; screenshots explicitly scroll evidence into view and do not assert automatic
scroll placement. Narrow Browse retains its existing stacked header/session-rail layout.
