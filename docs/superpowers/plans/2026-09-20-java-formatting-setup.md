# Opt-in Java formatting setup

## Scope and frozen acceptance

Provide a pinned, repeatable Maven formatter on JDK 21. Use Palantir formatting, not Eclipse.
Add blank lines before real return statements with a syntax-aware step; preserve literal values,
comments, and Java semantics. Keep the tooling opt-in with no normal Maven lifecycle gate.

The latest user sequencing supersedes the originally proposed one-file pilot: do not format any
application Java source. Validate only disposable fixtures. The user will run source formatting
after the pending pushes. The coordinator owns Git publication, review, and integration.

## Plan

1. Verify official Spotless/Palantir documentation, pin versions, and verify the actual JDK.
2. Add an opt-in Maven profile and a small JDK AST-based native formatter step.
3. Exercise literal/text-block/comment/return fixtures, malformed input, semantics, and repeat-run
   idempotence through the real Maven apply/check path.
4. Document exact commands, constraints, future project-wide use, and agent instructions.
5. Confirm all existing Java source bytes remain unchanged and hand off for fresh review.

## Verification

- Verified actual Oracle GraalVM JDK 21.0.12 and Maven 3.9.16, and worker write/process
  capability in the isolated checkout. No Git writes or live services were involved.
- Checked official Spotless and Palantir documentation and Maven Central metadata. Pinned
  Spotless 3.10.2 and Palantir 2.98.0; the real Maven pipeline runs on that JDK.
- The standard Palantir fixture retained a same-line unbraced return and did not insert the
  requested blank lines. The custom step now uses public JDK AST return positions and narrowly
  bounded trivia intervals; it does not search arbitrary Java with a regular expression.
- `python3 scripts/format/test_java_format.py`: nine tests passed in 14.295 seconds. Coverage
  includes first/nested returns, leading/trailing comments, literal and text-block safety,
  colon switch cases, unbraced if/else and loops, labels, CRLF, inline return expansion, and
  malformed or Unicode-escaped trivia rejection without transformed output.
- The real Maven check failed on an unformatted disposable fixture, then apply/check succeeded.
  A second complete apply produced identical bytes. Compiling/running the fixture before and
  after produced the same output. SHA-256 checks confirmed all 431 existing `src/**/*.java`
  files remained byte-for-byte unchanged.
- `mvn -q validate` passed without a formatting goal. `git diff --check` passed.
- The source helper was itself formatted through a disposable copy while being authored; no
  existing application or test source was selected for apply. The requested one-file application
  pilot was cancelled by the user's later sequencing instruction.
- Coordinator review found that leading comments also needed coverage for colon switch cases and
  unbraced control bodies. The helper and fixtures were extended before acceptance. The coordinator
  independently reran all nine tests successfully in 14.517 seconds after final source review.
- Backend CI now runs only the disposable formatter fixtures, including the check that application
  source hashes stay unchanged. It does not enforce or apply formatting to existing project sources.

## Handoff and limits

The worker authored `pom.xml` profile configuration, the helper and Python verification under
`scripts/format/`, the Java-formatting guide, this plan, and narrow `AGENTS.md` guidance. No commit,
push, deployment, IDE change, or application-source formatting was performed. The coordinator owns
fresh review and publication; the user will run application formatting after the pending pushes.

The helper rejects unknown/Unicode-escaped trivia rather than guessing. It starts a JVM/compiler
per file, so a whole-project pass has additional overhead. Normal Maven builds have no formatter
gate, and a multi-file Spotless apply is not transactional. Full application tests were not rerun
for configuration/docs/new-tooling only; compiled disposable runtime verification covered the
formatter's behavior.
