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
                .containsPattern("<link[^>]+href=\"/assets/index-[\\w-]+\\.css\"");
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

        assertThat(STATIC.resolve("fonts")).isDirectoryContaining("glob:**/inter-latin-400-normal.woff2")
                .isDirectoryContaining("glob:**/inter-latin-500-normal.woff2")
                .isDirectoryContaining("glob:**/inter-latin-600-normal.woff2")
                .isDirectoryContaining("glob:**/inter-latin-700-normal.woff2")
                .isDirectoryContaining("glob:**/ibm-plex-mono-latin-400-normal.woff2")
                .isDirectoryContaining("glob:**/ibm-plex-mono-latin-500-normal.woff2")
                .isDirectoryContaining("glob:**/ibm-plex-mono-latin-600-normal.woff2");
    }
}
