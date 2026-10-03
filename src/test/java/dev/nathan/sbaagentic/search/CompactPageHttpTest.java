package dev.nathan.sbaagentic.search;

import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** Private SQLite run of the shared canonical compact paging contract. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactPageHttpTest {
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
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("compact-page.db"),
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
    void legacyBoundedSearchCannotTraverseButCanonicalPagesDo() throws Exception {
        new CompactPageHttpContract(http, base).legacyBoundedSearchCannotTraverseButCanonicalPagesDo();
    }

    @Test
    void cutoffIsInclusiveToTheNanosecond() throws Exception {
        new CompactPageHttpContract(http, base).cutoffIsInclusiveToTheNanosecond();
    }

    @Test
    void literalTermsMatchExactlyWithoutGrammar() throws Exception {
        new CompactPageHttpContract(http, base).literalTermsMatchExactlyWithoutGrammar();
    }

    @Test
    void projectAndSessionBoundariesAreExact() throws Exception {
        new CompactPageHttpContract(http, base).projectAndSessionBoundariesAreExact();
    }

    @Test
    void cursorsAndAmbiguousRequestsFailExplicitly() throws Exception {
        new CompactPageHttpContract(http, base).cursorsAndAmbiguousRequestsFailExplicitly();
    }

    @Test
    void pagesHonorBytesWithoutSkippingAndEndCleanly() throws Exception {
        new CompactPageHttpContract(http, base).pagesHonorBytesWithoutSkippingAndEndCleanly();
    }

    @Test
    void extremeInstantsPaginateAndInvalidUtf8CursorsFail() throws Exception {
        new CompactPageHttpContract(http, base).extremeInstantsPaginateAndInvalidUtf8CursorsFail();
    }
}
