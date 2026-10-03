# Honest numeric tool previews

NAT-310 makes numeric precision loss visible in generic Browse tool Input/Result previews and
provides lazy access to the exact captured payload, including small payloads. Keep existing parser
return values, specialized presenters, canonical storage and transcript identity behavior unchanged.

## Plan

1. Reproduce missing original access using actual HTTP captures and packaged Browse.
2. Extract the existing numeric identity scanner without behavior changes; add bounded, sticky
   diagnostics across each successful payload decoding layer.
3. Show a precision-loss or unchecked-precision note with Original input/result access. Safe small
   payloads remain uncluttered; large/deep previews retain existing lazy/depth boundaries.
4. Verify input/result, double encoding, escaped numeric-looking strings, overflow/underflow,
   conservative bounds and exact keyboard original access; run focused/full frontend checks and
   related packaged regressions, regenerate assets normally and record cleanup.

## Results

- Before changing behavior, the packaged desktop journey captured a small unsafe integer through
  actual HTTP, read both canonical Input/Result strings back exactly, and failed because Browse
  offered no Original input disclosure. A prior isolated EventRow fixture confirmed that the
  visible integer had rounded from `9007199254740993` to `9007199254740992`.
- The shared scanner retains NAT-308's numeric identity policy; a missing numeric token now fails
  conservatively, and negative zero is separately reported for display only. Transcript identity
  still uses a 32,768-character bound. Preview diagnostics use 131,072 UTF-16 code units per
  successfully decoded layer, without expanding powers of ten or unbounded exponent arithmetic.
- Legacy Exit code conversion is diagnosed without changing its existing output. Unsupported
  numeric forms receive an unchecked note; malformed JSON and unconverted plain text do not gain
  misleading warnings. Two decoding passes remain the maximum; a triple-encoded value is not
  speculatively parsed a third time.
- 84 focused tests passed, including all 38 unchanged transcript identity cases. Full frontend
  verification passed 794 tests across 62 files. Lint/format/TypeScript checks passed with the
  existing 68 warnings and no rule exemptions.
- Nine packaged journeys passed together with zero retries: new desktop/narrow keyboard Original
  access for Input and Result, direct and double encoding, safe-number controls, NAT-308 search and
  pagination, and saved-braid linked-source navigation. Event API readback confirmed captured
  strings stayed unchanged. The normal frontend and packaged Maven builds regenerated assets.
- Desktop and 390px screenshots were inspected: warning text and original controls stay contained;
  the browser asserts no horizontal overflow. No CSS or broader layout changes were needed.
- The isolated fixture removed its synthetic project and database, and port 8799 was released.
  Protected port 8766 kept its existing listener. Production database identity was not discoverable,
  so no production database-identity claim is made.

## Limits and handoff

The preview continues to display parsed JavaScript values with an explicit warning when changed;
Original restores access to the full captured string rather than reconstructing it. Checking is
conservative for over-budget layers and unsupported legacy numeric forms. Specialized tool
presenters and parseJsonObject metadata surfaces are outside this change. The existing transcript
long-payload hash and occurrence heuristics remain unchanged.

Fixture-only work; no installed service, live database, provider or Git changes. The coordinator
owns final review, commit, CI, publication and integration.
