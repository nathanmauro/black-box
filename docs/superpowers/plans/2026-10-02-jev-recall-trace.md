# Jev Recall Trace

## Product slice

Keep immediate menu-bar recall. Add an explicit **Trace with Jev** action that identifies the
capture a query may be remembering and pairs it with relevant evidence captured later in the
same repository. Render copied source text and exact-event links, with fixed advisory labels.
The useful distinction is temporal context: an old match can be true as history while later
evidence changes how it should be interpreted. Do not claim unprecedented invention or current
truth from a bounded search.

## Implementation and safety contract

- Extend the native helper only, using existing read-only Black Box endpoints. The live Java/UI
  deployment is newer than this branch and must not be replaced.
- Use direct TypeSafe HTTPS with the current typed-question contract. Keep credentials in an
  explicit helper-owned macOS Keychain item, never in source, application resources, or launchd
  configuration. Test with synthetic evidence first; ask explicitly before the agent sends
  private retrieved evidence to the external provider.
- Ordinary typing remains local retrieval. Only the explicit trace action sends the query and
  bounded Decision/Handoff excerpts to TypeSafe; no whole-stream relay or historical backfill.
- Retrieve a broader bounded query shortlist, select an anchor with a no-match outcome, then
  retrieve and compare later same-repository captures. Code verifies IDs, repository identity,
  timestamp ordering, probabilities, and allowed relationships.
- Preserve source context and qualifying text. Labels describe what a later capture reports,
  never a global declaration that work is complete or a decision is current.
- Bound requests, timeout, concurrent work and provider calls. Query changes and dismissal
  invalidate pending work. Errors and uncertainty leave normal recall usable.
- Parent integrates isolated worker output, handles installation and live acceptance. No push,
  PR, merge, or backend deployment is part of this slice.

## Verification

Use deterministic fixtures for explicit revision, proposal versus completion, verified follow-up,
cross-project false matches, qualification/negation, malformed responses, unknown IDs and stale
responses. Run real Jev against synthetic examples before user evidence. Then exercise the
installed menu: type, trace, inspect the linked sources, alter the query during work, and keep
Return-to-Chrome functioning. Record actual provider model, call count and measured latency.

## Outcome

The native implementation and deterministic suite pass, including two-call HTTP transport,
cancellation, malformed responses, exact repository/date checks, omitted qualifications, and
installer preservation/rollback. An independent review found a UI-thread progress callback issue;
the final callback is MainActor-isolated and awaited.

The actual request builders passed all six synthetic fixture gates against `jev-1.13.0`: verified
fix, explicit reversal, proposal without implementation, unrelated work, fixture success with
production still blocked, and an embedded instruction attempting to force a completion label.
Calls took 152–385 ms per trace. A held-out retracted-verification example selected both correct
sources but used a neutral related-follow-up label instead of the expected specific label. Another
held-out incident in the same repo was correctly excluded. These small regression smokes do not
establish general accuracy or calibrated probabilities.

Installed-app acceptance against the synthetic HTTP fixture showed both source cards, scrolling,
and the exact later capture opening in Chrome. That trace made two calls in 363 ms. The final
display omits token/model diagnostics (retained in the evaluation CLI) and uses readable dates.
Reinstallation exposed an ad hoc-signing Keychain access prompt. Normal reads now suppress legacy
Keychain interaction and restore its prior setting; only explicit setup may request approval,
and refusal preserves the existing credential. The full suite passes after that repair. The final
helper is installed, explicit credential setup completed, the normal server origin is restored,
and the synthetic server is stopped. Real-history testing remains pending explicit approval to
send the bounded query/excerpts to TypeSafe. No real history was sent by this work.
