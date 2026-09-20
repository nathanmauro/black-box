# Black Box continuation study

## Authorized scope and completion contract

Implement and start the proposed five-scenario, four-condition, three-repetition exploratory
study using the existing signed-in Codex CLI. Preserve unrelated work and the live service.
No deployment, publication, host configuration changes, or live history ingestion.

## Design frozen before model execution

- Five authored miniature repositories: resumable exports, webhook reconciliation, invoice
  adjustments, configuration inheritance, and migration routing. Their histories come from
  actual Codex attempts, independently graded. Authored reference solutions are smoke-only.
- A predecessor agent fixes an earlier task. Save its candidate, command trace, explanation,
  and external grade. If it fails, allow one fresh repair attempt with failure feedback. Retain
  both attempts; never silently replace failed histories with reference implementations.
- All continuation arms inherit exactly the same final predecessor code, current repository
  documentation, prompt, public tests, model, effort, and wall-clock budget.
- Conditions: no history; a useful ordinary handoff; the same handoff round-tripped through
  Black Box; and a deliberately misleading synthetic handoff. The false control is explicitly
  labeled in the manifest, its edits recorded, and never captured into the live service. It is
  a stress test, not a length-matched causal ablation. The two useful-memory conditions intentionally
  have identical evidence. This isolates continuity and tests capture/recall fidelity; it
  cannot establish retrieval superiority over an equally good handoff.
- Current-contract changes appear in repository documentation available to every arm. No
  hidden requirement may be available only through memory. Hidden grading remains external.
- Predecessor and development seeds differ from evaluation seeds. Freeze code, corpus hashes,
  schedule and settings before evaluation. Repetitions share task families and histories and
  are not 60 independent problems; report paired outcomes by family and descriptive variation.
- Difficulty check: five bare development continuations. Proceed to the 60-run evaluation
  only if 1–4 of 5 pass. At 0/5 or 5/5, retain the evidence and stop the costly evaluation;
  report the floor/ceiling instead of tuning against the held-out evaluation outcomes.
- Primary outcome: all private checks passed within the fixed budget. Secondary: per-check
  failures, tokens, model time, end-to-end time, retrieval and history-production overhead.
  Missing metrics remain unknown; timeouts fail the fixed-budget outcome; infrastructure
  failures stop further model spending and remain visible in the schedule.

## Verification and final state

Test contracts and deliberately broken behavior, context equality, history provenance,
failure handling, schedule integrity, and real Docker capture/recall/grade paths. Run the
development gate and, if passed, the complete evaluation. Keep local artifacts under ignored
target/benchmarks; document results and a scoped Black Box handoff. Leave changes local.

## Observed result: 2026-09-18

- Implemented `continuation.py`, `continuation_fixtures.py`, and `test_continuation.py` beside the
  initial benchmark, reusing its isolated Docker lifecycle and external grading boundary.
- Plain handoff/Black Box payload equality is enforced. Misleading controls are explicit
  counterfactuals in the manifest. Webhook v1/v2 supplies a genuine historical contract change.
- Verification: 27 Python tests; full Docker smoke with five histories, five bare screens and
  20 evaluation conditions; read-only CLI plan; whitespace checks; independent read-only review.
- Real run: `target/benchmarks/continuation-study-v1/`, GPT-6 Astra medium, 180-second budget,
  five predecessor attempts, all passed. Their inherited code failed 5–7 new continuation checks.
- Five real bare continuations all passed. The fixed gate returned `ceiling_detected`; all 60
  evaluation rows remain `not_run`, and no comparison-arm model calls were made.
- History production consumed 290599 reported input tokens and 5512 output tokens. The bare
  screen consumed 309711 input tokens and 8843 output tokens. Input totals include cached tokens.
- This establishes execution/provenance and a ceiling on this task set, not a memory advantage.
  Efficiency remains untested. Prefer actual repository bug snapshots next; do not tune against
  held-out comparison outcomes or claim failed-command counts are repeated reasoning errors.
- Changes remain local. Benchmark data stayed in disposable storage and ignored artifacts;
  no deployment, live-service restart, upstream push, or policy change occurred.
