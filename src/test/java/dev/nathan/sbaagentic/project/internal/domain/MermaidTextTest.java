package dev.nathan.sbaagentic.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MermaidTextTest {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9 _.,:/\\-]*(?:#\\d+;[A-Za-z0-9 _.,:/\\-]*)*");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"]; click s1 call alert()",
                "%%{init: {'theme': 'dark'}}%%",
                "a --> b",
                "pipe | label",
                "x(\"(nested)\")",
                "{{hex}} [[sub]] ((circle))",
                "<script>alert(1)</script>",
                "`markdown` *bold* _it_",
                "semi; colon & amp #quot; #35;",
                "line\nbreak\r\ncarriage\ttab",
                "bidi ‮evil‬ zero​width nul\u0000",
                "emoji 🚀 é ß 漢"
            })
    void anyTextBecomesAllowlistedCharactersAndEntities(String hostile) {
        String label = MermaidText.label(hostile);

        assertThat(SAFE.matcher(label).matches()).as(label).isTrue();
        assertThat(label).doesNotContain("\"", "[", "]", "{", "}", "(", ")", "|", "<", ">", ";;", "%", "`", "\n");
    }

    @Test
    void entitiesDecodeBackToTheVisibleText() {
        String original = "say \"hi\" <b>now</b> 🚀";
        String label = MermaidText.label(original);

        assertThat(decode(label)).isEqualTo(original);
        assertThat(label).contains("#34;hi#34;").contains("#128640;");
    }

    @Test
    void controlAndFormatCharactersAreDroppedAndWhitespaceBecomesSpaces() {
        assertThat(MermaidText.label("a\u0000b​c‮d")).isEqualTo("abcd");
        assertThat(MermaidText.label("a\nb\tc d")).isEqualTo("a b c d");
    }

    @Test
    void plainTextPassesThrough() {
        assertThat(MermaidText.label("Pick SQLite, v2.1: /repo/path_name-x"))
                .isEqualTo("Pick SQLite, v2.1: /repo/path_name-x");
        assertThat(MermaidText.label(null)).isEmpty();
    }

    private static String decode(String label) {
        Matcher matcher = Pattern.compile("#(\\d+);").matcher(label);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    out, Matcher.quoteReplacement(Character.toString(Integer.parseInt(matcher.group(1)))));
        }
        matcher.appendTail(out);

        return out.toString();
    }
}
