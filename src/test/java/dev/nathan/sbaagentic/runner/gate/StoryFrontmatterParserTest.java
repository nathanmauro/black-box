package dev.nathan.sbaagentic.runner.gate;

import java.util.Optional;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser.ParsedStory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryFrontmatterParserTest {

    private final StoryFrontmatterParser parser = new StoryFrontmatterParser();

    @Test
    void validFrontmatterParsesAllFields() {
        Optional<ParsedStory> parsed = parser.parse("""
                ---
                story: v1
                repo: /tmp/example
                mode: full_auto
                verify: mvn test
                push: true
                priority: 10
                ---
                # Example

                ## Acceptance criteria
                - Tests pass
                """);

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().frontmatter()).isEqualTo(new StoryFrontmatter(
                "v1", "/tmp/example", "full_auto", "mvn test", true, 10));
    }

    @Test
    void missingFrontmatterReturnsEmpty() {
        assertThat(parser.parse("# Example\n\nNo frontmatter here.")).isEmpty();
    }

    @Test
    void malformedYamlReturnsEmptyRatherThanThrowing() {
        assertThat(parser.parse("""
                ---
                repo: [unterminated
                ---
                # Example
                """)).isEmpty();
    }

    @Test
    void absentFieldsRemainNullWithoutFailingParse() {
        ParsedStory parsed = parser.parse("""
                ---
                story: v1
                ---
                # Example
                """).orElseThrow();

        assertThat(parsed.frontmatter()).isEqualTo(new StoryFrontmatter(
                "v1", null, null, null, null, null));
    }

    @Test
    void bodyMarkdownExcludesFrontmatterAndLeadingBlankLines() {
        ParsedStory parsed = parser.parse("  ---  \n"
                + "story: v1\n"
                + "---\n"
                + "\n"
                + "\n"
                + "# Example\n"
                + "\n"
                + "Story body.\n").orElseThrow();

        assertThat(parsed.bodyMarkdown()).startsWith("# Example");
        assertThat(parsed.bodyMarkdown()).contains("Story body.");
        assertThat(parsed.bodyMarkdown()).doesNotContain("story: v1");
    }

    @Test
    void stringBooleanAndPriorityValuesAreCoercedWhenRecognized() {
        StoryFrontmatter frontmatter = parser.parse("""
                ---
                push: "TRUE"
                priority: "42"
                ---
                # Example
                """).orElseThrow().frontmatter();

        assertThat(frontmatter.push()).isTrue();
        assertThat(frontmatter.priority()).isEqualTo(42);
    }
}
