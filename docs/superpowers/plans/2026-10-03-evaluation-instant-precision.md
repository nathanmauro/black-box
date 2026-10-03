# Accept Java Instant timestamps in the offline evaluation harness

## Problem and safety contract

The documented Python 3.9 runtime rejects nanosecond `observedAt` strings in the evaluation
harness's direct `datetime.fromisoformat` call. A synthetic real-CLI reproduction on Python 3.9.6
returned `invalid_recall_timestamp`, exit 2, zero fake-model requests, and ten unattempted trials.
Existing fake-server coverage used whole-second timestamps and missed the compatibility gap.

Only the timestamp parser, focused tests, and behavior documentation change. All verification
uses synthetic manifests and disposable fake loopback servers. No real model/provider, canonical
database, historical study artifact, deployment, or Git publication is touched.

## Implementation

- Require an explicit `Z` or `±HH:MM` zone and a valid calendar date/time; accept zero through nine
  fractional digits. Reject missing timezone, malformed/overlong fractions, and invalid offsets.
- Pad or truncate the datetime parser input to six fractional digits for Python 3.9 compatibility.
- Retain the residual nanoseconds in decimal epoch arithmetic for cutoff comparison. Merely
  truncating those digits could admit evidence slightly after the historical cutoff.
- Preserve the original timestamp in evidence and prompts, and preserve numeric timestamp support.
- Keep exact-source, lexical-only, nontruncated recall requirements and the existing lookback.
  Sources outside the server's lookback remain missing evidence; do not add a new wall-clock
  limit to the historical study. Negative results and the `not_cleared` usefulness gate remain.

## Verification

- Before the fix, the new regression cases fail on supported fractional timestamps and the real
  CLI; they also show that the former parser accepted missing zones and malformed offsets.
- Python 3.9.6: all 20 evaluation tests and 27 benchmark/continuation tests pass.
- Coverage includes 0/1/3/6/7/9 fractional digits, UTC and positive/negative offsets, retained source
  bytes, exact cutoff equality, one-nanosecond-late refusal, pre-epoch timestamps, numeric input,
  invalid calendar/clock/zone/fraction values, and nonfinite or nonnumeric values.
- Actual subprocess CLI proof uses the documented arguments, owner-only synthetic manifests,
  and fake HTTP recall/model endpoints: nanosecond evidence completes all ten fake trials with
  exit 0; future, malformed, and missing/stale evidence each exit 2 with zero fake-model requests
  and ten unattempted trials. Every case retains `usefulness_gate.status = not_cleared`.
- Temporary fixture artifacts and fake servers are cleaned up. Python syntax and diff checks
  pass; coordinator read-only review and Git integration remain separate.

See [the evaluation guide](../../real-resumption-evaluation.md) for the supported contract.
