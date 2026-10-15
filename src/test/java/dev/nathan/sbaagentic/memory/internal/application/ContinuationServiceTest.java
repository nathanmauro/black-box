package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationBasis;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationHandle;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationLink;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationPromptRenderer;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisPrompt;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ContinuationServiceTest {

    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final String ROOT = "/repos/alpha";
    static final String ACTION = "Reproduce the recovery path";

    private final MemoryRecallOperations recall = mock(MemoryRecallOperations.class);
    private final ProjectScopeOperations projects = mock(ProjectScopeOperations.class);
    private final EvidenceService evidence = mock(EvidenceService.class);
    private final ContinuationService service =
            new ContinuationService(recall, projects, evidence, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * The next action (tier 1) is the oldest capture; five newer handoffs carry the same text as an
     * open loop. The cap of five listed sources must still keep the strongest source, listed first.
     */
    @Test
    void thePrimarySourceIsListedFirstAndTheNewestOthersFillTheCap() {
        List<RecalledItem> handoffs = new ArrayList<>();
        handoffs.add(handoff(0, Duration.ofHours(6), ACTION, List.of()));
        IntStream.rangeClosed(1, 5)
                .forEach(index -> handoffs.add(handoff(index, Duration.ofHours(6 - index), null, List.of(ACTION))));
        stubRecall(handoffs);

        ContinuationReport.Entry entry =
                service.build(ROOT, null, null).candidates().getFirst();

        assertThat(entry.candidate().basis()).isEqualTo(ContinuationBasis.HANDOFF_NEXT_ACTION);
        assertThat(entry.candidate().primary().capture().eventId()).isEqualTo(id(0));
        assertThat(entry.evidence())
                .extracting(ContinuationLink::eventId, ContinuationLink::field)
                .containsExactly(
                        tuple(id(0), "nextAction"),
                        tuple(id(5), "openLoops"),
                        tuple(id(4), "openLoops"),
                        tuple(id(3), "openLoops"),
                        tuple(id(2), "openLoops"));
        assertThat(entry.promptInput().omittedSources()).isEqualTo(1);

        String sources = entry.prompt()
                .substring(entry.prompt().lastIndexOf("\n" + ContinuationPromptRenderer.SOURCES_HEADING + "\n"));
        List<String> listed =
                sources.lines().filter(line -> line.startsWith("- handoff ")).toList();
        assertThat(listed)
                .hasSize(5)
                .first()
                .asString()
                .startsWith("- handoff nextAction, recorded " + NOW.minus(Duration.ofHours(6)) + ", event " + id(0));
        assertThat(listed)
                .extracting(line -> line.substring(line.indexOf(", event ") + 8, line.indexOf(": /?")))
                .containsExactly(id(0), id(5), id(4), id(3), id(2));
        assertThat(sources)
                .contains("- 1 older matching capture is not listed.")
                .doesNotContain(id(1));

        List<ContinuationHandle> goalHandles = ContinuationSynthesisPrompt.build(
                        entry.promptInput(), UnaryOperator.identity())
                .handles();
        assertThat(goalHandles.getFirst().handle()).isEqualTo("E1");
        assertThat(goalHandles.getFirst().eventId()).isEqualTo(id(0));
        assertThat(goalHandles.subList(0, 5))
                .extracting(ContinuationHandle::eventId)
                .containsExactly(id(0), id(5), id(4), id(3), id(2));
    }

    private void stubRecall(List<RecalledItem> handoffs) {
        when(projects.projectScopesFor(ROOT)).thenReturn(List.of(new ProjectScope("key", ROOT, ROOT, true, null)));
        when(recall.recall(isNull(), eq(ROOT), eq(""), anyInt(), eq(List.of("handoff")), eq(50), eq(false)))
                .thenReturn(new RecallResult("", 8760, List.of("handoff"), handoffs.size(), handoffs, "lexical"));
        when(recall.recall(isNull(), eq(ROOT), eq(""), anyInt(), eq(List.of("decision")), eq(50), eq(false)))
                .thenReturn(new RecallResult("", 8760, List.of("decision"), 0, List.of(), "lexical"));
        when(evidence.linkedTo(any(), anyString(), anyInt())).thenReturn(List.of());
    }

    private static RecalledItem handoff(int index, Duration age, String nextAction, List<String> openLoops) {

        return new RecalledItem(
                id(index),
                "session-" + index,
                "handoff",
                "codex",
                "client-" + index,
                ROOT,
                NOW.minus(age),
                "State " + index,
                null,
                null,
                null,
                openLoops,
                nextAction,
                null,
                null,
                null,
                null);
    }

    private static String id(int index) {

        return "00000000-0000-4000-8000-%012d".formatted(index);
    }
}
