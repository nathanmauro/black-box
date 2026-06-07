package dev.nathan.sbaagentic.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticUiContractTest {

    @Test
    void mainStageUsesSessionsRecallAndAskTabs() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(html)
                .contains("data-tab=\"spine\"")
                .contains("data-tab=\"recall\"")
                .contains("data-tab=\"ask\"")
                .contains("id=\"askForm\"")
                .contains("id=\"retrieveButton\"")
                .contains("id=\"askResults\"")
                .contains("id=\"searchForm\"")
                .contains("id=\"queryInput\"")
                .contains("id=\"searchResults\"")
                .contains("href=\"http://localhost:5601\"")
                .contains("<script src=\"/graph.js\"></script>")
                .contains("<script src=\"/querybar.js\"></script>")
                .contains("<script src=\"/app.js\"></script>")
                .contains("data-panel=\"recall\"")
                .contains("data-panel=\"ask\"")
                .contains("data-panel=\"spine\"");

        assertThat(html).doesNotContain("data-tab=\"search\"");
        assertThat(html).doesNotContain("data-panel=\"search\"");
        assertThat(html.indexOf("data-tab=\"spine\"")).isLessThan(html.indexOf("data-tab=\"recall\""));
        assertThat(html.indexOf("data-tab=\"recall\"")).isLessThan(html.indexOf("data-tab=\"ask\""));
        assertThat(html)
                .contains("class=\"stage-tab active\"")
                .contains("aria-controls=\"panel-spine\"")
                .contains(">Sessions</button>");
    }

    @Test
    void staticScriptWiresSessionsSearchAndGroupedRail() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/static/app.js"));

        assertThat(script)
                .contains("askForm")
                .contains("/api/ask")
                .contains("/api/ask/retrieve")
                .contains("activateTab")
                .contains("renderAskResponse")
                .contains("BlackBoxConstellation.render")
                .contains("BlackBoxQueryBar.attach")
                .contains("queryInput")
                .contains("activeTab: \"spine\"")
                .contains("groupSessionsByProject")
                .contains("formatProjectPath")
                .contains("localStorage")
                .contains("expandAllSessionGroups")
                .contains("collapseAllSessionGroups")
                .contains("locateOnSpine(row.dataset.eventId)")
                .contains("renderAnswerSourceLinks")
                .contains("citation-memory-link");

        assertThat(script).doesNotContain("activateTab(\"search\")");
    }

    @Test
    void queryInputTextRemainsReadableOverSyntaxOverlay() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/styles.css"));

        assertThat(css)
                .contains(".qb-input")
                .contains("color: var(--paper)")
                .contains(".qb-overlay")
                .contains("opacity: 0.72");
    }
}
