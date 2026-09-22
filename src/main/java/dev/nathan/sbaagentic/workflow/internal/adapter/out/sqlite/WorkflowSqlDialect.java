package dev.nathan.sbaagentic.workflow.internal.adapter.out.sqlite;

/** SQL differences only; canonical timestamp text retains nanosecond precision on both engines. */
enum WorkflowSqlDialect {
    SQLITE,
    POSTGRES;

    static WorkflowSqlDialect from(String backend) {

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

    String claimLock() {

        return this == POSTGRES ? "FOR UPDATE SKIP LOCKED" : "";
    }

    String unlimitedOffset() {

        return this == POSTGRES ? " OFFSET ?" : " LIMIT -1 OFFSET ?";
    }
}
