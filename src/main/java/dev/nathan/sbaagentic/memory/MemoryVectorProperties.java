package dev.nathan.sbaagentic.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.memory.vector")
public class MemoryVectorProperties {

    private String sqliteVecPath = "";

    public String getSqliteVecPath() {

        return sqliteVecPath;
    }

    public void setSqliteVecPath(String sqliteVecPath) {
        this.sqliteVecPath = sqliteVecPath;
    }
}
