package dev.nathan.sbaagentic.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nathan.sbaagentic.config.SbaProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AskServiceTest {

    @Test
    void returnsDeterministicNoHitAnswerInsteadOfGuessing() {
        FakeMemoryRetriever memory = new FakeMemoryRetriever();
        AskService service = new AskService(memory, QueryEmbedder.unavailable("disabled"), AnswerSynthesizer.unavailable("disabled"),
                properties());

        AskResponse response = service.ask(new AskRequest("Where is the deployment decision?", 6));

        assertThat(response.answer()).contains("not found in memory");
        assertThat(response.citations()).isEmpty();
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void fallsBackToBm25OnlyWhenEmbeddingIsUnavailable() {
        FakeMemoryRetriever memory = new FakeMemoryRetriever();
        memory.lexicalHits.add(hit("lexical", "Lexical Memory"));

        AskService service = new AskService(memory, QueryEmbedder.unavailable("ollama offline"),
                AnswerSynthesizer.unavailable("disabled"), properties());

        AskRetrieveResponse response = service.retrieve("agent memory", 10);

        assertThat(response.retrievalMode()).isEqualTo("bm25");
        assertThat(response.degraded()).isTrue();
        assertThat(response.results()).extracting(AskCitation::number).containsExactly(1);
        assertThat(response.results()).extracting(AskCitation::title).containsExactly("Lexical Memory");
        assertThat(memory.vectorCalled).isFalse();
    }

    @Test
    void askReturnsCitationsWhenChatSynthesisIsUnavailable() {
        FakeMemoryRetriever memory = new FakeMemoryRetriever();
        memory.lexicalHits.add(hit("first", "First Memory"));
        QueryEmbedder embedder = query -> new float[] { 0.1f, 0.2f, 0.3f };
        AnswerSynthesizer offlineChat = AnswerSynthesizer.unavailable("lm studio offline");

        AskService service = new AskService(memory, embedder, offlineChat, properties());

        AskResponse response = service.ask(new AskRequest("What happened?", 6));

        assertThat(response.answer()).contains("Local answer synthesis is unavailable");
        assertThat(response.degraded()).isTrue();
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().number()).isEqualTo(1);
        assertThat(response.citations().getFirst().sourcePath()).isEqualTo("/memory/first.md");
    }

    @Test
    void preservesCitationNumbersAndMetadataFromRankedHits() {
        FakeMemoryRetriever memory = new FakeMemoryRetriever();
        memory.lexicalHits.add(hit("one", "One"));
        memory.lexicalHits.add(hit("two", "Two"));
        AnswerSynthesizer synthesizer = (question, citations) -> "Answer [1] [2]";

        AskService service = new AskService(memory, QueryEmbedder.unavailable("disabled"), synthesizer, properties());

        AskResponse response = service.ask(new AskRequest("What should I remember?", 6));

        assertThat(response.answer()).isEqualTo("Answer [1] [2]");
        assertThat(response.citations()).extracting(AskCitation::number).containsExactly(1, 2);
        assertThat(response.citations()).extracting(AskCitation::title).containsExactly("One", "Two");
        assertThat(response.citations()).extracting(AskCitation::sessionId).containsExactly("session-one", "session-two");
        assertThat(response.citations()).extracting(AskCitation::snippet).containsExactly("Snippet one", "Snippet two");
    }

    private static SbaProperties properties() {
        SbaProperties properties = new SbaProperties();
        properties.getAsk().setDefaultAskCitations(6);
        properties.getAsk().setDefaultRetrieveResults(10);
        return properties;
    }

    private static MemoryHit hit(String id, String title) {
        return new MemoryHit(
                id,
                1.0,
                title,
                "codex",
                "/memory/" + id + ".md",
                "session-" + id,
                "client-" + id,
                "2026-06-01T12:00:00Z",
                "Text " + id,
                "Snippet " + id);
    }

    private static final class FakeMemoryRetriever implements MemoryRetriever {
        private final List<MemoryHit> lexicalHits = new ArrayList<>();
        private final List<MemoryHit> vectorHits = new ArrayList<>();
        private final AtomicBoolean vectorCalledRef = new AtomicBoolean();
        private boolean vectorCalled;

        @Override
        public AskComponentStatus status() {
            return AskComponentStatus.available("agent-memory");
        }

        @Override
        public List<MemoryHit> bm25(String query, int limit) {
            return lexicalHits.stream().limit(limit).toList();
        }

        @Override
        public List<MemoryHit> knn(float[] embedding, int limit) {
            vectorCalledRef.set(true);
            vectorCalled = true;
            return vectorHits.stream().limit(limit).toList();
        }
    }
}
