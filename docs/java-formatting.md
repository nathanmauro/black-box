# Java formatting

Java formatting is opt-in. The `java-format` Maven profile pins Spotless 3.10.2 and
Palantir Java Format 2.98.0 with `PALANTIR` style: four-space indentation and a 120-column layout.
It uses no Eclipse formatter. The formatter has been exercised on JDK 21.0.12. A full JDK 21 is
required by the additional syntax-aware return-spacing step.

No formatting goal is bound to the normal Maven lifecycle. `mvn test`, `mvn verify`, and ordinary
builds do not acquire a formatting gate. The project-wide source pass includes the optional Cortex module; see the
[rollout record](superpowers/plans/2026-09-22-java-formatting-integration.md).

## Commands

Run from the repository root. Preview a selected file without changing it:

```bash
mvn -Pjava-format spotless:check \
  '-DspotlessFiles=.*/src/main/java/dev/nathan/sbaagentic/query/TimeSpec\.java'
```

Apply to that selected file when ready:

```bash
mvn -Pjava-format spotless:apply \
  '-DspotlessFiles=.*/src/main/java/dev/nathan/sbaagentic/query/TimeSpec\.java'
```

`spotlessFiles` is a regular expression matched against the entire absolute path, not a relative
glob. The `.*` prefix and escaped `.java` suffix in these commands are intentional. Quote the
argument so the shell does not expand it. Spotless documents this matching rule in its
[Maven file-selection instructions](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#can-i-apply-spotless-to-specific-files).

For an explicitly requested project-wide pass:

```bash
mvn -Pjava-format spotless:check
mvn -Pjava-format spotless:apply
mvn -Pjava-format spotless:check
git diff --check
mvn test
```

The default include is `src/**/*.java`. Review and preserve unrelated dirty work before applying;
Spotless changes matching files in place and a multi-file run is not an atomic transaction. Review
the resulting diff, then run the relevant tests. No IDE installation or configuration is required.

## Blank lines before returns

Palantir supplies the general layout; it does not supply this repository's blank-before-return
policy. Spotless runs `scripts/format/BlankBeforeReturn.java` afterward through its documented
[`nativeCmd` step](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#generic-steps).
The executable is Maven's `${java.home}/bin/java`, and the helper runs in Java source-file mode.
There is no separate wrapper command or extra parser dependency.

The helper uses the JDK compiler's parsed `ReturnTree` positions. It edits only whitespace at
verified statement boundaries, including the first return inside a block. A leading explanatory
comment group stays adjacent to the return, with the blank line before that group. Trailing comments
stay with the previous statement. For example:

```java
int answer() {
    int result = calculate(); // Describe this calculation.

    // Explain the returned result.
    return result;
}
```

Simple unbraced returns that Palantir keeps on one line are separated using the parsed control-body
boundary:

```java
if (ready)

    return result;
```

The helper does not add braces or change control flow. It recognizes blocks, colon switch cases,
if/else branches, loops, and labels. Strings, character literals, and text blocks are not searched
for the word `return`. Only trivia between parsed code boundaries is scanned for comments. A
malformed Java file, unknown return context, or Unicode-escaped trivia is rejected instead of
guessing; the helper emits no partially transformed source. Palantir itself can change comment
layout, so the extra step's preservation guarantee applies to its Palantir-formatted input.

The complete pipeline is idempotent, even when Palantir would independently collapse an unbraced
return again. Up-to-date skipping is disabled so edits to the source helper cannot leave a stale
cached formatting result. Source-launching the helper starts a JVM/compiler per file, making a
project-wide run slower than Palantir alone. This small, explicit integration is intended to be
reviewed before considering a cached or packaged helper.

## Disposable verification

```bash
python3 scripts/format/test_java_format.py
```

The test creates fixtures under `target`, compiles the helper, and runs the real Maven profile with
an isolated `java.format.includes` override. It checks return and comment placement, literals and
text blocks, nested returns, control-flow contexts, CRLF, malformed/ambiguous input, repeat-run byte
identity, and compiled runtime output. It also hashes every existing `src/**/*.java` file before and
after to prove that verification did not format application or test sources.

Backend CI runs these disposable fixture contracts. It does not run a formatting check or apply
against the repository's application/test Java sources.

Use this disposable regression command for CI coverage of the formatter setup. Source formatting
remains opt-in; the completed rollout does not introduce a new CI formatting gate.

Palantir's layout and Java 21 support are described in its
[official documentation](https://github.com/palantir/palantir-java-format); Spotless's pinned engine
configuration follows its [Palantir integration](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#palantir-java-format).
