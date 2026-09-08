package dev.nathan.sbaagentic.project.internal.adapter.out.sqlite;

/** SQL differences only; canonical timestamp text retains nanosecond precision on both engines. */
enum ProjectSqlDialect {
    SQLITE, POSTGRES;

    static ProjectSqlDialect from(String backend) {
        return switch (backend) {
            case "sqlite" -> SQLITE;
            case "postgres" -> POSTGRES;
            default -> throw new IllegalArgumentException("Unsupported storage backend: " + backend);
        };
    }

    String sortableInstant(String column) {
        String position = this == POSTGRES ? "strpos" : "instr";
        return """
                CASE
                    WHEN %2$s(%1$s, '.') = 0
                        THEN substr(%1$s, 1, length(%1$s) - 1) || '.000000000Z'
                    ELSE substr(%1$s, 1, length(%1$s) - 1)
                         || substr('000000000', 1,
                                   9 - (length(%1$s) - %2$s(%1$s, '.') - 1))
                         || 'Z'
                END
                """.formatted(column, position);
    }

    String predicate(String sqlitePredicate) {
        if (this == SQLITE) return sqlitePredicate;
        // These are internal constant CASE expressions, never user SQL. PostgreSQL has real
        // booleans, whereas SQLite represents the predicate arms as 1/0.
        return sqlitePredicate.replace("THEN 1", "THEN TRUE").replace("ELSE 0", "ELSE FALSE")
                .replace("WHEN e.metadata_json LIKE", "WHEN lower(e.metadata_json) LIKE");
    }
}
