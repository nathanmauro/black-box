package dev.nathan.sbaagentic.summary.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.nathan.sbaagentic.recording.AgentSession;
import dev.nathan.sbaagentic.recording.RecordingCatalog;
import dev.nathan.sbaagentic.summary.SummaryExportProperties;
import dev.nathan.sbaagentic.summary.internal.adapter.in.web.SummaryController;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Trusted identical grading at existing controller/service boundaries; never exported to a worker. */
class SummaryExportFixtureContractTest {
    private static Path ownedRoot;
    private Path directory;

    @BeforeAll
    static void requireFilesystemCapabilities() {
        try {
            Path target = Files.createDirectories(Path.of("target").toAbsolutePath());
            ownedRoot = Files.createTempDirectory(target, "summary-export-fixture-");
            Path probe = Files.createDirectory(ownedRoot.resolve("prerequisites"));
            Path original = Files.writeString(probe.resolve("original"), "fixture");
            var permissions = PosixFilePermissions.fromString("rw-r-----");
            Files.setPosixFilePermissions(original, permissions);
            if (!Files.getPosixFilePermissions(original).equals(permissions)) throw new IOException("mode");
            Path hard = Files.createLink(probe.resolve("hard"), original);
            Path link = Files.createSymbolicLink(probe.resolve("link"), original);
            if (!Files.isSameFile(original, hard) || !Files.readString(link).equals("fixture"))
                throw new IOException("links");
            Object key = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
            Object again = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
            if (key == null
                    || !key.equals(again)
                    || Files.readAttributes(probe, BasicFileAttributes.class).fileKey() == null)
                throw new IOException("file identity");
            Path replacement = Files.writeString(probe.resolve("replacement"), "new");
            Files.move(replacement, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!Files.readString(original).equals("new")
                    || !Files.readString(hard).equals("fixture")) throw new IOException("atomic replacement");
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            throw new IllegalStateException("fixture_filesystem_unavailable", ex);
        }
    }

    @BeforeEach
    void createPrivateCaseDirectory() throws IOException {
        directory = Files.createTempDirectory(ownedRoot, "case-");
    }

    @AfterAll
    static void removePrivateTree() throws IOException {
        if (ownedRoot == null)

            return;

        // Files.walk does not follow symbolic links. Timeout cleanup belongs to the outer qualifier.
        try (var paths = Files.walk(ownedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void directorySymlinkPreservesOutsideSentinel() throws Exception {
        Fixture f = fixture();
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("note.md"), "outside directory sentinel");
        Files.createSymbolicLink(f.root.resolve("month"), outside);
        int status = export(f);
        assertEquals("outside directory sentinel", Files.readString(sentinel));
        assertEquals(500, status);
    }

    @Test
    void destinationSymlinkPreservesOutsideSentinel() throws Exception {
        Fixture f = fixture();
        Path sentinel = Files.writeString(directory.resolve("outside.md"), "outside file sentinel");
        Files.createDirectories(f.note.getParent());
        Files.createSymbolicLink(f.note, sentinel);
        int status = export(f);
        assertEquals("outside file sentinel", Files.readString(sentinel));
        assertEquals(500, status);
    }

    @Test
    void hardLinkReplacementPreservesOutsideAlias() throws Exception {
        Fixture f = fixture();
        Path sentinel = Files.writeString(directory.resolve("alias.md"), "outside hard-link sentinel");
        Files.createDirectories(f.note.getParent());
        Files.createLink(f.note, sentinel);
        assertEquals(200, export(f));
        assertEquals("fixture summary", Files.readString(f.note));
        assertEquals("outside hard-link sentinel", Files.readString(sentinel));
        assertFalse(Files.isSameFile(f.note, sentinel));
    }

    @Test
    void ordinaryRepeatedExportsPreserveContentAndExplicitMode() throws Exception {
        Fixture f = fixture();
        previousNote(f);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(200, export(f));
            assertEquals("fixture summary", Files.readString(f.note));
            assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(f.note));
        }
    }

    @Test
    void configuredRootAliasRemainsSupported() throws Exception {
        Fixture f = fixture();
        Path alias = Files.createSymbolicLink(directory.resolve("selected-root"), f.root);
        f.target.setDirectory(alias.toString());
        var result =
                f.mvc.perform(post("/api/sessions/session001/exports/fixture")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        assertEquals("month/note.md", json.path("relativePath").asText());
        assertEquals(f.note.toRealPath(), Path.of(json.path("path").asText()).toRealPath());
        assertEquals("fixture summary", Files.readString(f.note));
    }

    @Test
    void templateFailurePreservesExistingNote() throws Exception {
        Fixture f = fixture();
        previousNote(f);
        f.target.setTemplate("fixture:unavailable");
        assertEquals(500, export(f));
        assertEquals("previous note", Files.readString(f.note));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(f.note));
    }

    @Test
    void traversalStillRejectsWithoutOutsideWrite() throws Exception {
        Fixture f = fixture();
        Path outside = Files.writeString(directory.resolve("note.md"), "traversal sentinel");
        f.target.setSubdirectoryTemplate("..");
        assertEquals(500, export(f));
        assertEquals("traversal sentinel", Files.readString(outside));
        assertFalse(Files.exists(f.note));
    }

    private static void previousNote(Fixture f) throws IOException {
        Files.createDirectories(f.note.getParent());
        Files.writeString(f.note, "previous note");
        Files.setPosixFilePermissions(f.note, PosixFilePermissions.fromString("rw-r-----"));
    }

    private Fixture fixture() throws IOException {
        Path root = Files.createDirectory(directory.resolve("exports"));
        Instant time = Instant.parse("2026-10-02T12:00:00Z");
        var session = new AgentSession(
                "session001", "fixture", "client", "Title", "/fixture", "fixture summary", time, time, 1, null);
        RecordingCatalog catalog = (RecordingCatalog) Proxy.newProxyInstance(
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
        target.setTemplate("fixture:summary");
        target.setSubdirectoryTemplate("month");
        target.setFilenameTemplate("note.md");
        var properties = new SummaryExportProperties();
        properties.setTargets(List.of(target));
        ResourceLoader resources = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {

                return new ByteArrayResource("{{summary}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override
                    public boolean exists() {

                        return location.equals("fixture:summary");
                    }
                };
            }

            @Override
            public ClassLoader getClassLoader() {

                return getClass().getClassLoader();
            }
        };
        var service = new SummaryExportService(catalog, properties, resources);
        var mvc = MockMvcBuilders.standaloneSetup(new SummaryController(null, service))
                .build();

        return new Fixture(root, root.resolve("month/note.md"), target, mvc);
    }

    private static int export(Fixture f) throws Exception {

        return f.mvc.perform(post("/api/sessions/session001/exports/fixture"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private record Fixture(Path root, Path note, SummaryExportProperties.Target target, MockMvc mvc) {}
}
