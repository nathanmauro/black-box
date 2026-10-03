# Bound first-turn labels and collapsed headers

During packaged Browse acceptance, the transcript excerpt correctly hid its tail, but the session
list label and collapsed First turn header still exposed the entire 1,975-character prompt in the
accessibility tree. The header's Show all button was collapsed while its full text remained present.

Reuse the existing Unicode-safe excerpt logic for a 280-character/four-nonempty-line header preview
and a 160-character first-line label. Keep the original text for explicit expansion, filtering,
and title deduplication. Link the header toggle to its text with aria-controls and retain the
existing layout. Do not change canonical capture or API data.

The original implementation failed two focused regressions for header disclosure and bounded
Unicode labels. Verify those plus default transcript/media preview regressions, full frontend
checks/build, and actual Browse header expansion/collapse and label text before publication.

Verification completed: all 640 frontend tests pass; type, format, lint (70 existing warnings),
build, package and diff checks pass. In the packaged app with an isolated synthetic capture,
Stream → View session → Browse exposes 281 header characters and 161 list-label characters,
with the original tail absent from the accessibility tree. Enter expands the exact 1,975-character
original with correct aria-expanded/aria-controls; Space collapses it again. Filtering sessions by
the hidden tail still finds the record. At a 390-pixel viewport the document width remains 390.
The disposable process and browser tab were closed; the running local service was not changed.
