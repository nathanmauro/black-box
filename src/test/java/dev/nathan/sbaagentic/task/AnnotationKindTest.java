package dev.nathan.sbaagentic.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationKindTest {

    @Test
    void acceptsSdlcAnnotationKinds() {
        assertThat(AnnotationKind.fromValue("plan")).isEqualTo(AnnotationKind.PLAN);
        assertThat(AnnotationKind.fromValue("review")).isEqualTo(AnnotationKind.REVIEW);
        assertThat(AnnotationKind.fromValue("approval")).isEqualTo(AnnotationKind.APPROVAL);

        assertThat(AnnotationKind.PLAN.value()).isEqualTo("plan");
        assertThat(AnnotationKind.REVIEW.value()).isEqualTo("review");
        assertThat(AnnotationKind.APPROVAL.value()).isEqualTo("approval");
    }
}
