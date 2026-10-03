package dev.nathan.sbaagentic.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nathan.sbaagentic.SbaAgenticApplication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.support.StandardServletEnvironment;

/** Native recovery rehearsals using generated data only; never restores an operator-selected target. */
class DatabaseRestoreContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> CANONICAL = List.of(
            "agent_sessions",
            "agent_events",
            "project_aliases",
            "human_turn_state",
            "event_capture_receipts",
            "decision_replacements",
            "event_judgments",
            "memory_embeddings",
            "session_melds",
            "session_meld_inputs",
            "session_links");
    private static final Path BACKUP =
            Path.of("scripts/storage/blackbox_backup.py").toAbsolutePath();
    private static final String BODY = "Stored full output\n\"quoted\" café 🧭 " + "x".repeat(8_000);
    private static final String INPUT = " { \"command\" : \"fixture-only\", \"empty\" : \"\" } \n";
    private static final byte[] VECTOR = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1)
            .putFloat(-0.0f)
            .putFloat(0.25f)
            .array();

    @TempDir
    Path temporary;

    @Test
    void sqliteReadOnlyWalBackupRestoresEveryTableAndUsableConsumerState() throws Exception {
        Path directory = temporary.toRealPath();
        Database source = new Database(directory.resolve("source.sqlite"), null);
        Fixture fixture;
        // Keep a connection open after stopping the app so committed WAL frames stay separate
        // from the main file. The backup must observe them without checkpointing the source.
        try (App app = new App(source);
                Connection anchor = source.connect()) {
            execute(anchor, "PRAGMA journal_mode=WAL");
            execute(anchor, "PRAGMA wal_autocheckpoint=0");
            fixture = seed(app);
            app.close();
            assertThat(Files.size(Path.of(source.sqlite() + "-wal"))).isPositive();
            Manifest before = manifest(anchor, source);
            assertFixtureCoverage(before);
            assertSqliteFts(anchor, before, fixture.eventId());
            byte[] sourceBytes = Files.readAllBytes(source.sqlite());
            byte[] walBytes = Files.readAllBytes(Path.of(source.sqlite() + "-wal"));
            Path artifact = directory.resolve("snapshot.sqlite");
            Result backup = backup(source, artifact);
            assertCompletedArtifact(backup, artifact);
            assertThat(Files.readAllBytes(source.sqlite())).isEqualTo(sourceBytes);
            assertThat(Files.readAllBytes(Path.of(source.sqlite() + "-wal"))).isEqualTo(walBytes);
            assertThat(manifest(anchor, source)).isEqualTo(before);

            Path restoredFile = directory.resolve("restored.sqlite");
            Files.copy(artifact, restoredFile);
            Database restored = new Database(restoredFile, null);
            try (Connection connection = restored.connect()) {
                assertThat(manifest(connection, restored)).isEqualTo(before);
                assertSqliteFts(connection, before, fixture.eventId());
                assertThat(queryScalar(connection, "PRAGMA integrity_check")).isEqualTo("ok");
                assertThat(rows(connection, "PRAGMA foreign_key_check")).isEmpty();
            }
            verifyRestoredAndRestart(restored, fixture, before);
            verifyFreshWriteAfterRestore(restored, fixture, before);
            assertThat(manifest(anchor, source)).isEqualTo(before);

            Path corrupt = directory.resolve("corrupt.sqlite");
            Files.writeString(corrupt, "not a SQLite database\n");
            Path rejected = directory.resolve("must-not-be-created.sqlite");
            Result failure = backup(new Database(corrupt, null), rejected);
            assertThat(failure.exit()).isNotZero();
            assertThat(JSON.readTree(failure.output()).path("status").asText()).isEqualTo("failed");
            assertThat(Files.exists(rejected)).isFalse();
            assertThatThrownBy(() -> {
                        try (Connection connection = new Database(corrupt, null).connect()) {
                            queryScalar(connection, "PRAGMA integrity_check");
                        }
                    })
                    .isInstanceOf(SQLException.class);
        }
        // Native sqlite-vec is deliberately disabled: this gate verifies canonical vector bytes
        // and whole-database/FTS recovery, not availability or hydration of a native vector module.
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SBA_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
    void postgresCustomBackupRestoresOnlyItsOwnSchemaAndUsableConsumerState() throws Exception {
        Database database =
                new Database(null, "bb_restore_" + UUID.randomUUID().toString().replace("-", ""));
        boolean created = false;
        try {
            try (Connection connection = database.admin()) {
                execute(connection, "CREATE SCHEMA " + database.schema());
                created = true;
            }
            Fixture fixture;
            try (App app = new App(database)) {
                fixture = seed(app);
            }
            Manifest before;
            try (Connection connection = database.connect()) {
                before = manifest(connection, database);
            }
            assertFixtureCoverage(before);
            Path artifact = temporary.toRealPath().resolve("snapshot.dump");
            assertCompletedArtifact(backup(database, artifact), artifact);
            try (Connection connection = database.connect()) {
                assertThat(manifest(connection, database)).isEqualTo(before);
            }
            // Reject the damaged archive without opening a database or running restore SQL.
            Path corrupt = temporary.toRealPath().resolve("corrupt.dump");
            Files.write(corrupt, Arrays.copyOf(Files.readAllBytes(artifact), 16));
            assertThat(run(List.of("pg_restore", "--list", corrupt.toString()), Map.of())
                            .exit())
                    .isNotZero();
            try (Connection connection = database.connect()) {
                assertThat(manifest(connection, database)).isEqualTo(before);
            }
            // Only the random schema created above is removed. No --clean, public schema, or
            // operator-selected restore target is accepted by this test or the snapshot tool.
            try (Connection connection = database.admin()) {
                execute(connection, "DROP SCHEMA " + database.schema() + " CASCADE");
            }
            List<String> restore = new ArrayList<>(List.of(
                    "pg_restore",
                    "--exit-on-error",
                    "--single-transaction",
                    "--no-owner",
                    "--no-privileges",
                    "--no-password"));
            restore.addAll(database.clientArguments());
            restore.add(artifact.toString());
            assertThat(run(restore, database.credentials()).exit())
                    .as("restore of fixture archive")
                    .isZero();
            try (Connection connection = database.connect()) {
                assertThat(manifest(connection, database)).isEqualTo(before);
            }
            verifyRestoredAndRestart(database, fixture, before);
            verifyFreshWriteAfterRestore(database, fixture, before);
        } finally {
            if (created) {
                try (Connection connection = database.admin()) {
                    execute(connection, "DROP SCHEMA IF EXISTS " + database.schema() + " CASCADE");
                }
            }
        }
    }

    private Fixture seed(App app) throws Exception {
        String repo = "/fixture/restore/" + UUID.randomUUID();
        String alias = repo + "-worktree";
        String projectKey =
                Base64.getUrlEncoder().withoutPadding().encodeToString(repo.getBytes(StandardCharsets.UTF_8));
        String stamp = Instant.ofEpochSecond(Instant.now().minusSeconds(2).getEpochSecond(), 123456789)
                .toString();
        Map<String, Object> event = new LinkedHashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                "restore-parent",
                "eventType",
                "PostToolUse",
                "role",
                "tool",
                "cwd",
                repo,
                "toolName",
                "Bash",
                "text",
                "restore evidence",
                "observedAt",
                stamp));
        event.put("toolInput", Map.of("command", "fixture-only", "empty", ""));
        event.put("toolOutput", Map.of("stdout", BODY));
        event.put("metadata", Map.of("agentId", "fixture-agent", "captureDigest", "fixture-digest"));
        Map<String, Object> receipt = Map.of("captureId", UUID.randomUUID().toString(), "event", event);
        JsonNode captured = app.post("/api/events/idempotent", receipt);
        String eventId = captured.path("eventId").asText();
        String parent = captured.path("sessionId").asText();
        String child = app.post(
                        "/api/events",
                        Map.of(
                                "source",
                                "manual",
                                "clientSessionId",
                                "restore-child",
                                "cwd",
                                alias,
                                "eventType",
                                "Observation",
                                "text",
                                "Child evidence"))
                .path("sessionId")
                .asText();
        app.put("/api/project-aliases", Map.of("aliasKey", alias, "canonicalKey", repo));
        Map<String, Object> decision = new LinkedHashMap<>(Map.of(
                "source",
                "manual",
                "clientSessionId",
                "restore-parent",
                "repo",
                repo,
                "decision",
                "Use original restore strategy",
                "rationale",
                "Initial evidence"));
        String original = app.post("/api/decisions", decision).path("eventId").asText();
        decision.put("decision", "Use revised restore strategy");
        decision.put("rationale", "Verified recovery evidence");
        decision.put("supersedes", original);
        String replacement =
                app.post("/api/decisions", decision).path("eventId").asText();
        app.post(
                "/api/session-links",
                Map.of("parentSessionId", parent, "childSessionId", child, "linkType", "spawned"));
        JsonNode meld = app.post(
                "/api/melds",
                Map.of(
                        "projectKey",
                        projectKey,
                        "title",
                        "Recovery synthesis",
                        "body",
                        "Saved synthesis\n" + "café 🧭",
                        "sessionIds",
                        List.of(child, parent),
                        "savedFromPreview",
                        true));
        JdbcTemplate sql = app.context.getBean(JdbcTemplate.class);
        sql.update(
                "UPDATE agent_sessions SET summary=?, title=?, title_rank=?, spawned_by=? WHERE id=?",
                "Stored summary, no regeneration",
                "Preserved title",
                100,
                "parent-client-provenance",
                parent);
        sql.update(
                "UPDATE agent_events SET tool_input_json=?, turn_id=?, human_text=? WHERE id=?",
                INPUT,
                "",
                null,
                eventId);
        sql.update(
                "INSERT INTO event_judgments(event_id,session_id,beat_id,judge,model,version,answers_json,judged_at) VALUES (?,?,?,?,?,?,?,?)",
                eventId,
                parent,
                "fixture-beat",
                "fixture-judge",
                null,
                "1",
                " { \"salience\" : 0.75 } ",
                stamp);
        sql.update(
                "INSERT INTO memory_embeddings(target_kind,target_id,model,dimensions,vector,content_hash,embedded_at) VALUES (?,?,?,?,?,?,?)",
                "event",
                eventId,
                "fixture-model",
                3,
                VECTOR,
                "fixture-content-hash",
                stamp);
        sql.update(
                "INSERT INTO memory_embeddings(target_kind,target_id,model,dimensions,vector,content_hash,embedded_at) VALUES (?,?,?,?,?,?,?)",
                "session_summary",
                parent,
                "fixture-model",
                3,
                VECTOR,
                "fixture-summary-hash",
                stamp);
        sql.execute("CREATE TABLE specs (id TEXT PRIMARY KEY, body TEXT NOT NULL)");
        sql.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, spec_id TEXT REFERENCES specs(id), body TEXT)");
        sql.execute("CREATE TABLE task_events (id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id), body TEXT)");
        sql.execute("ALTER TABLE session_links ADD COLUMN task_id TEXT REFERENCES tasks(id)");
        sql.update("INSERT INTO specs VALUES (?,?)", "legacy-spec", "Preserve dormant work");
        sql.update("INSERT INTO tasks VALUES (?,?,?)", "legacy-task", "legacy-spec", "");
        sql.update("INSERT INTO task_events VALUES (?,?,?)", "legacy-transition", "legacy-task", null);
        sql.update("UPDATE session_links SET task_id=?", "legacy-task");
        sql.execute(
                "CREATE TABLE fixture_archive (id TEXT PRIMARY KEY, note TEXT, empty_text TEXT, number_value INTEGER, raw_bytes "
                        + (app.database.postgres() ? "BYTEA" : "BLOB") + ", observed_at TEXT)");
        sql.update(
                "INSERT INTO fixture_archive VALUES (?,?,?,?,?,?)",
                "unknown-object",
                null,
                "",
                7,
                new byte[] {0, 1, -1, 10, 13},
                stamp);
        sql.execute("CREATE INDEX idx_fixture_archive_note ON fixture_archive(note)");
        sql.execute("CREATE VIEW fixture_archive_view AS SELECT id, note FROM fixture_archive");

        return new Fixture(
                repo,
                alias,
                projectKey,
                parent,
                child,
                eventId,
                original,
                replacement,
                meld.path("id").asText(),
                receipt,
                app.get("/api/events/" + eventId),
                app.get("/api/projects/" + projectKey + "/melds"));
    }

    private void verifyRestoredAndRestart(Database database, Fixture fixture, Manifest before) throws Exception {
        for (int restart = 0; restart < 2; restart++) {
            try (App app = new App(database)) {
                assertThat(app.get("/api/events/" + fixture.eventId())).isEqualTo(fixture.fullEvent());
                assertThat(app.get("/api/events/" + fixture.eventId())
                                .path("toolInputJson")
                                .asText())
                        .isEqualTo(INPUT);
                assertThat(app.get("/api/sessions/" + fixture.parent())
                                .path("summary")
                                .asText())
                        .isEqualTo("Stored summary, no regeneration");
                assertThat(app.get("/api/sessions/" + fixture.parent() + "/links")
                                .toString())
                        .contains(fixture.child());
                assertThat(app.get("/api/session-links/child-counts?ids=" + fixture.parent())
                                .path(fixture.parent())
                                .asInt())
                        .isEqualTo(1);
                assertThat(app.get("/api/projects/" + fixture.projectKey() + "/melds"))
                        .isEqualTo(fixture.melds());
                assertThat(app.get("/api/sessions/" + fixture.parent() + "/judgments")
                                .get(0)
                                .path("eventId")
                                .asText())
                        .isEqualTo(fixture.eventId());
                JsonNode current =
                        app.get("/api/recall?project=" + encode(fixture.alias()) + "&query=restore&kinds=decision");
                assertThat(current.path("items")).hasSize(1);
                assertThat(current.path("items").get(0).path("eventId").asText())
                        .isEqualTo(fixture.replacement());
                assertThat(current.path("items")
                                .get(0)
                                .path("supersedesEventId")
                                .asText())
                        .isEqualTo(fixture.original());
                app.initializeMcp();
                JsonNode history = app.call(
                        "recallContext",
                        Map.of(
                                "project",
                                fixture.alias(),
                                "query",
                                "restore",
                                "kinds",
                                List.of("decision"),
                                "includeSuperseded",
                                true));
                assertThat(history.path("items")).hasSize(2);
                assertThat(find(history.path("items"), "eventId", fixture.original())
                                .path("supersededByEventId")
                                .asText())
                        .isEqualTo(fixture.replacement());
                JsonNode discovery =
                        app.call("searchContext", Map.of("query", "session:" + fixture.parent() + " evidence"));
                assertThat(find(discovery.path("items"), "eventId", fixture.eventId())
                                .path("sourceReference")
                                .path("eventPath")
                                .asText())
                        .isEqualTo("/api/events/" + fixture.eventId());
                JsonNode replay = app.post("/api/events/idempotent", fixture.receipt());
                assertThat(replay.path("replayed").asBoolean()).isTrue();
                assertThat(replay.path("eventId").asText()).isEqualTo(fixture.eventId());
            }
            try (Connection connection = database.connect()) {
                Manifest after = manifest(connection, database);
                // SQLite may refresh accelerator progress after startup; canonical/legacy state
                // must remain byte-exact, including no extra event or receipt after replay.
                for (String table : before.tables().keySet()) {
                    if (!table.startsWith("event_fts") && !table.equals("search_index_state")) {
                        assertThat(after.tables().get(table))
                                .as(table + " after restart " + restart)
                                .isEqualTo(before.tables().get(table));
                    }
                }
                assertThat(after.objects()).isEqualTo(before.objects());
            }
        }
    }

    private void verifyFreshWriteAfterRestore(Database database, Fixture fixture, Manifest before) throws Exception {
        Map<String, Object> receipt = Map.of(
                "captureId",
                UUID.randomUUID().toString(),
                "event",
                Map.of(
                        "source",
                        "manual",
                        "clientSessionId",
                        "post-restore-probe",
                        "cwd",
                        fixture.repo(),
                        "eventType",
                        "Observation",
                        "text",
                        "freshrecoveryprobe",
                        "metadata",
                        Map.of("kind", "observation")));
        String id;
        try (App app = new App(database)) {
            JsonNode created = app.post("/api/events/idempotent", receipt);
            assertThat(created.path("replayed").asBoolean()).isFalse();
            id = created.path("eventId").asText();
            JsonNode recalled = app.get(
                    "/api/recall?project=" + encode(fixture.repo()) + "&query=freshrecoveryprobe&kinds=observation");
            assertThat(recalled.path("items")).hasSize(1);
            assertThat(recalled.path("items").get(0).path("eventId").asText()).isEqualTo(id);
        }
        try (App restarted = new App(database)) {
            assertThat(restarted.get("/api/events/" + id).path("text").asText()).isEqualTo("freshrecoveryprobe");
            assertThat(restarted
                            .post("/api/events/idempotent", receipt)
                            .path("replayed")
                            .asBoolean())
                    .isTrue();
        }
        try (Connection connection = database.connect()) {
            assertThat(manifest(connection, database)
                            .tables()
                            .get("agent_events")
                            .rows())
                    .hasSize(before.tables().get("agent_events").rows().size() + 1);
        }
    }

    private void assertFixtureCoverage(Manifest manifest) throws Exception {
        assertThat(manifest.tables().keySet())
                .containsAll(CANONICAL)
                .contains("specs", "tasks", "task_events", "fixture_archive");
        for (String table : CANONICAL)
            assertThat(manifest.tables().get(table).rows()).as(table).isNotEmpty();
        assertThat(manifest.tables().get("session_meld_inputs").rows()).hasSize(2);
        assertThat(manifest.tables().get("memory_embeddings").rows()).hasSize(2);
        assertThat(manifest.tables().get("fixture_archive").rows().get(0))
                .contains("null", "text:", "bytes:0001ff0a0d");
        assertThat(manifest.tables().get("agent_events").rows().toString()).contains("123456789Z");
    }

    private static void assertSqliteFts(Connection connection, Manifest before, String eventId) throws Exception {
        assertThat(before.tables().keySet()).contains("event_fts", "search_index_state");
        assertThat(queryScalar(connection, "SELECT complete FROM search_index_state WHERE id='event_fts_backfill'"))
                .isEqualTo("1");
        try (var statement = connection.prepareStatement(
                "SELECT count(*) FROM event_fts JOIN agent_events e ON e.rowid=event_fts.rowid WHERE event_fts MATCH 'restore' AND e.id=?")) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static URI checkedPostgresUri(String url) {
        String error =
                "PostgreSQL restore fixtures require an explicit host/database URL without query, fragment or user information";
        URI uri;
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(error);
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPath() == null
                || !uri.getPath().matches("/[A-Za-z0-9_][A-Za-z0-9_.-]*")
                || uri.getPort() == 0
                || uri.getPort() > 65535) throw new IllegalArgumentException(error);

        return uri;
    }

    private Result backup(Database database, Path output) throws Exception {
        assertThat(BACKUP).as("real snapshot CLI must be present").exists();
        List<String> command =
                new ArrayList<>(List.of("python3", BACKUP.toString(), database.postgres() ? "postgres" : "sqlite"));
        if (database.postgres()) {
            URI uri = database.postgresUri();
            command.addAll(List.of(
                    "--host",
                    uri.getHost(),
                    "--port",
                    Integer.toString(uri.getPort() < 0 ? 5432 : uri.getPort()),
                    "--database",
                    uri.getPath().substring(1),
                    "--username",
                    database.username(),
                    "--schema",
                    database.schema()));
        } else command.addAll(List.of("--source", database.sqlite().toString()));
        command.addAll(List.of("--output", output.toString(), "--execute"));

        return run(command, database.postgres() ? database.credentials() : Map.of());
    }

    private void assertCompletedArtifact(Result result, Path artifact) throws Exception {
        assertThat(result.exit())
                .as("native fixture backup completed: " + result.output())
                .isZero();
        JsonNode report = JSON.readTree(result.output());
        assertThat(report.path("status").asText()).isEqualTo("complete");
        assertThat(report.path("bytes").asLong()).isEqualTo(Files.size(artifact));
        assertThat(report.path("sha256").asText())
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
    }

    private Result run(List<String> command, Map<String, String> credentials) throws Exception {
        Path directory = temporary.toRealPath();
        Path log = Files.createTempFile(directory, "native-process-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        builder.environment().put("HOME", directory.toString());
        builder.environment().put("TMPDIR", directory.toString());
        builder.environment().putAll(credentials);
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Fixture native process exceeded its deadline");
        }

        return new Result(process.exitValue(), Files.readString(log));
    }

    private static Manifest manifest(Connection connection, Database database) throws Exception {
        List<String> names = new ArrayList<>();
        String tables = database.postgres()
                ? "SELECT tablename FROM pg_tables WHERE schemaname='" + database.schema() + "' ORDER BY tablename"
                : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(tables)) {
            while (result.next()) names.add(result.getString(1));
        }
        Map<String, Table> values = new TreeMap<>();
        for (String name : names) {
            List<String> columns = new ArrayList<>();
            List<String> data = new ArrayList<>();
            try (var statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT * FROM " + quote(name))) {
                var metadata = result.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++)
                    columns.add(metadata.getColumnName(index));
                while (result.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        Object value = result.getObject(index);
                        cells.add(
                                value == null
                                        ? "null"
                                        : value instanceof byte[] bytes
                                                ? "bytes:" + HexFormat.of().formatHex(bytes)
                                                : value instanceof Number ? "number:" + value : "text:" + value);
                    }
                    data.add(JSON.writeValueAsString(cells));
                }
            }
            data.sort(String::compareTo);
            values.put(name, new Table(columns, data));
        }
        String objects = database.postgres()
                ? "SELECT 'relation',relname,relkind::text,CASE WHEN relkind='v' THEN pg_get_viewdef(c.oid,true) WHEN relkind='i' THEN pg_get_indexdef(c.oid) ELSE '' END "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"
                        + database.schema()
                        + "' UNION ALL SELECT 'constraint',conname,contype::text,pg_get_constraintdef(c.oid,true) "
                        + "FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='"
                        + database.schema()
                        + "' ORDER BY 1,2,3,4"
                : "SELECT name,type,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name,type";

        return new Manifest(values, rows(connection, objects));
    }

    private static List<String> rows(Connection connection, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                    row.add(rows.getString(index));
                result.add(JSON.writeValueAsString(row));
            }
        }

        return result;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryScalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();

            return result.getString(1);
        }
    }

    private static String quote(String name) {

        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String encode(String value) {

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode find(JsonNode items, String field, String value) {
        for (JsonNode item : items) if (item.path(field).asText().equals(value))

            return item;

        throw new AssertionError("Expected fixture item absent");
    }

    private record Table(List<String> columns, List<String> rows) {}

    private record Manifest(Map<String, Table> tables, List<String> objects) {}

    private record Result(int exit, String output) {}

    private record Fixture(
            String repo,
            String alias,
            String projectKey,
            String parent,
            String child,
            String eventId,
            String original,
            String replacement,
            String meldId,
            Map<String, Object> receipt,
            JsonNode fullEvent,
            JsonNode melds) {}

    private record Database(Path sqlite, String schema) {
        boolean postgres() {

            return schema != null;
        }

        String username() {

            return System.getenv().getOrDefault("SBA_POSTGRES_TEST_USERNAME", "blackbox_test");
        }

        String password() {

            return System.getenv().getOrDefault("SBA_POSTGRES_TEST_PASSWORD", "blackbox_disposable_test");
        }

        URI postgresUri() {

            return checkedPostgresUri(System.getenv("SBA_POSTGRES_TEST_URL"));
        }

        Map<String, String> credentials() {

            return Map.of("PGPASSWORD", password());
        }

        List<String> clientArguments() {
            URI uri = postgresUri();

            return List.of(
                    "--host",
                    uri.getHost(),
                    "--port",
                    Integer.toString(uri.getPort() < 0 ? 5432 : uri.getPort()),
                    "--username",
                    username(),
                    "--dbname",
                    uri.getPath().substring(1));
        }

        String jdbcUrl() {

            return "jdbc:" + postgresUri();
        }

        Connection admin() throws SQLException {

            return DriverManager.getConnection(jdbcUrl(), username(), password());
        }

        Connection connect() throws SQLException {
            if (!postgres())

                return DriverManager.getConnection("jdbc:sqlite:" + sqlite);
            Properties properties = new Properties();
            properties.put("user", username());
            properties.put("password", password());
            properties.put("currentSchema", schema);

            return DriverManager.getConnection(jdbcUrl(), properties);
        }
    }

    private final class App implements AutoCloseable {
        final Database database;
        final ServletWebServerApplicationContext context;
        final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        final String token = UUID.randomUUID().toString() + UUID.randomUUID();
        final String base;
        String session;
        int rpcId;
        boolean closed;

        App(Database database) {
            this.database = database;
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put(
                    "spring.datasource.url",
                    database.postgres() ? database.jdbcUrl() : "jdbc:sqlite:" + database.sqlite());
            properties.put(
                    "spring.datasource.driver-class-name",
                    database.postgres() ? "org.postgresql.Driver" : "org.sqlite.JDBC");
            if (database.postgres()) {
                properties.put("spring.datasource.username", database.username());
                properties.put("spring.datasource.password", database.password());
                properties.put("spring.datasource.hikari.data-source-properties.currentSchema", database.schema());
            }
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            properties.put("server.shutdown", "immediate");
            properties.put("sba.editor.enabled", "false");
            properties.put("sba.local-ai.enabled", "false");
            properties.put("sba.summary.backend", "local");
            properties.put("sba.elasticsearch.enabled", "false");
            properties.put("sba.memory.embedding.enabled", "false");
            properties.put("sba.memory.vector.sqlite-vec-path", "");
            properties.put("sba.ask.embedding-enabled", "false");
            properties.put("sba.storage.retire-workflow", "false");
            properties.put("SBA_AUTH_ENABLED", "true");
            properties.put("SBA_AUTH_PASSWORD", UUID.randomUUID().toString() + UUID.randomUUID());
            properties.put("SBA_AUTH_API_TOKEN", token);
            properties.put("SBA_AUTH_SECURE_COOKIES", "true");
            properties.put("spring.main.banner-mode", "off");
            properties.put("logging.level.root", "WARN");
            var environment = new StandardServletEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(new MapPropertySource("restore-fixture", properties));
            var builder = new SpringApplicationBuilder(SbaAgenticApplication.class).environment(environment);
            if (database.postgres()) builder.profiles("postgres");
            context = (ServletWebServerApplicationContext)
                    builder.run("--spring.config.location=classpath:/application.yml");
            base = "http://127.0.0.1:" + context.getWebServer().getPort();
        }

        JsonNode get(String path) throws Exception {

            return request("GET", path, null);
        }

        JsonNode post(String path, Object body) throws Exception {

            return request("POST", path, body);
        }

        JsonNode put(String path, Object body) throws Exception {

            return request("PUT", path, body);
        }

        JsonNode request(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);

            return JSON.readTree(response.body());
        }

        void initializeMcp() throws Exception {
            rpc(
                    "initialize",
                    Map.of(
                            "protocolVersion",
                            "2024-11-05",
                            "capabilities",
                            Map.of(),
                            "clientInfo",
                            Map.of("name", "restore-fixture", "version", "1")));
            var response = mcp(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            assertThat(response.statusCode()).isBetween(200, 299);
        }

        JsonNode call(String name, Map<String, Object> arguments) throws Exception {
            JsonNode result = rpc("tools/call", Map.of("name", name, "arguments", arguments));
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();

            return JSON.readTree(result.path("content").get(0).path("text").asText());
        }

        JsonNode rpc(String method, Map<String, Object> params) throws Exception {
            int id = ++rpcId;
            var response = mcp(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            String body = response.body();
            if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
                body = body.lines()
                        .filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).strip())
                        .findFirst()
                        .orElseThrow();
            }
            JsonNode envelope = JSON.readTree(body);
            assertThat(envelope.path("id").asInt()).isEqualTo(id);
            assertThat(envelope.has("error")).as(envelope.toString()).isFalse();

            return envelope.path("result");
        }

        HttpResponse<String> mcp(Map<String, Object> body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (session != null) request.header("Mcp-Session-Id", session);
            var response = http.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);

            return response;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                context.close();
                http.close();
            }
        }
    }
}
