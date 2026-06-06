package dev.nathan.sbaagentic.web;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticUiContractTest {

    @Test
    void mainStageUsesSearchRecallAskAndSessionTabs() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(html)
                .contains("data-tab=\"search\"")
                .contains("data-tab=\"recall\"")
                .contains("data-tab=\"ask\"")
                .contains("data-tab=\"spine\"")
                .contains("id=\"askForm\"")
                .contains("id=\"retrieveButton\"")
                .contains("id=\"askResults\"")
                .contains("data-panel=\"search\"")
                .contains("data-panel=\"recall\"")
                .contains("data-panel=\"ask\"")
                .contains("data-panel=\"spine\"");

        assertThat(html.indexOf("data-tab=\"search\"")).isLessThan(html.indexOf("data-tab=\"recall\""));
        assertThat(html.indexOf("data-tab=\"recall\"")).isLessThan(html.indexOf("data-tab=\"ask\""));
        assertThat(html.indexOf("data-tab=\"ask\"")).isLessThan(html.indexOf("data-tab=\"spine\""));
        assertThat(html)
                .contains("class=\"stage-tab active\"")
                .contains("aria-controls=\"panel-search\"")
                .contains(">Session</button>");
    }

    @Test
    void staticScriptWiresAskApiAndTabbedStage() throws Exception {
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
                .contains("activateTab(\"spine\")")
                .contains("renderAnswerSourceLinks")
                .contains("citation-memory-link");
    }
}
