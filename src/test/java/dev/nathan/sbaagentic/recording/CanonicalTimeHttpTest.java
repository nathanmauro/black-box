package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalTimeHttpTest {
    @TempDir
    static Path directory;

    private final TestRestTemplate http = new TestRestTemplate();
    private ServletWebServerApplicationContext app;
    private String base;

    @BeforeAll
    void start() {
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(SbaAgenticApplication.class)
                .run(
                        "--spring.config.location=classpath:/application.yml",
                        "--spring.profiles.active=default",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("time.db"),
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--server.shutdown=immediate",
                        "--sba.auth.enabled=false",
                        "--sba.editor.enabled=false",
                        "--sba.local-ai.enabled=false",
                        "--sba.summary.backend=local",
                        "--sba.elasticsearch.enabled=false",
                        "--sba.memory.embedding.enabled=false",
                        "--sba.ask.embedding-enabled=false",
                        "--sba.judge.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
    }

    @Test
    void recallWindowsAndTypedEventCursorsPreserveNanoseconds() {
        new CanonicalTimeHttpContract(http, base)
                .exactRecallWindows(
                        app.getBean(
                                dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite.MemorySqlQueryAdapter.class),
                        app.getBean(RecordingCatalog.class));
    }

    @Test
    void mixedTranscriptPagesPreserveAllRows() throws Exception {
        new CanonicalTimeHttpContract(http, base)
                .mixedTranscriptPages(app.getBean(TranscriptProperties.class), directory);
    }

    @Test
    void preciseWindowsAgreeAcrossNavigationAndSearch() {
        new CanonicalTimeHttpContract(http, base)
                .exactWindowsAndOrdering(app.getBean(Clock.class).getZone());
    }

    @Test
    void tiesAndBackdatedArrivalsRemainNavigable() {
        new CanonicalTimeHttpContract(http, base).tiedPagesAndBackdatedArrival();
    }
}
