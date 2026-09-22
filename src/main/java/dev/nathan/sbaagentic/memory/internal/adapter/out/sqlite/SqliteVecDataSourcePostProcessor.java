package dev.nathan.sbaagentic.memory.internal.adapter.out.sqlite;

import com.zaxxer.hikari.HikariDataSource;
import dev.nathan.sbaagentic.memory.MemoryVectorProperties;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sba.storage.backend", havingValue = "sqlite", matchIfMissing = true)
public class SqliteVecDataSourcePostProcessor implements BeanPostProcessor {

    private final MemoryVectorProperties properties;

    public SqliteVecDataSourcePostProcessor(MemoryVectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof HikariDataSource dataSource)) {

            return bean;
        }
        Optional<Path> extensionPath = SqliteVecSupport.configuredExistingPath(properties);
        if (extensionPath.isEmpty()) {

            return bean;
        }
        dataSource.addDataSourceProperty("enable_load_extension", "true");
        if (SqliteVecSupport.canLoad(
                dataSource.getJdbcUrl(), dataSource.getDataSourceProperties(), extensionPath.get())) {
            dataSource.setConnectionInitSql(SqliteVecSupport.loadExtensionSql(extensionPath.get()));
        }

        return bean;
    }
}
