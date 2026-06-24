package dev.nathan.sbaagentic.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for the built single-page UI that Spring Boot serves from {@code static/}. The bundle is
 * produced by the SolidJS + Vite build in {@code frontend/}; this asserts the shipped entry point
 * boots that bundle (hashes are matched by pattern so a rebuild does not break the test).
 */
class StaticUiContractTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void indexHtmlBootstrapsTheSolidBundle() throws Exception {
        String html = Files.readString(STATIC.resolve("index.html"));

        assertThat(html)
                .contains("<title>BLACKBOX</title>")
                .contains("id=\"root\"")
                .containsPattern("<script[^>]+type=\"module\"[^>]+src=\"/assets/index-[\\w-]+\\.js\"")
                .containsPattern("<link[^>]+href=\"/assets/index-[\\w-]+\\.css\"")
                .contains("ibm-plex-sans-latin-400-normal.woff2");
    }

    @Test
    void builtBundleAndFontsAreEmitted() throws Exception {
        Path assets = STATIC.resolve("assets");
        assertThat(Files.isDirectory(assets)).isTrue();

        try (Stream<Path> files = Files.list(assets)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertThat(names).anyMatch(name -> name.endsWith(".js"));
            assertThat(names).anyMatch(name -> name.endsWith(".css"));
        }

        assertThat(Files.exists(STATIC.resolve("fonts/ibm-plex-sans-latin-400-normal.woff2"))).isTrue();
    }
}
