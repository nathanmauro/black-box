# Preserve numeric tool evidence during Browse merges

## Scope

NAT-308 fixes frontend transcript identity only. Distinct numeric JSON payloads must not collapse
when Browse combines search results or older pages. Stored captures, timestamps, occurrence
windows, exact IDs and backend behavior remain unchanged.

## Approach

Reproduce with actual HTTP captures and packaged Browse before changing behavior. For valid JSON
within the existing inline identity bound, scan numeric tokens outside strings and compare their
normalized decimal values with JavaScript's serialized Number values. Keep sorted-key semantic
identity only when every token roundtrips; otherwise use compact raw JSON with outside-string
whitespace removed. Tag identity categories so raw fallbacks cannot collide with invalid text.
Bound exponent work and preserve a conservative fallback for deep inputs. Keep the existing
long-payload hash policy; it is not a mathematically lossless guarantee.

## Verification

- Before/after packaged search and pagination with adjacent large integers, desktop and narrow.
- Numeric edge cases, ordinary semantic duplicate compatibility and bounded input regressions.
- Focused and full frontend tests, lint/format/types, normal generated-assets build.
- Related exact-target and linked-source browser journeys, fixture cleanup and diff check.

## Results

- Before the fix, actual HTTP captures retained both references `9007199254740992` and
  `9007199254740993`, and initial Browse displayed both. Searching `Lookup` and loading older
  events each removed one captured event from the reader. The desktop browser regression failed
  at both missing-event assertions. The new unit cases produced 13 failures with 24 controls passing.
- After the fix, 765 frontend tests passed across 61 files. The initial focused transcript/Browse
  run passed 82 tests; a later long-significand case is included in the full passing suite.
- Lint, format and TypeScript checks passed with the existing 68 warnings and no new exemptions.
- Nine packaged journeys passed together with zero retries: numeric search/pagination at desktop
  and narrow widths, exact older-source/prompt pagination, and saved-braid linked-source navigation.
  Both numeric payload strings were read back unchanged through the actual event API.
- The normal Maven frontend build regenerated the static bundle. No layout changed, so the numeric
  slice does not introduce a visual redesign or require a separate screenshot baseline.
- Fixture cleanup removed its synthetic project and temporary database; port 8799 was released.
  The protected 8766 listener remained unchanged. Its database identity was not discoverable, so
  this verification makes no production database-identity claim.

The decimal normalizer caps significant exponent digits at six and never expands powers of ten.
JSON deeper than 128 levels uses compact raw identity, avoiding recursive semantic normalization.
Inexact numbers with different key order or spelling may remain as extra duplicates. The existing
large-payload hash, duplicate-key semantics and occurrence heuristics are outside this change.

No installed service, live database, provider or Git mutation was made. The coordinator owns final
review, commit, CI, publication and integration.
