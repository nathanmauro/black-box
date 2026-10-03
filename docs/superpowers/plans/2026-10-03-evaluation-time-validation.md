# Refuse hour-24 recall timestamps on every Python

## Scope

NAT-318 fixes the resumption evaluation timestamp preflight only. `instant_epoch` in
`scripts/evaluation/resumption_eval.py` must refuse invalid calendar/clock fields identically on
Python 3.9 through 3.14. Offset checks, exact `Decimal` nanoseconds, original timestamp strings,
cutoff semantics, lookback, known-event selection, scoring and the usefulness gate are unchanged.
The separate history-search parser already validates components and is not touched.

## Approach

Reproduce first. Python 3.14 `datetime.fromisoformat` accepts `T24:00:00` as next-day midnight;
3.9 rejects it. Because the harness feeds `fromisoformat` exactly six fractional digits and
restores the sub-microsecond residue afterward, `.000000001` through `.000000999` also passed.
Before the existing fixed-microsecond `fromisoformat` call, construct a `datetime` from the
numeric year, month, day, hour, minute and second fields so the constructor's range checks apply
on every version. Invalid input keeps the `invalid_recall_timestamp` category.

## Verification

- Direct reproduction of hour 24 with zero, `.000`, sub-microsecond, offset and month-end inputs.
- Component regressions: twelve hour-24 inputs refused through `instant_epoch` and `recall`;
  four nanosecond/offset/month-end/leap-day controls keep exact epochs and original strings.
- CLI regression on a private ephemeral loopback fake with a scrubbed environment: four hour-24
  cases exit 2 with `invalid_recall_timestamp`, zero model requests and ten unattempted trials;
  two valid controls make ten model requests and preserve the original string in recalled
  evidence and recall-arm prompts.
- Full evaluation suite on `/usr/bin/python3` 3.9.6, Homebrew 3.13 and 3.14; syntax and diff checks.

## Results

- Before the fix, 3.14.7 accepted `2026-01-01T24:00:00Z` as epoch `1767312000`, plus the
  sub-microsecond, `+05:30`, `-04:00` and month/year-end variants; 3.9.6 refused all of them.
  The baseline suite already failed one case on 3.14.7 and passed on 3.9.6 and 3.13.
- The new tests against the base script: 16 failures on 3.14.7 (all hour-24 cases, including
  the four CLI cases reaching the model) and none on 3.9.6.
- After the fix, all 22 evaluation tests pass on 3.9.6, 3.13.15 and 3.14.7.
- The 224 differing timestamps from the independent audit, plus a generated 19,008-case matrix of
  years, month-end/invalid dates, clock edges, fractions and offsets, produce byte-identical
  results on all three interpreters with zero hour-24 acceptances. On 3.9.6 the fixed results
  equal the base results for every case; on 3.14.7 the only changed results are the 1,376 hour-24
  inputs, now refused.

This is a preflight correctness fix. It makes no claim about evaluation usefulness; the gate
remains not cleared. No real model or provider call, installed service, live database, port
8766/8799 or Git mutation was made. The coordinator owns review, commit, CI and integration.

## Coordinator acceptance

Fresh review accepted the strict component validation and preserved offset/nanosecond behavior.
A Java 21 probe corrected a comment-only claim: Java Instant can normalize zero-fraction hour 24,
so the comment now describes this evaluator's established hour range rather than claiming Java
rejects it. The coordinator independently ran all 22 evaluation tests, including the actual
ephemeral fake-provider CLI journeys, on Python 3.9.6, 3.13.15 and 3.14.7; all passed. No real
provider, installed service or live database was used.
