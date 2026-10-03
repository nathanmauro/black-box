package dev.nathan.sbaagentic.memory.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline safety controls; never opens a real connection or creates a schema. */
class EvidencePostgresFixtureTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 5432, 15432, 18877, 18878, 65535})
    void acceptsAnExplicitPortIndependentlyOfCi(int port) {
        for (String host : new String[] {"127.0.0.1", "localhost"}) {
            var target = EvidencePostgresHttpMcpTest.checkedTarget(
                    "jdbc:postgresql://" + host + ":" + port + "/blackbox_test", "blackbox_test");
            assertThat(target.getPort()).isEqualTo(port);
            assertThat(target.getHost()).isEqualTo(host);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://127.0.0.1/blackbox_test",
                "jdbc:postgresql://127.0.0.1:0/blackbox_test",
                "jdbc:postgresql://127.0.0.1:65536/blackbox_test",
                "jdbc:postgresql://192.0.2.1:5432/blackbox_test",
                "jdbc:postgresql://127.0.0.1:5432/other",
                "jdbc:postgresql://127.0.0.1:5432/blackbox_test?currentSchema=public",
                "jdbc:postgresql://127.0.0.1:5432/blackbox_test#fragment",
                "jdbc:postgresql://other@127.0.0.1:5432/blackbox_test",
                "jdbc:postgresql:blackbox_test",
                "jdbc:sqlite:fixture.db"
            })
    void rejectsTargetsOutsideTheExplicitFixtureContract(String url) {
        assertThatThrownBy(() -> EvidencePostgresHttpMcpTest.checkedTarget(url, "blackbox_test"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsAnAlternateRequestedUserBeforeConnection() {
        assertThatThrownBy(() -> EvidencePostgresHttpMcpTest.checkedTarget(
                        "jdbc:postgresql://127.0.0.1:5432/blackbox_test", "other"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture username");
    }

    static Stream<Arguments> incorrectIdentity() {

        return Stream.of(
                Arguments.of("other", "blackbox_test", 15432, "observed fixture database"),
                Arguments.of("blackbox_test", "other", 15432, "observed fixture user"),
                Arguments.of("blackbox_test", "blackbox_test", 5432, "observed fixture server port"));
    }

    @ParameterizedTest
    @MethodSource("incorrectIdentity")
    void rejectsObservedIdentityMismatchBeforeAnyMutation(String database, String user, int port, String message)
            throws Exception {
        Fixture fixture = fixture(database, user, port);
        assertThatThrownBy(() -> EvidencePostgresHttpMcpTest.verifyIdentity(fixture.connection(), 15432, null))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(message);
        verify(fixture.statement(), never()).execute(anyString());
        verify(fixture.statement()).close();
    }

    @Test
    void expectedDirectoryIsOptionalAndExactWhenSupplied() throws Exception {
        Fixture fixture = fixture("blackbox_test", "blackbox_test", 15432);
        EvidencePostgresHttpMcpTest.verifyIdentity(fixture.connection(), 15432, null);
        verify(fixture.statement(), never()).executeQuery("SHOW data_directory");
        var directory = mock(ResultSet.class);
        when(fixture.statement().executeQuery("SHOW data_directory")).thenReturn(directory);
        when(directory.next()).thenReturn(true);
        when(directory.getString(1)).thenReturn("/fixture/postgres/data");
        EvidencePostgresHttpMcpTest.verifyIdentity(fixture.connection(), 15432, "/fixture/postgres/data");
        assertThatThrownBy(() -> EvidencePostgresHttpMcpTest.verifyIdentity(fixture.connection(), 15432, "/other/data"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("observed fixture data directory");
        verify(fixture.statement(), never()).execute(anyString());
    }

    private Fixture fixture(String database, String user, int port) throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet identity = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_database(), current_user, inet_server_port()"))
                .thenReturn(identity);
        when(identity.next()).thenReturn(true);
        when(identity.getString(1)).thenReturn(database);
        when(identity.getString(2)).thenReturn(user);
        when(identity.getInt(3)).thenReturn(port);

        return new Fixture(connection, statement);
    }

    private record Fixture(Connection connection, Statement statement) {}
}
