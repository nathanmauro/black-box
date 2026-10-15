package dev.nathan.sbaagentic.platform.internal.adapter.out.model;

import dev.nathan.sbaagentic.memory.ContinuationModelReply;
import dev.nathan.sbaagentic.memory.ContinuationSynthesisProperties;
import dev.nathan.sbaagentic.memory.ContinuationSynthesizer;
import dev.nathan.sbaagentic.summary.ModelCompletion;
import dev.nathan.sbaagentic.summary.SummaryModelOperations;
import dev.nathan.sbaagentic.summary.SummaryModelProperties;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Opt-in continuation restatement through the summary module's local OpenAI-compatible client. It
 * exists only when synthesis is enabled, refuses a non-loopback model unless explicitly allowed,
 * makes one call per request, and never logs prompt text or exception messages.
 */
@Component
@ConditionalOnProperty(name = "sba.continuation.synthesis.enabled", havingValue = "true")
public class LocalContinuationSynthesizer implements ContinuationSynthesizer {

    static final String DISABLED = "disabled";
    static final String REMOTE_MODEL_NOT_ALLOWED = "remote_model_not_allowed";
    static final String UNAVAILABLE = "unavailable";
    static final String TIMEOUT = "timeout";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalContinuationSynthesizer.class);
    /** Matches the local client's input floor, so its own clamp never shortens accepted input. */
    private static final int MIN_INPUT_CHARS = 500;

    private static final int MAX_CAUSE_DEPTH = 16;

    private final SummaryModelOperations model;
    private final SummaryModelProperties localAi;
    private final ContinuationSynthesisProperties synthesis;

    public LocalContinuationSynthesizer(
            SummaryModelOperations model, SummaryModelProperties localAi, ContinuationSynthesisProperties synthesis) {
        this.model = model;
        this.localAi = localAi;
        this.synthesis = synthesis;
    }

    @Override
    public String unavailableReason() {
        if (!localAi.isEnabled()) {

            return DISABLED;
        }
        if (!synthesis.isAllowNonLoopback() && !LoopbackAddress.isLoopbackUrl(localAi.getBaseUrl())) {

            return REMOTE_MODEL_NOT_ALLOWED;
        }

        return null;
    }

    @Override
    public int maxInputChars() {

        return Math.max(MIN_INPUT_CHARS, localAi.getMaxInputChars());
    }

    @Override
    public ContinuationModelReply complete(String system, String user, int maxTokens) {
        String unavailable = unavailableReason();
        if (unavailable != null) {

            return ContinuationModelReply.failure(unavailable);
        }
        try {
            ModelCompletion completion = model.completeWithMetadata(system, user, maxTokens);

            return ContinuationModelReply.reply(completion.content(), completion.finishReason(), completion.model());
        } catch (RuntimeException ex) {
            boolean timeout = timedOut(ex);
            // Client exception messages can echo response text, so only the class is logged.
            LOGGER.warn(
                    "Continuation synthesis model call failed: {}",
                    ex.getClass().getName());

            return ContinuationModelReply.failure(timeout ? TIMEOUT : UNAVAILABLE);
        }
    }

    static boolean timedOut(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {

                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }

        return false;
    }
}
