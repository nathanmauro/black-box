package dev.nathan.sbaagentic.ask.internal.application.port;

import dev.nathan.sbaagentic.ask.AskCitation;
import dev.nathan.sbaagentic.ask.AskComponentStatus;
import dev.nathan.sbaagentic.ask.internal.application.AskDependencyUnavailable;
import java.util.List;

@FunctionalInterface
public interface AnswerSynthesizer {

    String synthesize(String question, List<AskCitation> citations);

    default AskComponentStatus status() {

        return AskComponentStatus.available("enabled");
    }

    static AnswerSynthesizer unavailable(String detail) {

        return new AnswerSynthesizer() {
            @Override
            public String synthesize(String question, List<AskCitation> citations) {
                throw new AskDependencyUnavailable(detail);
            }

            @Override
            public AskComponentStatus status() {

                return AskComponentStatus.unavailable(detail);
            }
        };
    }
}
