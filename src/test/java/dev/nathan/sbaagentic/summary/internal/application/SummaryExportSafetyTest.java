package dev.nathan.sbaagentic.summary.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.summary.SummaryExportProperties;
import dev.nathan.sbaagentic.summary.internal.adapter.in.web.SummaryController;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SummaryExportSafetyTest {
    @TempDir
    Path directory;

    @Test
    void directorySymlinkCannotOverwriteOutsideNote() throws Exception {
        var f = fixture();
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = outside.resolve("note.md");
        Files.writeString(sentinel, "original outside bytes");
        Files.createSymbolicLink(f.root.resolve("2026-10"), outside);
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(sentinel)).isEqualTo("original outside bytes");
    }

    @Test
    void finalSymlinkCannotOverwriteOutsideNote() throws Exception {
        var f = fixture();
        Path sentinel = directory.resolve("outside.md");
        Files.writeString(sentinel, "original outside bytes");
        Files.createDirectories(f.note.getParent());
        Files.createSymbolicLink(f.note, sentinel);
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(sentinel)).isEqualTo("original outside bytes");
    }

    @Test
    void normalExportReplacesCompletelyAndPreservesExistingPermissions() throws Exception {
        var f = fixture();
        assertThat(export(f.mvc)).isEqualTo(200);
        assertThat(Files.readString(f.note)).contains("fixture summary", "black_box_session_id");
        assertThat(Files.getPosixFilePermissions(f.note)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var permissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(f.note, permissions);
        Path template = directory.resolve("template.mustache");
        Files.writeString(template, "replacement {{summary}}");
        f.target.setTemplate(template.toUri().toString());
        assertThat(export(f.mvc)).isEqualTo(200);
        assertThat(Files.readString(f.note)).isEqualTo("replacement fixture summary");
        assertThat(Files.getPosixFilePermissions(f.note)).isEqualTo(permissions);
        assertNoStaging(f.note.getParent());
    }

    @Test
    void nestedDirectoriesAndConfiguredRootAliasesAreSupported() throws Exception {
        var f = fixture();
        Path alias = directory.resolve("selected-root-alias");
        Files.createSymbolicLink(alias, f.root);
        f.target.setDirectory(alias.toString());
        f.target.setSubdirectoryTemplate("deep/nested/month");
        assertThat(export(f.mvc)).isEqualTo(200);
        assertThat(Files.readString(f.root.resolve("deep/nested/month/note.md")))
                .contains("fixture summary");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void danglingDirectoryOrFileLinksCannotCreateOutsideContent(boolean leaf) throws Exception {
        var f = fixture();
        Path missing = directory.resolve("missing-outside");
        if (leaf) Files.createDirectories(f.note.getParent());
        Files.createSymbolicLink(leaf ? f.note : f.note.getParent(), missing);
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void lexicalTraversalAndNonRegularDestinationAreRejected() throws Exception {
        var f = fixture();
        f.target.setSubdirectoryTemplate("../../outside");
        assertThat(export(f.mvc)).isEqualTo(500);
        f.target.setSubdirectoryTemplate("2026-10");
        Files.createDirectories(f.note);
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.isDirectory(f.note)).isTrue();
    }

    @Test
    void renderFailurePreservesPreviousNote() throws Exception {
        var f = fixture();
        previousNote(f);
        f.target.setTemplate(directory.resolve("missing-template").toUri().toString());
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(f.note)).isEqualTo("previous note");
        assertNoStaging(f.note.getParent());
    }

    @Test
    void partialStagedWriteFailurePreservesPreviousNoteAndCleansStaging() throws Exception {
        var f = fixture();
        previousNote(f);
        doAnswer(call -> {
                    Files.writeString(call.getArgument(0), "partial write");
                    throw new IOException("fixture write failure");
                })
                .when(f.service)
                .writeStaged(any(Path.class), anyString());
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(f.note)).isEqualTo("previous note");
        assertNoStaging(f.note.getParent());
    }

    @Test
    void unavailableAtomicMoveFailsWithoutReplacingPreviousNote() throws Exception {
        var f = fixture();
        previousNote(f);
        doThrow(new AtomicMoveNotSupportedException("fixture-stage", "fixture-note", "fixture unsupported"))
                .when(f.service)
                .publish(any(Path.class), any(Path.class));
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(f.note)).isEqualTo("previous note");
        assertNoStaging(f.note.getParent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detectedParentSwapPreservesDestinationAndDoesNotFollowReplacementForCleanup(boolean symlink) throws Exception {
        var f = fixture();
        previousNote(f);
        Path displaced = directory.resolve("displaced-original-parent");
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("note.md"), "outside note");
        doAnswer(call -> {
                    call.callRealMethod();
                    Path staged = call.getArgument(0);
                    Files.move(f.note.getParent(), displaced);
                    if (symlink) Files.createSymbolicLink(f.note.getParent(), outside);
                    else {
                        Files.createDirectory(f.note.getParent());
                        Files.writeString(f.note, "replacement directory note");
                    }
                    Files.writeString(
                            f.note.getParent().resolve(staged.getFileName()), "replacement staged-name sentinel");

                    return null;
                })
                .when(f.service)
                .writeStaged(any(Path.class), anyString());
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(displaced.resolve("note.md"))).isEqualTo("previous note");
        assertThat(Files.readString(outside.resolve("note.md"))).isEqualTo("outside note");
        if (!symlink) assertThat(Files.readString(f.note)).isEqualTo("replacement directory note");
        try (var files = Files.list(f.note.getParent())) {
            Path sentinel = files.filter(p -> p.getFileName().toString().startsWith(".blackbox-export-"))
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readString(sentinel)).isEqualTo("replacement staged-name sentinel");
        }
    }

    @Test
    void stagedReplacementIsRejectedBeforeCopyingPermissions() throws Exception {
        var f = fixture();
        previousNote(f);
        Files.setPosixFilePermissions(f.note, PosixFilePermissions.fromString("rw-r-----"));
        Path outside = directory.resolve("outside-private.md");
        Files.writeString(outside, "outside private note");
        var privatePermissions = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(outside, privatePermissions);
        doAnswer(call -> {
                    call.callRealMethod();
                    Path staged = call.getArgument(0);
                    Files.move(staged, directory.resolve("displaced-staged-file"));
                    Files.createLink(staged, outside);

                    return null;
                })
                .when(f.service)
                .writeStaged(any(Path.class), anyString());
        assertThat(export(f.mvc)).isEqualTo(500);
        assertThat(Files.readString(f.note)).isEqualTo("previous note");
        assertThat(Files.readString(outside)).isEqualTo("outside private note");
        assertThat(Files.getPosixFilePermissions(outside)).isEqualTo(privatePermissions);
    }

    @Test
    void replacingAnExistingHardLinkDoesNotTruncateItsOutsideAlias() throws Exception {
        var f = fixture();
        Path outside = directory.resolve("outside.md");
        Files.writeString(outside, "outside note");
        Files.createDirectories(f.note.getParent());
        Files.createLink(f.note, outside);
        assertThat(export(f.mvc)).isEqualTo(200);
        assertThat(Files.readString(f.note)).contains("fixture summary");
        assertThat(Files.readString(outside)).isEqualTo("outside note");
        assertThat(Files.isSameFile(f.note, outside)).isFalse();
    }

    @Test
    void actualHttpExportPublishesNormallyAndRefusesOutsideSymlink() throws Exception {
        var f = fixture();
        try (var context = new AnnotationConfigServletWebServerApplicationContext();
                var client = HttpClient.newHttpClient()) {
            context.register(HttpFixtureConfiguration.class);
            context.registerBean(ServletWebServerFactory.class, () -> {
                var server = new TomcatServletWebServerFactory(0);
                server.setAddress(InetAddress.getLoopbackAddress());

                return server;
            });
            context.registerBean(SummaryController.class, () -> new SummaryController(null, f.service));
            context.registerBean(
                    ServletRegistrationBean.class,
                    () -> new ServletRegistrationBean<>(new DispatcherServlet(context), "/"));
            context.refresh();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + context.getWebServer().getPort() + "/api/sessions/session001/exports/fixture"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            assertThat(Files.readString(f.note)).contains("fixture summary");
            Path sentinel = directory.resolve("http-outside.md");
            Files.writeString(sentinel, "outside HTTP sentinel");
            Files.delete(f.note);
            Files.createSymbolicLink(f.note, sentinel);
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(500);
            assertThat(Files.readString(sentinel)).isEqualTo("outside HTTP sentinel");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class HttpFixtureConfiguration {}

    private static void previousNote(Fixture f) throws IOException {
        Files.createDirectories(f.note.getParent());
        Files.writeString(f.note, "previous note");
    }

    private static void assertNoStaging(Path parent) throws IOException {
        try (var files = Files.list(parent)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".blackbox-export-"));
        }
    }

    private Fixture fixture() throws Exception {
        Path root = Files.createDirectory(directory.resolve("exports"));
        var time = Instant.parse("2026-10-02T12:00:00Z");
        var session = new AgentSession(
                "session001", "codex", "fixture", "Fixture", "/fixture", "fixture summary", time, time, 1, null);
        RecordingCatalog repository = (RecordingCatalog) Proxy.newProxyInstance(
                RecordingCatalog.class.getClassLoader(),
                new Class<?>[] {RecordingCatalog.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findSessionById"))

                        return Optional.of(session);

                    throw new UnsupportedOperationException(method.getName());
                });
        var target = new SummaryExportProperties.Target();
        target.setId("fixture");
        target.setDirectory(root.toString());
        target.setFilenameTemplate("note.md");
        var properties = new SummaryExportProperties();
        properties.setTargets(List.of(target));
        var service = spy(new SummaryExportService(repository, properties, new DefaultResourceLoader()));
        var mvc = MockMvcBuilders.standaloneSetup(new SummaryController(null, service))
                .build();

        return new Fixture(root, root.resolve("2026-10/note.md"), service, target, mvc);
    }

    private static int export(MockMvc mvc) throws Exception {

        return mvc.perform(post("/api/sessions/session001/exports/fixture"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private record Fixture(
            Path root, Path note, SummaryExportService service, SummaryExportProperties.Target target, MockMvc mvc) {}
}
