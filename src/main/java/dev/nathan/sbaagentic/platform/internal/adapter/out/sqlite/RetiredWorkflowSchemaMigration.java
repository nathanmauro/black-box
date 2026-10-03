package dev.nathan.sbaagentic.platform.internal.adapter.out.sqlite;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in retirement of board storage; never runs during ordinary startup. */
@Component
@ConditionalOnProperty(name = "sba.storage.retire-workflow", havingValue = "true")
@DependsOnDatabaseInitialization
public class RetiredWorkflowSchemaMigration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public RetiredWorkflowSchemaMigration(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${sba.storage.backend:sqlite}") String backend) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = "postgres".equals(backend);
    }

    @PostConstruct
    public void migrate() {
        // Both engines support transactional DDL. Do not disable foreign keys or use CASCADE:
        // an unexpected dependency must fail and roll the entire retirement back.
        transactions.executeWithoutResult(status -> {
            boolean hasTaskId = postgres
                    ? Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM information_schema.columns
                         WHERE table_schema = current_schema() AND table_name = 'session_links'
                           AND column_name = 'task_id')
                        """, Boolean.class))
                    : jdbc.queryForList("PRAGMA table_info(session_links)").stream()
                            .anyMatch(column -> "task_id".equals(column.get("name")));
            if (hasTaskId) {
                jdbc.update("UPDATE session_links SET task_id = NULL WHERE task_id IS NOT NULL");
                jdbc.execute("ALTER TABLE session_links DROP COLUMN task_id");
            }
            jdbc.execute("DROP TABLE IF EXISTS task_events");
            jdbc.execute("DROP TABLE IF EXISTS tasks");
            jdbc.execute("DROP TABLE IF EXISTS specs");
        });
    }
}
