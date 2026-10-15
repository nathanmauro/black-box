package dev.nathan.sbaagentic.recording.internal.adapter.out.sqlite;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * Replaces Boot's JDBC transaction manager on SQLite only; the PostgreSQL profile keeps Boot's.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sba.storage.backend", havingValue = "sqlite", matchIfMissing = true)
public class SqliteTransactionConfiguration {

    @Bean
    JdbcTransactionManager transactionManager(
            DataSource dataSource, ObjectProvider<TransactionManagerCustomizers> customizers) {
        SqliteTransactionManager manager = new SqliteTransactionManager(dataSource);
        customizers.ifAvailable(each -> each.customize(manager));

        return manager;
    }
}
