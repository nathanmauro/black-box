package dev.nathan.sbaagentic.judgment.internal.application;

import dev.nathan.sbaagentic.memory.internal.application.EmbeddingIndexer;
import dev.nathan.sbaagentic.recording.EventRecorded;

import org.junit.jupiter.api.Test;

import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

class JudgmentListenerTest {

    @Test
    void listenerRunsAfterEmbeddingIndexer() throws Exception {
        int judgmentOrder = JudgmentListener.class
                .getMethod("judgeRecordedEvent", EventRecorded.class)
                .getAnnotation(Order.class)
                .value();
        int embeddingOrder = EmbeddingIndexer.class
                .getMethod("indexRecordedEvent", EventRecorded.class)
                .getAnnotation(Order.class)
                .value();

        assertThat(embeddingOrder).isEqualTo(30);
        assertThat(judgmentOrder).isEqualTo(35);
    }
}
