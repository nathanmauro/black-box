# Public documentation and showcase polish

## Purpose

Make the README and supporting documentation useful to a public reader: a clear capture-and-recall
story, a practical first run, accurate release/platform boundaries, and current reproducible media.
This is documentation and showcase work, not a product expansion or a deployment.

## Independently verifiable slices

1. Rewrite the README around the reader's first use and add a grouped documentation index.
2. Audit active guides against source; clarify installation, optional integrations, data flows,
   prototypes, and historical material without weakening technical contracts.
3. Regenerate the demo and three README screenshots from current source using synthetic records.
   Give important terminal results deliberate reading time, and document regeneration.
4. Record one evidence-backed example of Black Box being used during its own development, with
   explicit limits on the claim.
5. Check local links/anchors, review rendered text and images, exercise the real demo/browser path,
   run relevant script/frontend checks, and complete CI before integration.

## Preservation contract

Use isolated checkouts and disposable storage. Do not modify the installed service, real history,
credentials, unrelated work, or cloud infrastructure. Keep public examples machine-neutral. Preserve
historical engineering evidence, but label it clearly so old plans do not become current setup advice.
The published v0.2.0 artifact is older than the source used for this showcase; do not imply otherwise.

## Verification

Verified against the integrated documentation and scripts on 2026-10-03:

- Audited active guide claims against source, including release/platform support, model egress,
  voice alias paths, gateway authentication, canonical readback, and stream replay.
- Checked 312 local file/heading references across 49 documents; no unresolved targets.
- Rendered the README at 1280 px and 390 px widths: all images loaded, with no document overflow.
  Reviewed the three current UI screenshots and the GIF's important frames visually.
- Ran 18 packaged Chromium journeys, including the opt-in showcase capture, exact recalled
  Handoff-to-Browse navigation, smoke, continuity, evidence recall, and project/session recall.
- Ran 18 gateway protocol tests against disposable local fake servers. Validated the bundled skill.
- Ran all 9 demo/recorder fixture tests with Python 3.9 and the development Python. The shutdown
  regression first reproduced both failures against the earlier wrapper; corrected behavior preserves
  scratch state and reports failure when shutdown or ownership cannot be confirmed.
- Ran the actual recorder on its isolated port and database. It completed the capture-and-recall
  loop, stopped its process, and removed its temporary directory. The resulting 20.81-second GIF
  holds the recalled evidence for 5.02 seconds and the final instructions for 6 seconds.
- Frontend lint, formatting, and TypeScript checks passed with the existing 68 lint warnings.
  Shell syntax and `git diff --check` passed. No application static bundle changed.

The first PR CI run exposed three existing stream tests that assumed a shared seed stayed on the
first 100-result page. Its trace showed a valid continuation cursor and the older fixture beyond
that page; the showcase test was skipped and had made no captures. The tests now capture their own
fresh records, keep the unfiltered default landing check, and assert ordering, visibility, expansion,
rationale, and exact event/session navigation. The corrected full local browser suite passed
91 journeys with only the opt-in showcase skipped; that showcase was verified separately above.

These checks did not qualify native Windows use or display refresh rates. GIF timing was measured
from encoded frame delays. No installed service, real history, or cloud infrastructure was changed;
the installed listener retained its original PID throughout. Full PR CI remains the merge gate.
