package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import org.junit.jupiter.api.Test;

class SqliteVecDataSourcePostProcessorTest {

    @Test
    void unsetSqliteVecPathDoesNotEnableExtensionLoading() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            MemoryVectorProperties properties = new MemoryVectorProperties();
            SqliteVecDataSourcePostProcessor processor = new SqliteVecDataSourcePostProcessor(properties);

            Object processed = processor.postProcessBeforeInitialization(dataSource, "dataSource");

            assertThat(processed).isSameAs(dataSource);
            assertThat(dataSource.getDataSourceProperties()).doesNotContainKey("enable_load_extension");
            assertThat(dataSource.getConnectionInitSql()).isNull();
        }
    }
}
