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
                .contains("data-tab=\"projects\"")
                .contains("data-tab=\"recall\"")
                .contains("data-tab=\"ask\"")
                .contains("id=\"projectRail\"")
                .contains("id=\"toggleRailButton\"")
                .contains("id=\"sessionSearchInput\"")
                .contains("id=\"projectList\"")
                .contains("id=\"projectSessions\"")
                .contains("id=\"projectTimeline\"")
                .contains("id=\"projectMeldTray\"")
                .contains("id=\"projectMeldForm\"")
                .contains("id=\"projectMeldOutput\"")
                .contains("id=\"projectMeldSelectedCount\"")
                .contains("id=\"summaryPreview\"")
                .contains("id=\"summaryDialog\"")
                .contains("id=\"sessionOutline\"")
                .contains("id=\"outlineBody\"")
                .contains("id=\"outlinePopout\"")
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
                .contains("data-panel=\"projects\"")
                .contains("data-panel=\"spine\"");

        assertThat(html).doesNotContain("data-tab=\"search\"");
        assertThat(html).doesNotContain("data-panel=\"search\"");
        assertThat(html.indexOf("data-tab=\"spine\"")).isLessThan(html.indexOf("data-tab=\"recall\""));
        assertThat(html.indexOf("data-tab=\"projects\"")).isLessThan(html.indexOf("data-tab=\"recall\""));
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
                .contains("/api/projects")
                .contains("/melds/preview")
                .contains("loadProjects")
                .contains("renderProjectTimeline")
                .contains("previewProjectMeld")
                .contains("renderProjectMeldTray")
                .contains("selectedProjectSessionIds")
                .contains("toggleRail")
                .contains("renderSessionOutline")
                .contains("renderSummaryCard")
                .contains("toolActionSummary")
                .contains("toggleNode")
                .contains("openOutlinePopout")
                .contains("sessionSearchInput")
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

    @Test
    void staticCssDefinesProjectsWorkspace() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/styles.css"));

        assertThat(css)
                .contains(".project-workspace")
                .contains(".project-list")
                .contains(".project-timeline")
                .contains(".project-meld-tray")
                .contains(".project-meld-output")
                .contains(".project-block")
                .contains(".rail.collapsed")
                .contains(".summary-dialog")
                .contains(".session-workspace")
                .contains(".session-outline")
                .contains(".session-minimap")
                .contains(".node.is-collapsed")
                .contains("@media (max-width: 720px)");
    }
}
