package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Opens every read-write transaction with {@code BEGIN IMMEDIATE}, so it takes SQLite's single
 * writer lock before its first statement, where {@code busy_timeout} waits for another writer.
 *
 * <p>sqlite-jdbc begins a transaction with a deferred {@code BEGIN} that takes no lock. The first
 * read then pins a snapshot, and SQLite never waits to upgrade a snapshot to the writer lock: a
 * transaction that reads before it writes fails at its first write with {@code SQLITE_BUSY} while
 * another connection is writing, or with {@code SQLITE_BUSY_SNAPSHOT} once one has committed
 * since that read, without calling the busy handler. Read-only transactions keep the deferred
 * begin and never block writers.
 *
 * <p>A read-write transaction therefore holds the writer lock from begin to commit. Code inside one
 * must never write through another connection ({@code REQUIRES_NEW}, a raw
 * {@code DataSource.getConnection()}, a second {@code DataSource}) and must never block on another
 * thread that writes: that writer waits for this transaction's lock, so the request stalls for the
 * full {@code busy_timeout} and then fails. Mark a unit that only reads {@code readOnly = true};
 * an unmarked one holds the writer lock for its whole duration.
 *
 * <p>The driver's {@code transaction_mode=IMMEDIATE} property is deliberately not used. Its
 * {@code commit()} re-begins IMMEDIATE at once, taking the writer lock again until the pool
 * restores auto-commit, and a begin that times out leaves the driver reporting an open
 * transaction that SQLite does not have, which the pool returns unrepaired.
 */
class SqliteTransactionManager extends JdbcTransactionManager {

    SqliteTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        if (definition.isReadOnly()) {

            return;
        }
        try (Statement statement = con.createStatement()) {
            // Replace the driver's deferred BEGIN, which holds no lock yet, with an immediate one.
            statement.execute("COMMIT");
            try {
                statement.execute("BEGIN IMMEDIATE");
            } catch (SQLException busy) {
                // Reopen the transaction the driver believes is open, so releasing the connection
                // rolls it back and the pool restores auto-commit as usual.
                try {
                    statement.execute("BEGIN");
                } catch (SQLException restore) {
                    busy.addSuppressed(restore);
                }
                throw busy;
            }
        }
    }
}
