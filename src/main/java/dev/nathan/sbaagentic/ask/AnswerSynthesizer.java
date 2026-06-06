package dev.nathan.sbaagentic.ask;

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
