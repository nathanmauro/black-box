package dev.nathan.sbaagentic.platform.internal.adapter.out.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nathan.sbaagentic.memory.ContinuationModelReply;
import dev.nathan.sbaagentic.memory.ContinuationSynthesisProperties;
import dev.nathan.sbaagentic.summary.ModelCompletion;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@ExtendWith(OutputCaptureExtension.class)
class LocalContinuationSynthesizerTest {

    private final SummaryModelOperations model = mock(SummaryModelOperations.class);
    private final SummaryModelProperties localAi = new SummaryModelProperties();
    private final ContinuationSynthesisProperties synthesis = new ContinuationSynthesisProperties();
    private final LocalContinuationSynthesizer synthesizer =
            new LocalContinuationSynthesizer(model, localAi, synthesis);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:1234",
                "http://LOCALHOST:1234/v1",
                "http://127.0.0.1:1234",
                "http://127.5.6.7",
                "https://127.0.0.1",
                "http://[::1]:1234"
            })
    void loopbackUrlsAreAccepted(String url) {
        assertThat(LoopbackAddress.isLoopbackUrl(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://0.0.0.0:1234",
                "http://192.168.1.10:1234",
                "http://10.0.0.5",
                "http://lmstudio.local:1234",
                "http://localhost.example.com",
                "http://api.example.com",
                "http://127.0.0.256",
                "http://2130706433",
                "http://[::]:1234",
                "not a url",
                ""
            })
    void everyOtherHostIsRefused(String url) {
        assertThat(LoopbackAddress.isLoopbackUrl(url)).isFalse();
    }

    @Test
    void aNonLoopbackModelIsRefusedWithoutACallUnlessExplicitlyAllowed() {
        localAi.setBaseUrl("http://192.168.1.10:1234");

        assertThat(synthesizer.unavailableReason()).isEqualTo("remote_model_not_allowed");
        assertThat(synthesizer.complete("system", "user", 512).failureReason()).isEqualTo("remote_model_not_allowed");
        verify(model, never()).completeWithMetadata(anyString(), anyString(), anyInt());

        synthesis.setAllowNonLoopback(true);
        when(model.completeWithMetadata("system", "user", 512))
                .thenReturn(new ModelCompletion("Restated [E1].", "stop", "remote-model"));
        assertThat(synthesizer.unavailableReason()).isNull();
        assertThat(synthesizer.complete("system", "user", 512))
                .isEqualTo(ContinuationModelReply.reply("Restated [E1].", "stop", "remote-model"));
    }

    @Test
    void aDisabledLocalModelIsDisabled() {
        localAi.setEnabled(false);

        assertThat(synthesizer.unavailableReason()).isEqualTo("disabled");
        assertThat(synthesizer.complete("system", "user", 512).failureReason()).isEqualTo("disabled");
        verify(model, never()).completeWithMetadata(anyString(), anyString(), anyInt());
    }

    @Test
    void failuresMapToFixedReasonsAndOnlyTheExceptionClassIsLogged(CapturedOutput output) {
        when(model.completeWithMetadata(anyString(), anyString(), anyInt()))
                .thenThrow(new ResourceAccessException(
                        "I/O error: Read timed out PRIVATE-RESPONSE-TEXT",
                        new SocketTimeoutException("Read timed out")))
                .thenThrow(new RestClientException("500 Internal Server Error: PRIVATE-RESPONSE-TEXT"));

        assertThat(synthesizer
                        .complete("system PRIVATE-PROMPT", "user PRIVATE-PROMPT", 512)
                        .failureReason())
                .isEqualTo("timeout");
        assertThat(synthesizer
                        .complete("system PRIVATE-PROMPT", "user PRIVATE-PROMPT", 512)
                        .failureReason())
                .isEqualTo("unavailable");
        verify(model, times(2)).completeWithMetadata(anyString(), anyString(), anyInt());
        assertThat(output.getAll())
                .contains(
                        "Continuation synthesis model call failed: org.springframework.web.client.ResourceAccessException")
                .doesNotContain("PRIVATE-RESPONSE-TEXT")
                .doesNotContain("PRIVATE-PROMPT");
    }

    @Test
    void theInputWindowKeepsTheLocalClientFloor() {
        localAi.setMaxInputChars(100);
        assertThat(synthesizer.maxInputChars()).isEqualTo(500);
        localAi.setMaxInputChars(8_000);
        assertThat(synthesizer.maxInputChars()).isEqualTo(8_000);
    }
}
