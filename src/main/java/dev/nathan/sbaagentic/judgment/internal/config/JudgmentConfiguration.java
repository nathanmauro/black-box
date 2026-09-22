package dev.nathan.sbaagentic.judgment.internal.config;

import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nathan.sbaagentic.judgment.JudgmentProperties;
import dev.nathan.sbaagentic.judgment.internal.adapter.out.http.HttpJevTransport;
import dev.nathan.sbaagentic.judgment.internal.adapter.out.http.JevJudge;
import dev.nathan.sbaagentic.judgment.internal.adapter.out.http.JevTransport;
import dev.nathan.sbaagentic.judgment.internal.adapter.out.http.JudgeQuestionSet;
import dev.nathan.sbaagentic.judgment.internal.application.BeatFolder;
import dev.nathan.sbaagentic.judgment.internal.application.JudgmentStateBuilder;
import dev.nathan.sbaagentic.judgment.internal.application.NoopJudge;
import dev.nathan.sbaagentic.judgment.internal.application.port.Judge;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.workflow.SessionLineageOperations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

@Configuration
@ConditionalOnProperty(name = "sba.judge.enabled", havingValue = "true")
public class JudgmentConfiguration {

    @Bean
    BeatFolder beatFolder(ObjectMapper objectMapper, JudgmentProperties properties) {
        return new BeatFolder(
                objectMapper,
                properties.getBeat().getGapMs(),
                properties.getBeat().getMaxEvents(),
                properties.getBeat().getMaxChars());
    }

    @Bean
    JudgmentStateBuilder judgmentStateBuilder(
            RecordingCatalog recording,
            SessionLineageOperations lineage,
            JudgmentProperties properties,
            Clock clock) {
        return new JudgmentStateBuilder(recording, lineage, properties, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "sba.judge.provider", havingValue = "none")
    Judge noopJudge() {
        return new NoopJudge();
    }

    @Bean
    @ConditionalOnProperty(name = "sba.judge.provider", havingValue = "jev", matchIfMissing = true)
    JudgeQuestionSet judgeQuestionSet(
            ObjectMapper objectMapper,
            @Value("classpath:judge/questions.json") Resource resource)
            throws java.io.IOException {
        return JudgeQuestionSet.load(objectMapper, resource);
    }

    @Bean
    @ConditionalOnProperty(name = "sba.judge.provider", havingValue = "jev", matchIfMissing = true)
    JevTransport jevTransport(JudgmentProperties properties) {
        return new HttpJevTransport(Duration.ofMillis(properties.getTimeoutMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "sba.judge.provider", havingValue = "jev", matchIfMissing = true)
    Judge jevJudge(
            JudgmentProperties properties,
            ObjectMapper objectMapper,
            JevTransport transport,
            JudgeQuestionSet questions,
            Clock clock,
            Environment environment) {
        String apiKey = firstText(
                environment.getProperty("SBA_JUDGE_API_KEY"),
                environment.getProperty("TYPESAFE_API_KEY"),
                System.getenv("SBA_JUDGE_API_KEY"),
                System.getenv("TYPESAFE_API_KEY"));
        return new JevJudge(
                apiKey,
                Duration.ofMillis(properties.getTimeoutMs()),
                objectMapper,
                transport,
                questions,
                clock);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
