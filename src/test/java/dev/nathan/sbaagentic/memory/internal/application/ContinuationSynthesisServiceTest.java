package dev.nathan.sbaagentic.memory.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.memory.ContinuationModelReply;
import dev.nathan.sbaagentic.memory.ContinuationSynthesisProperties;
import dev.nathan.sbaagentic.memory.ContinuationSynthesizer;
import dev.nathan.sbaagentic.memory.MemoryRecallOperations;
import dev.nathan.sbaagentic.memory.RecallResult;
import dev.nathan.sbaagentic.memory.RecalledItem;
import dev.nathan.sbaagentic.memory.internal.domain.ContinuationSynthesisPrompt;
import dev.nathan.sbaagentic.project.ProjectScope;
import dev.nathan.sbaagentic.project.ProjectScopeOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ContinuationSynthesisServiceTest {

    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final String ROOT = "/repos/alpha";
    static final String HANDOFF = "3f2a0000-0000-4000-8000-000000000001";

    private final MemoryRecallOperations recall = mock(MemoryRecallOperations.class);
    private final ProjectScopeOperations projects = mock(ProjectScopeOperations.class);
    private final EvidenceService evidence = mock(EvidenceService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ContinuationSynthesizer> provider = mock(ObjectProvider.class);

    private final ContinuationSynthesizer model = mock(ContinuationSynthesizer.class);
    private ContinuationSynthesisService service;
    private String candidateId;

    @BeforeEach
    void setUp() {
        when(projects.projectScopesFor(ROOT)).thenReturn(List.of(new ProjectScope("key", ROOT, ROOT, true, null)));
        RecalledItem handoff = new RecalledItem(
                HANDOFF,
                "session-1",
                "handoff",
                "codex",
                "client-1",
                ROOT,
                NOW.minusSeconds(3_600),
                "Storage replaced; key SECRET-TOKEN rotated",
                null,
                null,
                null,
                List.of("Measure restore time"),
                "Reproduce the recovery path",
                "next-session",
                null);
        when(recall.recall(isNull(), eq(ROOT), eq(""), anyInt(), eq(List.of("handoff")), eq(50), eq(false)))
                .thenReturn(new RecallResult("", 8760, List.of("handoff"), 1, List.of(handoff), "lexical"));
        when(recall.recall(isNull(), eq(ROOT), eq(""), anyInt(), eq(List.of("decision")), eq(50), eq(false)))
                .thenReturn(new RecallResult("", 8760, List.of("decision"), 0, List.of(), "lexical"));
        when(evidence.linkedTo(any(), anyString(), anyInt())).thenReturn(List.of());
        var continuations = new ContinuationService(recall, projects, evidence, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ContinuationSynthesisService(
                continuations,
                provider,
                text -> text.replace("SECRET-TOKEN", "[REDACTED]"),
                new ContinuationSynthesisProperties());
        candidateId = continuations
                .build(ROOT, null, null)
                .candidates()
                .getFirst()
                .candidate()
                .id();
        when(model.maxInputChars()).thenReturn(8_000);
    }

    @Test
    void withoutASynthesizerItIsDisabledAndUnavailable() {
        var result = service.synthesize(ROOT, null, candidateId);

        assertThat(service.available()).isFalse();
        assertThat(result.synthesized()).isNull();
        assertThat(result.status()).isEqualTo("degraded");
        assertThat(result.reason()).isEqualTo("disabled");
        assertThat(result.entry().prompt()).startsWith("Black Box continuation prompt");
    }

    @Test
    void anUnreadySynthesizerIsNeverCalled() {
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.unavailableReason()).thenReturn("remote_model_not_allowed");

        var result = service.synthesize(ROOT, null, candidateId);

        assertThat(service.available()).isFalse();
        assertThat(result.reason()).isEqualTo("remote_model_not_allowed");
        verify(model, never()).complete(anyString(), anyString(), anyInt());
    }

    @Test
    void inputOverTheModelWindowIsRefusedNotElided() {
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.maxInputChars()).thenReturn(100);

        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("input_over_budget");
        verify(model, never()).complete(anyString(), anyString(), anyInt());
    }

    @Test
    void oneValidatedCallReturnsTheRestatementBesideTheDeterministicPrompt() {
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.complete(anyString(), anyString(), anyInt()))
                .thenReturn(ContinuationModelReply.reply("Reproduce the recovery path [E1].", "stop", "fixture-model"));

        var result = service.synthesize(ROOT, null, " " + candidateId + " ");

        assertThat(service.available()).isTrue();
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.reason()).isNull();
        assertThat(result.synthesized().text()).isEqualTo("Reproduce the recovery path [E1].");
        assertThat(result.synthesized().model()).isEqualTo("fixture-model");
        assertThat(result.synthesized().promptVersion()).isEqualTo("continuation-synthesis-v1");
        assertThat(result.synthesized().citations()).singleElement().satisfies(citation -> {
            assertThat(citation.handle()).isEqualTo("E1");
            assertThat(citation.eventId()).isEqualTo(HANDOFF);
        });
        assertThat(result.entry().prompt()).doesNotContain("[E1]");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(model).complete(eq(ContinuationSynthesisPrompt.SYSTEM), user.capture(), eq(512));
        assertThat(user.getValue())
                .contains("[REDACTED]")
                .doesNotContain("SECRET-TOKEN")
                .doesNotContain(HANDOFF);
    }

    @Test
    void failuresAndInvalidRepliesDegradeWithFixedReasons() {
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.complete(anyString(), anyString(), anyInt()))
                .thenReturn(ContinuationModelReply.failure("timeout"))
                .thenReturn(ContinuationModelReply.failure("socket reset by peer: details"))
                .thenReturn(ContinuationModelReply.reply("No citation at all.", "stop", "m"))
                .thenReturn(ContinuationModelReply.reply("Cut off [E1]", "length", "m"))
                .thenReturn(ContinuationModelReply.reply(" ", "stop", "m"))
                .thenReturn(null);

        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("timeout");
        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("unavailable");
        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("invalid_reference");
        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("truncated");
        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("empty");
        assertThat(service.synthesize(ROOT, null, candidateId).reason()).isEqualTo("unavailable");
    }

    @Test
    void anUnknownOrBlankCandidateIsAClientError() {
        assertThatThrownBy(() -> service.synthesize(ROOT, null, "bbc1-000000000000000000000000"))
                .isInstanceOf(ContinuationCandidateNotFoundException.class);
        assertThatThrownBy(() -> service.synthesize(ROOT, null, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.synthesize("/", null, candidateId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
