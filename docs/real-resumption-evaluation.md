# Local evaluation of real-work resumptions

`scripts/evaluation/resumption_eval.py` compares five historical project checkpoints with and
without recalled handoffs. It uses a local OpenAI-compatible inference endpoint and Black Box's
real `/api/recall` endpoint. Python 3.9+ and the standard library are sufficient for the controller.
This is separate from the authored coding tasks in [the memory benchmark](memory-benchmark.md).

The bounded work is **checkpoint reconstruction**: recover the accepted checkpoint, technical
constraints, and verification surface before continuing work. It does not execute suggested
commands, edit task repositories, or prove autonomous implementation, real-world acceptance,
productivity improvement, or a retrieval-ranking advantage.

## Privacy contract

- Only explicitly configured numeric loopback HTTP origins are accepted. DNS aliases, HTTPS,
  credentials in URLs, paths on origins, redirects, and environment HTTP proxies are rejected
  or disabled. No cloud CLI, API key, remote model, model downloader, or external command is used.
- **Verify that the endpoint actually performs local inference.** Loopback alone cannot detect a
  local proxy forwarding prompts to a cloud provider. Use a trusted local runtime with installed
  weights and offline mode. The harness cannot enforce another process's egress or logging policy.
- Private manifests must be regular owner-only files. Outputs go into a **new directory outside
  every Git checkout**, with directory mode `0700` and files `0600`. Symlink output components and
  existing output directories are refused. Use a local location outside synced document folders.
- Private artifacts contain prompts, handoffs, expected answers, model responses, source references,
  and timing. Do not publish them. File permissions protect against other ordinary users, not the
  owner, administrator, backups, or a compromised local service.
- The public JSON/Markdown report is constructed from fixed labels and numerical aggregates. It
  includes no free-text task titles, model names, file paths, event IDs, handoffs, model answers,
  or exception bodies. Review any additional material before sharing it.
- Black Box reads may append ordinary recall telemetry. No history is ingested, no canonical DB
  is edited, no deployment occurs, and no running service is restarted by this harness. Model
  runtime logs and any telemetry remain under their existing local retention policies.

## Preparing five tasks

Select five distinct genuine historical handoffs before inference. Use immutable source evidence
or independently checked outcomes to define a small answer key. A handoff is a historical report,
not independent proof of current deployment or publication. Keep historical/current times explicit.
Avoid choosing only cases that favor recall; record selection rationale and related task families.

Create an owner-only JSON manifest outside the checkout. The schema is:

```json
{
  "schema_version": 1,
  "tasks": [{
    "id": "task-01",
    "event_id": "00000000-0000-0000-0000-000000000001",
    "prompt": "Resume the historical feature checkpoint audit.",
    "as_of": "Describe the historical cutoff and current source revision.",
    "cutoff_epoch": 1800000000,
    "provenance": "Selection rationale and independent evidence provenance.",
    "shared_context": "Current source snippets and repository search evidence available to both arms.",
    "facts": [{
      "key": "verification",
      "question": "Which exact relative test-file path verifies this contract?",
      "expected": "tests/test_feature.py",
      "evidence": "Independent immutable source revision:path and verification receipt."
    }]
  }]
}
```

This abbreviated schema example is synthetic and **not runnable evidence**. A run requires five
entries named `task-01` through `task-05`, each with its real unique event ID, a cutoff timestamp,
and supported answers. The validator checks completeness and structure; a human must establish
that provenance is genuine and the rubric is fair. It cannot validate a citation's truth merely
because it is a nonempty string. Use exact, unambiguous questions; equivalent free-form wording is
not graded semantically.

Both arms receive identical task instructions, source context, fact questions, model, token cap,
and temperature. The private answer key and provenance are excluded from prompts. The recall arm
alone receives the selected historical handoff. Each request is a fresh conversation with no other
chat history or tools. A fixed seed counterbalances arm order (three baseline-first, two recall-first)
and randomizes task order. Temperature zero does not guarantee identical hardware-level results;
this is one exploratory pair per task, not a statistical significance test.

The harness fetches by **known exact event ID**, requires lexical recall, verifies kind and time,
and excludes other returned hits. This intentionally isolates the value of supplying an existing
handoff. It does not test finding the right handoff in an unknown corpus, semantic ranking, or
whether an agent would choose to recall on its own. Older handoffs may fall outside the supported
lookback; missing evidence stops the run before inference.

## Run and verify

```bash
python3 -m unittest discover -s scripts/evaluation -p 'test_*.py' -v

# Substitute your own private paths and already-installed local model identifier.
python3 scripts/evaluation/resumption_eval.py /private/local-study/cases.json \
  --output /private/local-study/run-001 \
  --model-origin http://127.0.0.1:1234 \
  --recall-origin http://127.0.0.1:8766 \
  --model local-model-id --seed 42 --timeout 120 --within-hours 8760
```

The output parent must already exist. Each invocation creates a new run; it never overwrites an
old result. `frozen.json` records the input manifest, its hash, the harness hash, model settings,
endpoints and schedule before any inference. `recalled.json` preserves the exact historical input,
its hash and retrieval time. Numbered prompts/responses/results plus `trials.json` preserve all ten
scheduled trials. `report.json` and `report.md` are sanitized aggregates. Exit 0 means all ten were
graded, **not** that a usefulness gate passed; exit 2 means refusal or an incomplete run.

Scoring counts exact correct fields, missing/abstained fields, incorrect fields, and extra keys.
A checkpoint passes only when all requested facts match and there are no extra assertions.
Malformed or truncated model output fails that checkpoint. Infrastructure failures stop further
inference, retain the error privately, and leave all remaining trials explicitly unattempted.
Unknown token usage stays `null`. Reported latency is inference wall time; per-handoff retrieval
time is separate in private evidence. No monetary cost or whole-task completion speed is inferred.
Incorrect fields are bounded rubric errors, not a comprehensive hallucination audit of free text.

## Usefulness gate

The previously proposed product gate requires twenty historical candidates and five resumed tasks,
an ordinary latest-handoff/search comparator using the same model/source window, at least 70%
useful supported suggestions, below 10% stale/duplicate suggestions, and three useful accepted
actions that comparator missed. Acceptance must be observed; a model score cannot substitute for it.

This narrower five-pair no-recall audit **cannot clear that gate**, even if all recall trials win.
The report therefore returns `not_cleared` with the missing evidence listed. That means usefulness
is not established by this experiment; it is not proof that Black Box is useless. Extend the study
with independently adjudicated suggestions, the ordinary-context comparator, and actual accepted
continuations before claiming a product-level pass. Do not tune answers after seeing held-out results.

## Local pilot, 2026-09-18

[Machine-readable aggregate result](evaluation-results/2026-09-18-real-resumption.json).
Five pre-existing handoffs selected from twenty scoped candidates; four predefined source-backed
facts per task; one pair per task, seed 42. Merely selecting from twenty does not constitute the
required twenty-candidate evaluation. Cases covered transcript/read-half integration, graph
integration/art, capped trajectory counts, Timeline query indexing, and secure file navigation.
All were from one repository, with related graph work, so they are not independent task families.

Qwen3 4B Instruct 2507 MLX 4-bit ran locally with temperature zero, a 1,000-token response cap,
a 120-second request timeout, and a fresh conversation per request. A temporary loopback MLX
runtime used already-installed weights with Hugging Face/Transformers offline mode and telemetry
disabled; its prompt cache was disabled. The runtime was stopped after the comparison. The model,
source/rubric hashes, exact evidence, raw answers and weight fingerprint are retained privately.

| Outcome | No recall | Recalled handoff |
| --- | ---: | ---: |
| Completely correct checkpoints | 0 / 5 | 3 / 5 |
| Exact value-and-schema matches | 12 / 20 | 17 / 20 |
| Missing facts / abstentions | 3 | 2 |
| Nonmatching values or types | 5 | 1 |
| Extra answer keys | 0 | 0 |
| Reported input tokens | 12,427 | 14,924 |
| Reported output tokens | 209 | 213 |
| Inference wall time, seconds | 11.950 | 13.419 |

Recall improved exact-match count in four pairs and tied one; no pair lost exact matches.
One baseline mismatch was the correct integer `720` supplied as a JSON number instead of the
required string. That is a formatting defect, not an unsupported factual claim. The frozen strict
scorer was retained: these counts measure schema-conforming answers, not pure factual accuracy.
The complete-checkpoint result is unaffected by this distinction.
All ten trials were graded, without malformed output or infrastructure errors in the completed run.
An earlier LM Studio invocation failed at model loading before producing any graded result. That
incomplete run was retained separately; the same frozen five cases were rerun with the installed
weights through the temporary MLX runtime. No rubric was changed after seeing model answers.

**Verdict: the usefulness gate is not cleared.** This pilot supports a narrow checkpoint-recovery
benefit, with additional context and slightly more inference time. Correct predefined facts are
not the same metric as useful supported suggestions, and paired score improvements are not
accepted actions. The ordinary-handoff/search comparator, twenty-candidate adjudication, and
accepted continuation outcomes remain unmeasured. Do not turn 17/20 exact matches into a claim
that the 70% suggestion threshold passed.

Verification: 16 standard-library tests cover real fake-server HTTP interactions, privacy controls,
input/output failures, private artifact permissions, paired context equality and schedule, score
accounting, and the conservative gate. The actual ten-trial CLI run exercised capture-free live
recall, local inference, artifact persistence and report generation. Application code, live JAR,
existing historical records, and unrelated benchmark work were unchanged by this implementation.
A separate local continuity handoff records the work; read operations also emit normal recall telemetry.
