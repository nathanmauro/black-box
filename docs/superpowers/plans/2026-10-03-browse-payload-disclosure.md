# Browse payload disclosure and accessible text previews

Status: implementation verified in the isolated source checkout; integration-owner Browse review pending.

## Reproduced behavior

A synthetic `MCP__CUA_REPL__JS` result rendered through the actual EventRow/ToolPayload components
put a 20,000-character image data URL into the DOM and Chrome accessibility tree before any action.
The generic renderer pretty-printed its nested JSON immediately; its 360px visual limit did not
limit accessible text. Adjacent Bash Output details correctly kept content unmounted until opened
and excluded it from accessibility after closing. ReaderText separately kept all 20,044 fixture
characters accessible behind a visual-only collapsed mask.

## Scope and contract

- Leave canonical stored evidence, API responses and capture behavior unchanged.
- Lazily mount large generic Input/Result sections. Keep small ordinary payloads direct.
- Compact explicit data URLs and typed binary fields in displayed previews, retaining readable
  sibling text, field names and metadata. Do not guess that arbitrary opaque text is binary.
- Offer a deliberate second disclosure containing the exact original payload string; no automatic
  image loading, provider call, fetch, clipboard mutation or lossy evidence replacement.
- Render a bounded ReaderText excerpt while collapsed, expose accurate expansion semantics, and
  show the exact original text on expansion. Avoid hidden full text in DOM attributes.
- Reuse native details/summary controls and existing presentation styles. No broad presenter redesign.

## Verification

Add focused component/helper regressions for nested MCP media, embedded JSON strings, typed base64,
ordinary text, exact originals, initial/closed disclosures, and ReaderText length/line/Unicode limits.
Run targeted tests, full frontend checks/tests/build, regenerate static assets, and exercise the
actual changed components with synthetic browser fixtures and keyboard controls. The integration
owner separately reviews and exercises Browse before Git publication. No live data or service is
changed by this implementation lane.


## Implemented verification

- Focused helper/component regressions: 19 passed; the surrounding EventCards/BlockView regressions
  also passed. Full frontend suite: 637 tests across 58 files passed.
- `npm run check` passed (existing Solid reactivity warnings remain); `npm run build` passed and
  regenerated the committed static bundle. `git diff --check` passed.
- A loopback-only Vite fixture imported the actual changed EventRow, ReaderText and theme. Chrome
  CUA keyboard checks verified native Enter/Space disclosure behavior and accessibility state:
  the initial generic payload contained 55 DOM characters and no image bytes; its opened preview
  exposed the MIME/count placeholder and readable explanation; a second disclosure exposed the
  exact 20,123-character original. Closing the original or outer disclosure removed its respective
  contents from accessibility. ReaderText exposed 901 characters while collapsed, expanded to the
  exact 20,044-character original, then removed its tail again on collapse; `aria-expanded` matched.
- The synthetic fixture server and browser tab were stopped/closed. No live database, capture,
  provider, production service or Git publication was touched by this implementation lane.

For the integration review, seed a disposable Browse session with a `PostToolUse` event named
`MCP__CUA_REPL__JS`, a small input and a nested `content` array containing an image `data:` URL plus
readable text. Add a user message longer than 900 characters. Open the targeted session/event in
Browse; keyboard-open Result, inspect the compact preview, open Original result and compare it to
the canonical API field, then close both and check accessibility. Expand/collapse the user message
and verify the exact original and hidden-tail removal. Repeat at a narrow viewport.

## Tradeoffs

The 1,200-character threshold keeps small ordinary sections direct. Explicit original access can
still mount large evidence; this is intentional and user-controlled. Native details retains content
after its first opening, matching the existing specialized presenters; closed details excludes that
content from accessibility. No claim is made that the API response or all presenter parsing is lazy,
or that latency/heap performance has been benchmarked. Specialized presenters are unchanged.

Review additionally reproduced a stack overflow when previewing valid JSON nested 2,200 levels.
The preview now stops after 64 object/array levels with an explicit placeholder and accurate
separate note. Helper and actual-disclosure regressions verify that fixture renders safely and its
exact original remains accessible. This bound applies only to the preview traversal.

## Packaged Browse acceptance

The integration owner built the actual JAR and captured a synthetic user message and MCP tool event
into a separate temporary SQLite service with all model paths disabled. Opening the session through
Stream → View session → Browse showed an initially closed 20,200-character Result. Keyboard Enter
opened a readable media placeholder; a second disclosure exposed the canonical API payload exactly.
Closing the original and outer result removed the corresponding content from the accessibility
snapshot. The transcript paragraph expanded to the exact 1,975-character message and collapsed to
901 characters with its hidden tail removed and the expansion state correct. At 390px, both result
controls remained within the viewport and the page had no horizontal overflow. This acceptance
covers the transcript paragraph; the separate first-turn header/list labels retain existing behavior.
