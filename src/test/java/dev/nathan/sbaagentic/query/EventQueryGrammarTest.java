package dev.nathan.sbaagentic.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives the shared golden fixture {@code query/grammar-cases.json} through the Java parser. The
 * frontend's TypeScript mirror runs the same file through vitest, so a grammar change that lands
 * on only one side turns into a test failure instead of silent drift. Every grammar change appends
 * fixture cases in the same commit.
 */
class EventQueryGrammarTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record GrammarCase(String name, String input, Map<String, Object> expected) {
        @Override
        public String toString() {

            return name;
        }
    }

    static List<GrammarCase> cases() throws Exception {
        try (InputStream in = EventQueryGrammarTest.class.getResourceAsStream("/query/grammar-cases.json")) {
            assertThat(in)
                    .as("fixture query/grammar-cases.json on the classpath")
                    .isNotNull();

            return MAPPER.readValue(in, new TypeReference<List<GrammarCase>>() {});
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void fixtureCaseParses(GrammarCase grammarCase) {
        EventQuery parsed = EventQuery.parse(grammarCase.input());
        Map<String, Object> expected = grammarCase.expected();

        Map<String, List<String>> facets = stringLists(expected.get("facets"));
        Map<String, List<String>> excluded = stringLists(expected.get("excluded"));
        for (EventQuery.Field field : EventQuery.Field.values()) {
            String key = field.name().toLowerCase(Locale.ROOT);
            // project_group values live under the top-level projectGroups fixture key; its
            // exclusions must always be empty (the parser routes negations to free text).
            List<String> expectedValues = field == EventQuery.Field.PROJECT_GROUP
                    ? stringList(expected.get("projectGroups"))
                    : facets.getOrDefault(key, List.of());
            assertThat(parsed.values(field)).as("values(%s)", key).isEqualTo(expectedValues);
            assertThat(parsed.excluded(field)).as("excluded(%s)", key).isEqualTo(excluded.getOrDefault(key, List.of()));
        }

        assertThat(parsed.sessionRef().orElse(null)).as("session").isEqualTo(expected.get("session"));
        assertTimeSpec("since", parsed.sinceSpec().orElse(null), expected.get("since"));
        assertTimeSpec("until", parsed.untilSpec().orElse(null), expected.get("until"));
        assertThat(parsed.includeAll()).as("isAll").isEqualTo(Boolean.TRUE.equals(expected.get("isAll")));
        assertThat(parsed.freeTerms()).as("freeTerms").isEqualTo(stringList(expected.get("freeTerms")));
        assertThat(parsed.projectGroups()).as("projectGroups").isEqualTo(stringList(expected.get("projectGroups")));
    }

    @Test
    void nullQueryParsesToNothing() {
        EventQuery parsed = EventQuery.parse(null);
        assertThat(parsed.hasAnyFacet()).isFalse();
        assertThat(parsed.freeTerms()).isEmpty();
        assertThat(parsed.sessionRef()).isEmpty();
        assertThat(parsed.sinceSpec()).isEmpty();
        assertThat(parsed.untilSpec()).isEmpty();
        assertThat(parsed.includeAll()).isFalse();
    }

    @Test
    void hasAnyFacetCoversOperatorsButNotFreeText() {
        assertThat(EventQuery.parse("recall bug").hasAnyFacet()).isFalse();
        assertThat(EventQuery.parse("source:codex").hasAnyFacet()).isTrue();
        assertThat(EventQuery.parse("-tool:Read").hasAnyFacet()).isTrue();
        assertThat(EventQuery.parse("session:x").hasAnyFacet()).isTrue();
        assertThat(EventQuery.parse("since:today").hasAnyFacet()).isTrue();
        assertThat(EventQuery.parse("is:all").hasAnyFacet()).isTrue();
    }

    private static void assertTimeSpec(String side, TimeSpec actual, Object expectedRaw) {
        if (expectedRaw == null) {
            assertThat(actual).as(side).isNull();

            return;
        }
        assertThat(actual).as(side).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) expectedRaw;
        assertThat(actual.kind().name().toLowerCase(Locale.ROOT))
                .as("%s.kind", side)
                .isEqualTo(expected.get("kind"));
        assertThat(actual.value()).as("%s.value", side).isEqualTo(expected.get("value"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> stringLists(Object raw) {

        return raw == null ? Map.of() : (Map<String, List<String>>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {

        return raw == null ? List.of() : (List<String>) raw;
    }
}
