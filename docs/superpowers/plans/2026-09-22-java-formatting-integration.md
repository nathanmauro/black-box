# Java formatting and RSI reconciliation

Apply the existing pinned Spotless/Palantir profile and AST return-spacing helper to current
main after the Cortex merge (4354d3a). This preserves the newer retrieval, idempotent capture,
and Cortex behavior while reconciling the earlier local formatting work. No formatter settings,
normal build gates, or runtime behavior change.

The RSI audit found that evaluation, lifecycle, and retirement work was already merged or
superseded upstream. Preserve newer lifecycle durability fixes and regression tests. The remaining
unique housekeeping change is `scripts/lifecycle/.gitignore` ignoring Python bytecode caches.

Verification compares pre-format and post-format compiled instructions, signatures, and constants
using `javap -c -p -s -constants`, checks the entire formatter profile, runs the full backend suite,
and verifies the lifecycle ignore rule.

Results:

- The pinned formatter changed 437 of 488 Java source files.
- An independent token-level review found identical code and literals after excluding comments,
  whitespace, and import ordering/removal. Import removals occurred in 17 files.
- All 476 application classes and 200 test classes retain byte-identical
  `javap -c -p -s -constants` output, including executable instructions and literal values.
- Full backend suite: 673 tests, zero failures/errors, 20 skipped. The packaged Boot JAR builds.
- All 36 lifecycle tests pass; `git check-ignore` confirms the bytecode-cache rule.
- `git diff --check` passes. The full read-only formatter check passes for all 488 Java sources.
